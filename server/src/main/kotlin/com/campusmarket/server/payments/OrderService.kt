package com.campusmarket.server.payments

import com.campusmarket.core.DisputeOutcome
import com.campusmarket.core.DisputeReason
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Limits
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.OpenDisputeRequest
import com.campusmarket.core.Order
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.Page
import com.campusmarket.server.AppDeps
import com.campusmarket.server.KeysetCursor
import com.campusmarket.server.PageParams
import com.campusmarket.server.Sql
import com.campusmarket.server.ApiException
import com.campusmarket.server.UserPrincipal
import com.campusmarket.server.UserThrottle
import com.campusmarket.server.asUuid
import com.campusmarket.server.badRequest
import com.campusmarket.server.conflict
import com.campusmarket.server.forbidden
import com.campusmarket.server.instant
import com.campusmarket.server.invalidField
import com.campusmarket.server.isBlockedEitherWay
import com.campusmarket.server.notFound
import com.campusmarket.server.pageOfIds
import com.campusmarket.server.query
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import com.campusmarket.server.uuid
import com.campusmarket.server.withoutControlChars
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.time.toJavaDuration

/** Runs a blocking processor call off the request thread (never inside a DB transaction). */
internal suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

internal fun paymentFailed(message: String): Nothing = throw ApiException(HttpStatusCode.BadGateway, ErrorCodes.PAYMENT_FAILED, message)

internal fun invalidState(message: String): Nothing = conflict(message, ErrorCodes.INVALID_ORDER_STATE)

/** Disputes a buyer may open on one order (they can withdraw one and open another), so holds can't be stretched forever. */
internal const val MAX_DISPUTES_PER_ORDER = 3

/**
 * The order state machine:
 *
 * ```
 * PENDING_PAYMENT ──paid──▶ PAID ──buyer confirms / hold runs out (transfer)──▶ COMPLETED
 *       │                    │  ▲                                                  ▲
 *       │                    │  └─buyer withdraws─┐                                │ buyer confirms / moderator releases
 *       │                    ├─buyer reports a problem / seller suspended──▶ DISPUTED
 *       │                    │                                                  │
 *       └─cancel/expire─▶ CANCELLED   └─seller cancels (refund)──▶ REFUNDED ◀─────┘ seller cancels / moderator refunds
 * ```
 *
 * Each step is a short transaction with a conditional update; processor calls happen between transactions,
 * are idempotent per order, and every step can be retried (by the user, the webhook, a moderator or the background
 * sweep) after a crash or a lost response without moving money twice. Money movements on a held order (PAID or
 * DISPUTED) first claim it (`pending_action`), which keeps a transfer and a refund from ever both happening.
 */
internal class OrderService(private val deps: AppDeps, private val gateway: PaymentGateway, private val options: PaymentOptions) {
    private val db = deps.db
    private val config = deps.config
    private val urls = PaymentUrls(config.publicBaseUrl, config.appDeepLinkBase)
    private val log = LoggerFactory.getLogger(OrderService::class.java)

    /** "Now" for deadlines and suspension checks ([PaymentOptions.clock]; tests move it). */
    fun now(): Instant = options.clock.instant()

    // ---------- create ----------

    private data class Draft(val orderId: UUID, val listingId: UUID, val amountCents: Int, val currency: String, val title: String, val buyerEmail: String)

    suspend fun create(user: UserPrincipal, listingIdRaw: String): Order {
        val listingId = listingIdRaw.asUuid("Listing")
        deps.throttle.acquire(UserThrottle.Action.ORDER, user.id, "You've started a lot of checkouts recently. Try again later.")
        val draft = db.tx { c -> insertPendingOrder(c, user, listingId) }

        val session = try {
            io {
                gateway.createCheckoutSession(
                    CheckoutRequest(
                        orderId = draft.orderId,
                        listingId = draft.listingId,
                        buyerId = user.id,
                        amountCents = draft.amountCents,
                        currency = draft.currency,
                        title = draft.title,
                        buyerEmail = draft.buyerEmail,
                        successUrl = urls.checkoutSuccess(draft.orderId),
                        cancelUrl = urls.checkoutCancel(draft.orderId),
                        expiresAt = Instant.now().plusSeconds(options.checkoutSessionTtl.inWholeSeconds),
                        idempotencyKey = IdempotencyKeys.checkout(draft.orderId),
                    ),
                )
            }
        } catch (e: Throwable) {
            // Undo the reservation. If the session was in fact created, the buyer never saw its URL and it expires on its own.
            log.warn("Checkout session for order {} failed; cancelling the order", draft.orderId, e)
            withContext(NonCancellable) {
                db.tx { c -> c.findOrder(draft.orderId, forUpdate = true)?.takeIf { it.sessionId == null }?.let { c.cancelPendingOrder(it) } }
            }
            if (e is PaymentGatewayException) paymentFailed("We couldn't start checkout right now. Please try again.")
            throw e
        }

        val stillPending = db.tx { c ->
            c.update(
                """UPDATE orders SET stripe_checkout_session_id = ?, checkout_url = ?
                   WHERE id = ? AND status = 'PENDING_PAYMENT' AND stripe_checkout_session_id IS NULL""",
                session.id, session.url, draft.orderId,
            ) == 1 || run {
                // Cancelled while we were creating the session: remember the session (so its webhooks find the order) …
                c.update("UPDATE orders SET stripe_checkout_session_id = COALESCE(stripe_checkout_session_id, ?) WHERE id = ?", session.id, draft.orderId)
                false
            }
        }
        if (!stillPending) {
            // … and make sure it can't be paid. (A payment landing anyway is refunded by the webhook handler.)
            runCatching { io { gateway.expireCheckoutSession(session.id, IdempotencyKeys.expire(draft.orderId)) } }
                .onFailure { log.warn("Couldn't expire checkout session {} of cancelled order {}", session.id, draft.orderId, it) }
        }
        return render(draft.orderId, user.id)
    }

    private fun insertPendingOrder(c: Connection, user: UserPrincipal, listingId: UUID): Draft {
        data class Target(
            val sellerId: UUID, val campus: String, val status: ListingStatus, val price: Int, val currency: String, val title: String,
            val sellerActive: Boolean, val payoutsEnabled: Boolean, val hasAccount: Boolean, val buyerEmail: String,
        )
        // Lock the listing, so the seller can't change its price or title between our read and the reservation (the
        // order would otherwise charge a price the listing no longer shows). Lock the two accounts (shared) first:
        // account deletion and suspension lock the user row and then touch listings, so taking the listing first
        // could deadlock with them; this way they wait for us, or we see their outcome.
        val sellerId = c.queryOne("SELECT seller_id FROM listings WHERE id = ?", listingId) { it.uuid("seller_id") } ?: notFound("Listing")
        c.query("SELECT id FROM users WHERE id = ANY(?) ORDER BY id FOR SHARE", listOf(user.id, sellerId)) { }
        val t = c.queryOne(
            """SELECT l.seller_id, l.campus_domain, l.status, l.price_cents, l.currency, l.title,
                      (s.deleted_at IS NULL AND NOT (s.suspended AND (s.suspended_until IS NULL OR s.suspended_until > now()))) AS seller_active,
                      s.payouts_enabled, s.stripe_account_id IS NOT NULL AS has_account, b.email AS buyer_email
               FROM listings l JOIN users s ON s.id = l.seller_id JOIN users b ON b.id = ?
               WHERE l.id = ?
               FOR UPDATE OF l""",
            user.id, listingId,
        ) { rs ->
            Target(
                rs.uuid("seller_id"), rs.getString("campus_domain"), ListingStatus.valueOf(rs.getString("status")), rs.getInt("price_cents"),
                rs.getString("currency"), rs.getString("title"), rs.getBoolean("seller_active"), rs.getBoolean("payouts_enabled"),
                rs.getBoolean("has_account"), rs.getString("buyer_email"),
            )
        } ?: notFound("Listing")

        val isSeller = t.sellerId == user.id
        if (t.campus != user.campusDomain) notFound("Listing")
        if (!isSeller && t.status in setOf(ListingStatus.REMOVED, ListingStatus.DELETED)) notFound("Listing")
        if (isSeller) badRequest("You can't buy your own listing")
        if (c.isBlockedEitherWay(user.id, t.sellerId)) forbidden("You can't buy from this user", ErrorCodes.BLOCKED)
        if (t.status != ListingStatus.ACTIVE || !t.sellerActive) conflict("This listing is no longer available", ErrorCodes.LISTING_UNAVAILABLE)
        if (!t.payoutsEnabled || !t.hasAccount) conflict("The seller can't accept payments yet", ErrorCodes.PAYOUTS_NOT_ENABLED)
        // Every unpaid order keeps a listing off the market for up to 35 minutes: don't let one buyer hold many.
        val pending = c.queryOne("SELECT count(*) FROM orders WHERE buyer_id = ? AND status = 'PENDING_PAYMENT'", user.id) { it.getInt(1) } ?: 0
        if (pending >= deps.throttle.maxPendingOrders) {
            throw ApiException(HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED, "Finish or cancel your other checkouts before starting a new one.")
        }

        val orderId = UUID.randomUUID()
        val fee = (t.price.toLong() * config.platformFeeBps / 10_000).toInt()
        try {
            c.update(
                """INSERT INTO orders (id, listing_id, buyer_id, seller_id, amount_cents, fee_cents, currency, status)
                   VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING_PAYMENT')""",
                orderId, listingId, user.id, t.sellerId, t.price, fee, t.currency,
            )
        } catch (e: SQLException) {
            // orders_one_live_per_listing: someone else's order holds this listing
            if (e.sqlState == "23505") conflict("This listing is no longer available", ErrorCodes.LISTING_UNAVAILABLE)
            throw e
        }
        if (c.update("UPDATE listings SET status = 'RESERVED', updated_at = now() WHERE id = ? AND status = 'ACTIVE'", listingId) != 1) {
            conflict("This listing is no longer available", ErrorCodes.LISTING_UNAVAILABLE)
        }
        return Draft(orderId, listingId, t.price, t.currency, t.title, t.buyerEmail)
    }

    // ---------- read ----------

    suspend fun get(user: UserPrincipal, idRaw: String): Order {
        val id = idRaw.asUuid("Order")
        val row = db.tx { it.findOrder(id) }?.takeIf { it.isParticipant(user.id) } ?: notFound("Order")
        // Each refresh is a processor API call: past the caller's budget, serve the local state (webhooks still update it).
        if (row.status == OrderStatus.PENDING_PAYMENT && row.sessionId != null &&
            deps.throttle.tryAcquire(UserThrottle.Action.PROCESSOR_REFRESH, user.id)
        ) {
            reconcile(row, row.sessionId)
        }
        return render(id, user.id)
    }

    suspend fun list(user: UserPrincipal, role: String?, page: PageParams): Page<Order> {
        val sql = when (role?.lowercase()) {
            null, "" -> Sql("SELECT o.id, o.created_at FROM orders o WHERE (o.buyer_id = ? OR o.seller_id = ?)", user.id, user.id)
            "buyer" -> Sql("SELECT o.id, o.created_at FROM orders o WHERE o.buyer_id = ?", user.id)
            "seller" -> Sql("SELECT o.id, o.created_at FROM orders o WHERE o.seller_id = ?", user.id)
            else -> invalidField("role", "role must be buyer or seller")
        }.keysetPage("o.created_at", "o.id", KeysetCursor.decode(page.cursor), page.limit)
        return db.tx { c -> c.pageOfIds(sql, page.limit) { ids -> loadOrders(config, ids, user.id, options.autoReleaseAfter) } }
    }

    suspend fun render(id: UUID, viewerId: UUID): Order = db.tx { it.loadOrder(config, id, viewerId, options.autoReleaseAfter) } ?: notFound("Order")

    // ---------- confirm (PAID / DISPUTED → COMPLETED) ----------

    suspend fun confirm(user: UserPrincipal, idRaw: String): Order {
        val id = idRaw.asUuid("Order")
        db.tx { c ->
            val row = c.findOrder(id)?.takeIf { it.isParticipant(user.id) } ?: notFound("Order")
            if (row.buyerId != user.id) forbidden("Only the buyer can confirm receipt")
            if (row.status !in HELD_STATUSES) invalidState("Only a paid order can be confirmed")
        }
        releaseToSeller(id, HELD_STATUSES, DisputeClosure(DisputeOutcome.BUYER_CONFIRMED)) { invalidState("Only a paid order can be confirmed") }
        return render(id, user.id)
    }

    /**
     * Claims held order [id] for a transfer (when its status is one of [statuses]), pays the seller their share and
     * completes the order (listing SOLD), closing an open dispute as [closure] says. [onApplied] runs in the completing
     * transaction. Returns whether this call applied the completion (false: a concurrent release under the same claim,
     * hence the same transfer, completed it first). Throws [ApiException] when the order can't be claimed
     * ([notClaimable]) or the processor fails (the claim is kept while the outcome is unknown).
     */
    suspend fun releaseToSeller(
        id: UUID,
        statuses: Set<OrderStatus>,
        closure: DisputeClosure,
        onApplied: (Connection) -> Unit = {},
        notClaimable: () -> Nothing,
    ): Boolean {
        val order = db.tx { c ->
            c.claimAction(id, PendingAction.TRANSFER, statuses) ?: run {
                if (c.findOrder(id)?.pendingAction == PendingAction.REFUND) invalidState("This order is being refunded")
                notClaimable()
            }
        }
        val transferId = sendTransfer(order)
        return db.tx { c ->
            val applied = c.findOrder(id, forUpdate = true)?.let { c.applyCompleted(it, transferId, closure) } ?: false
            if (applied) onApplied(c)
            applied
        }
    }

    /** The processor half of a release: transfers the seller's share of claimed (TRANSFER) [order]. Null = nothing to send. */
    private suspend fun sendTransfer(order: OrderRow): String? {
        if (order.sellerShareCents <= 0) return null
        val destination = db.tx { c -> c.queryOne("SELECT stripe_account_id FROM users WHERE id = ?", order.sellerId) { it.getString(1) } }
        val paymentIntentId = order.paymentIntentId
        if (destination == null || paymentIntentId == null) {
            db.tx { it.releaseClaim(order.id, PendingAction.TRANSFER) }
            log.error("Order {} can't be paid out: seller account {}, payment {}", order.id, destination, paymentIntentId)
            conflict("The seller's payout account is missing", ErrorCodes.PAYOUTS_NOT_ENABLED)
        }
        return try {
            io {
                gateway.createTransfer(
                    TransferRequest(
                        orderId = order.id,
                        amountCents = order.sellerShareCents,
                        currency = order.currency,
                        destinationAccountId = destination,
                        paymentIntentId = paymentIntentId,
                        idempotencyKey = IdempotencyKeys.transfer(order.id, order.actionAttempt),
                    ),
                )
            }
        } catch (e: PaymentGatewayException) {
            log.warn("Transfer for order {} failed (retryable={})", order.id, e.retryable, e)
            // Unknown outcome: keep the claim so only this same transfer can be retried (same idempotency key).
            if (!e.retryable) db.tx { it.releaseClaim(order.id, PendingAction.TRANSFER) }
            paymentFailed("We couldn't release the payment to the seller right now. Please try again.")
        }
    }

    // ---------- cancel ----------

    /**
     * PENDING_PAYMENT: buyer or seller → CANCELLED. PAID / DISPUTED: the seller only → refund → REFUNDED (the buyer may
     * already hold the item, so they report a problem instead).
     */
    suspend fun cancel(user: UserPrincipal, idRaw: String): Order {
        val id = idRaw.asUuid("Order")
        var row = db.tx { it.findOrder(id) }?.takeIf { it.isParticipant(user.id) } ?: notFound("Order")
        val isSeller = row.sellerId == user.id
        when (row.status) {
            OrderStatus.COMPLETED -> invalidState("A completed order can't be cancelled")
            OrderStatus.CANCELLED, OrderStatus.REFUNDED -> invalidState("This order is already cancelled")
            OrderStatus.PENDING_PAYMENT -> {
                if (resolvePending(row) == PendingResolution.PROCESSING) invalidState("The payment for this order is still processing")
                row = db.tx { it.findOrder(id) }!!
                // Paid at the last moment (or concurrently). The seller may still cancel it (a refund); the buyer may
                // not refund a paid order, so it simply stays PAID.
                if (row.status != OrderStatus.PAID || !isSeller) return render(id, user.id)
            }
            OrderStatus.PAID, OrderStatus.DISPUTED ->
                if (!isSeller) forbidden("Only the seller can cancel a paid order. If something went wrong, report a problem instead.")
        }
        refundBuyer(id, HELD_STATUSES, DisputeClosure(DisputeOutcome.SELLER_REFUNDED)) { invalidState("This order can no longer be cancelled") }
        return render(id, user.id)
    }

    /**
     * Claims held order [id] for a refund (when its status is one of [statuses]), refunds the buyer in full and marks
     * it REFUNDED (listing back on sale), closing an open dispute as [closure] says. [onApplied] runs in that
     * transaction. Returns whether this call applied it (false: it was already refunded). Throws like [releaseToSeller].
     */
    suspend fun refundBuyer(
        id: UUID,
        statuses: Set<OrderStatus>,
        closure: DisputeClosure,
        onApplied: (Connection) -> Unit = {},
        notClaimable: () -> Nothing,
    ): Boolean {
        val order = db.tx { c ->
            c.claimAction(id, PendingAction.REFUND, statuses) ?: run {
                val now = c.findOrder(id)!!
                if (now.status == OrderStatus.REFUNDED) return@tx null
                if (now.pendingAction == PendingAction.TRANSFER) invalidState("The payment is already being released to the seller")
                notClaimable()
            }
        } ?: return false
        val pi = order.paymentIntentId ?: run {
            log.error("Paid order {} has no payment intent", id)
            db.tx { it.releaseClaim(id, PendingAction.REFUND) }
            paymentFailed("This order can't be refunded automatically. Please contact support.")
        }
        val refundId = try {
            io { gateway.refund(RefundRequest(id, pi, IdempotencyKeys.refund(id, order.actionAttempt))) }
        } catch (e: PaymentGatewayException) {
            log.warn("Refund for order {} failed (retryable={})", id, e.retryable, e)
            if (!e.retryable) db.tx { it.releaseClaim(id, PendingAction.REFUND) }
            paymentFailed("We couldn't refund this order right now. Please try again.")
        }
        return db.tx { c ->
            val applied = c.findOrder(id, forUpdate = true)?.let { c.applyRefunded(it, refundId, pi, closure) } ?: false
            if (applied) onApplied(c)
            applied
        }
    }

    // ---------- disputes (buyer) ----------

    /** The buyer of a PAID order reports a problem: PAID → DISPUTED, which freezes the payment until it's resolved. */
    suspend fun openDispute(user: UserPrincipal, idRaw: String, req: OpenDisputeRequest): Order {
        val id = idRaw.asUuid("Order")
        val details = req.details.withoutControlChars().trim()
        if (req.reason == DisputeReason.SELLER_SUSPENDED) invalidField("reason", "Choose what went wrong")
        if (details.length > Limits.DISPUTE_DETAILS_MAX) invalidField("details", "Details must be at most ${Limits.DISPUTE_DETAILS_MAX} characters")
        if (req.reason == DisputeReason.OTHER && details.isEmpty()) invalidField("details", "Tell us what went wrong")
        db.tx { c ->
            val row = c.findOrder(id, forUpdate = true)?.takeIf { it.isParticipant(user.id) } ?: notFound("Order")
            if (row.buyerId != user.id) forbidden("Only the buyer can report a problem with an order")
            when {
                row.status == OrderStatus.DISPUTED -> invalidState("You've already reported a problem with this order")
                row.status != OrderStatus.PAID -> invalidState("You can only report a problem with a paid order that isn't completed yet")
                row.pendingAction == PendingAction.TRANSFER -> invalidState("The payment is already being released to the seller")
                row.pendingAction == PendingAction.REFUND -> invalidState("This order is being refunded")
            }
            if (c.disputeCount(id) >= MAX_DISPUTES_PER_ORDER) {
                invalidState("You've reported problems with this order too many times. Contact support for help.")
            }
            if (!c.openDispute(id, user.id, req.reason, details)) invalidState("This order can't be disputed right now")
        }
        return render(id, user.id)
    }

    /** The buyer withdraws their open dispute: DISPUTED → PAID, and the auto-release clock starts over. */
    suspend fun withdrawDispute(user: UserPrincipal, idRaw: String): Order {
        val id = idRaw.asUuid("Order")
        val now = now()
        db.tx { c ->
            val row = c.findOrder(id, forUpdate = true)?.takeIf { it.isParticipant(user.id) } ?: notFound("Order")
            if (row.buyerId != user.id) forbidden("Only the buyer can withdraw a problem report")
            val open = c.openDisputeOf(id)
            if (row.status != OrderStatus.DISPUTED || open == null) invalidState("There's no open problem report on this order")
            if (open.openedBy == null) {
                invalidState("This order is on hold for review by our team and can't be withdrawn. You can still confirm receipt.")
            }
            when (row.pendingAction) {
                PendingAction.TRANSFER -> invalidState("The payment is already being released to the seller")
                PendingAction.REFUND -> invalidState("This order is being refunded")
                null -> {}
            }
            if (!c.withdrawDispute(id, now)) invalidState("There's no open problem report on this order")
        }
        return render(id, user.id)
    }

    // ---------- pending orders: reconcile / cancel / expire ----------

    enum class PendingResolution { CANCELLED, PAID, PROCESSING, UNCHANGED }

    /**
     * Closes a PENDING_PAYMENT order: first makes sure its checkout can't be paid any more (expiring the session),
     * then cancels it — unless the processor reports the buyer paid in the meantime, in which case the order
     * becomes PAID instead (or, for a suspended seller, is cancelled and refunded).
     */
    suspend fun resolvePending(row: OrderRow): PendingResolution {
        val sessionId = row.sessionId
        if (sessionId != null) {
            val state = try {
                io { gateway.expireCheckoutSession(sessionId, IdempotencyKeys.expire(row.id)) }
            } catch (e: PaymentGatewayException) {
                log.warn("Couldn't expire checkout session {} of order {}", sessionId, row.id, e)
                paymentFailed("We couldn't cancel the checkout right now. Please try again.")
            }
            if (state.status == CheckoutStatus.COMPLETE) {
                if (!state.paid) return PendingResolution.PROCESSING
                applyPaid(sessionId, row.id, state.paymentIntentId)
                return PendingResolution.PAID
            }
        }
        val cancelled = db.tx { c -> c.findOrder(row.id, forUpdate = true)?.let { c.cancelPendingOrder(it) } ?: false }
        return if (cancelled) PendingResolution.CANCELLED else PendingResolution.UNCHANGED
    }

    /** Catches up with the processor when its webhook hasn't arrived (yet): paid → PAID, expired → CANCELLED. */
    private suspend fun reconcile(row: OrderRow, sessionId: String) {
        val state = try {
            io { gateway.retrieveCheckoutSession(sessionId) }
        } catch (e: PaymentGatewayException) {
            log.warn("Couldn't refresh checkout session {} of order {}", sessionId, row.id, e)
            return
        }
        when (state.status) {
            CheckoutStatus.COMPLETE -> if (state.paid) applyPaid(sessionId, row.id, state.paymentIntentId)
            CheckoutStatus.EXPIRED -> db.tx { c -> c.findOrderForCheckout(sessionId, row.id)?.let { c.cancelPendingOrder(it) } }
            CheckoutStatus.OPEN -> {}
        }
    }

    /** Records a completed, paid checkout (outside the webhook path) and refunds it if the order can't take it. */
    suspend fun applyPaid(sessionId: String, orderId: UUID?, paymentIntentId: String?) {
        val now = now()
        val outcome = db.tx { c -> c.findOrderForCheckout(sessionId, orderId)?.let { c.applyCheckoutPaid(it, sessionId, paymentIntentId, now) } }
        if (outcome is PaidOutcome.RefundNeeded) refundStrayPayment(outcome)
    }

    /** Sends back money that arrived for an already-cancelled order; the order ends REFUNDED. */
    suspend fun refundStrayPayment(r: PaidOutcome.RefundNeeded) {
        val refundId = io { gateway.refund(RefundRequest(r.orderId, r.paymentIntentId, IdempotencyKeys.refund(r.orderId, r.attempt))) }
        db.tx { c -> c.findOrder(r.orderId, forUpdate = true)?.let { c.applyRefunded(it, refundId, r.paymentIntentId) } }
    }

    // ---------- background sweep ----------

    /**
     * Cancels PENDING_PAYMENT orders older than [PaymentOptions.pendingOrderTtl] (their sessions expired at
     * [PaymentOptions.checkoutSessionTtl]). Returns how many orders were resolved one way or the other.
     */
    suspend fun expireStaleOrders(): Int {
        val ids = db.tx { it.stalePendingOrders(options.pendingOrderTtl.inWholeSeconds, limit = SWEEP_BATCH) }
        var resolved = 0
        for (id in ids) {
            try {
                val row = db.tx { it.findOrder(id) } ?: continue
                if (row.status != OrderStatus.PENDING_PAYMENT) continue
                when (resolvePending(row)) {
                    PendingResolution.CANCELLED, PendingResolution.PAID -> resolved++
                    PendingResolution.PROCESSING -> log.info("Order {} is past its checkout window but its payment is still processing", id)
                    PendingResolution.UNCHANGED -> {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Couldn't expire stale order {}", id, e)
            }
        }
        return resolved
    }

    /**
     * Orders of sellers who are suspended now: unpaid checkouts are closed (sessions expired, orders cancelled; a
     * payment that slipped in is refunded), and paid orders are frozen as DISPUTED (reason SELLER_SUSPENDED) for a
     * moderator to refund or release. Nothing is ever released to a suspended seller automatically. Returns how many
     * orders were changed.
     */
    suspend fun holdSuspendedSellersOrders(): Int {
        val now = now()
        val rows = db.tx { it.liveOrdersOfSuspendedSellers(now, limit = SWEEP_BATCH) }
        var changed = 0
        for ((id, status) in rows) {
            try {
                when (status) {
                    OrderStatus.PENDING_PAYMENT -> {
                        val row = db.tx { it.findOrder(id) } ?: continue
                        if (row.status != OrderStatus.PENDING_PAYMENT) continue
                        when (resolvePending(row)) {
                            PendingResolution.CANCELLED, PendingResolution.PAID -> changed++
                            PendingResolution.PROCESSING, PendingResolution.UNCHANGED -> {}
                        }
                    }
                    OrderStatus.PAID -> if (db.tx { it.disputeForSuspendedSeller(id, now) }) {
                        log.info("Order {} put on hold: its seller is suspended", id)
                        changed++
                    }
                    else -> {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Couldn't put order {} of a suspended seller on hold", id, e)
            }
        }
        return changed
    }

    /**
     * Releases every PAID order whose hold has run out ([PaymentOptions.autoReleaseAfter] after payment or after the
     * buyer withdrew a dispute) to its seller: transfer, COMPLETED, listing SOLD. Each order is claimed exactly like a
     * buyer confirmation, so a concurrent confirm, dispute or seller refund yields exactly one outcome; a processor
     * failure leaves the order PAID (or claimed, with the same idempotency key) for the next run. Returns how many
     * orders this run completed.
     */
    suspend fun releaseDueOrders(): Int {
        val now = now()
        val cutoff = now.minus(options.autoReleaseAfter.toJavaDuration())
        val ids = db.tx { it.ordersDueForRelease(cutoff, now, limit = SWEEP_BATCH) }
        var released = 0
        for (id in ids) {
            try {
                val claimed = db.tx { it.claimForAutoRelease(id, cutoff, now) } ?: continue
                val transferId = sendTransfer(claimed)
                val applied = db.tx { c ->
                    c.findOrder(id, forUpdate = true)?.let { c.applyCompleted(it, transferId, DisputeClosure(DisputeOutcome.BUYER_CONFIRMED)) } ?: false
                }
                if (applied) {
                    log.info("Order {} auto-released to its seller", id)
                    released++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Couldn't auto-release order {}; will retry", id, e)
            }
        }
        return released
    }

    private companion object {
        const val SWEEP_BATCH = 200
    }
}
