package com.campusmarket.server

import com.campusmarket.core.ErrorCodes
import io.ktor.http.HttpStatusCode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** A token bucket: at most [limit] actions in a burst, refilled evenly over [period]. */
data class Budget(val limit: Int, val period: Duration) {
    init {
        require(limit >= 1) { "budget limit must be >= 1" }
        require(period.isPositive()) { "budget period must be positive" }
    }
}

/**
 * Per-user budgets for authenticated actions where abuse is plausible: chat spam, report bombing, reserving
 * listings without paying, and amplifying calls to the payment processor. Deliberately generous: no real person
 * should ever meet them. (The public auth endpoints have their own per-IP limits in the auth feature.)
 */
data class UserRateLimits(
    val enabled: Boolean = true,
    /** Chat messages, REST and websocket combined. */
    val messages: Budget = Budget(60, 1.minutes),
    /** Newly started conversations. */
    val conversations: Budget = Budget(30, 1.hours),
    val reports: Budget = Budget(30, 1.hours),
    /** New listings (photos can be reused across listings, so the photo upload quota doesn't bound them). */
    val listings: Budget = Budget(30, 1.hours),
    /** Order attempts (each creates a Checkout Session and reserves a listing). */
    val orders: Budget = Budget(20, 1.hours),
    /** A buyer's orders awaiting payment at the same time (each keeps a listing RESERVED for up to 35 minutes). */
    val maxPendingOrders: Int = 3,
    /** On-demand refreshes from the payment processor (order / payout status); past it, local state is served. */
    val processorRefreshes: Budget = Budget(30, 1.minutes),
) {
    init {
        require(maxPendingOrders >= 1) { "maxPendingOrders must be >= 1" }
    }
}

/** In-memory per-user token buckets (single node, like the chat hub). */
class UserThrottle(private val limits: UserRateLimits, private val nanoTime: () -> Long = System::nanoTime) {

    enum class Action { MESSAGE, CONVERSATION, REPORT, LISTING, ORDER, PROCESSOR_REFRESH }

    private class Bucket(var tokens: Double, var at: Long)

    private data class Key(val action: Action, val userId: UUID)

    private val buckets = ConcurrentHashMap<Key, Bucket>()
    private val lastPrune = AtomicLong(nanoTime())

    val maxPendingOrders: Int get() = limits.maxPendingOrders

    private fun budget(action: Action): Budget = when (action) {
        Action.MESSAGE -> limits.messages
        Action.CONVERSATION -> limits.conversations
        Action.REPORT -> limits.reports
        Action.LISTING -> limits.listings
        Action.ORDER -> limits.orders
        Action.PROCESSOR_REFRESH -> limits.processorRefreshes
    }

    /** Takes one token from [userId]'s [action] bucket; false (nothing taken) when it is empty. */
    fun tryAcquire(action: Action, userId: UUID): Boolean {
        if (!limits.enabled) return true
        val budget = budget(action)
        val now = nanoTime()
        val bucket = buckets.computeIfAbsent(Key(action, userId)) { Bucket(budget.limit.toDouble(), now) }
        val ok = synchronized(bucket) {
            refill(bucket, budget, now)
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                true
            } else {
                false
            }
        }
        pruneOccasionally(now)
        return ok
    }

    /** Like [tryAcquire], but answers 429 `RATE_LIMITED` with [message] when the budget is spent. */
    fun acquire(action: Action, userId: UUID, message: String) {
        if (!tryAcquire(action, userId)) throw ApiException(HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED, message)
    }

    private fun refill(bucket: Bucket, budget: Budget, now: Long) {
        val perNano = budget.limit / budget.period.inWholeNanoseconds.toDouble()
        bucket.tokens = minOf(budget.limit.toDouble(), bucket.tokens + (now - bucket.at).coerceAtLeast(0) * perNano)
        bucket.at = now
    }

    /** Forgets buckets that have refilled completely (they behave exactly like a fresh one), at most once a minute. */
    private fun pruneOccasionally(now: Long) {
        val last = lastPrune.get()
        if (now - last < PRUNE_EVERY_NANOS || !lastPrune.compareAndSet(last, now)) return
        buckets.entries.removeIf { (key, bucket) ->
            val budget = budget(key.action)
            synchronized(bucket) {
                refill(bucket, budget, now)
                bucket.tokens >= budget.limit
            }
        }
    }

    private companion object {
        const val PRUNE_EVERY_NANOS = 60_000_000_000L
    }
}
