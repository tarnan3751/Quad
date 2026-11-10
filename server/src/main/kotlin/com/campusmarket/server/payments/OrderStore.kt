package com.campusmarket.server.payments

import com.campusmarket.core.DisputeOutcome
import com.campusmarket.core.DisputeReason
import com.campusmarket.core.OrderStatus
import com.campusmarket.server.instant
import com.campusmarket.server.instantOrNull
import com.campusmarket.server.query
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import com.campusmarket.server.uuid
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/*
 * Order rows and the database half of every order state transition. Everything here runs inside a caller's
 * transaction and never touches the network. Transitions are conditional on the current status
 * (`UPDATE ... WHERE status = ?`), so concurrent webhooks, user actions and the background sweep can't trample
 * each other; callers that branch on the current state lock the row first (`forUpdate`).
 */

private val log = LoggerFactory.getLogger("com.campusmarket.server.payments.OrderStore")

/** Statuses in which an order holds its listing (RESERVED); `orders_one_live_per_listing` enforces one per listing. */
internal const val LIVE_ORDER_STATUSES = "('PENDING_PAYMENT', 'PAID', 'DISPUTED')"

/** Paid and not yet settled: the platform holds the money. */
internal val HELD_STATUSES = setOf(OrderStatus.PAID, OrderStatus.DISPUTED)

/** A money movement in flight for a held order; see V2_payments_1__order_actions.sql. */
internal enum class PendingAction { TRANSFER, REFUND }

internal data class OrderRow(
    val id: UUID,
    val listingId: UUID,
    val buyerId: UUID,
    val sellerId: UUID,
    val amountCents: Int,
    val feeCents: Int,
    val currency: String,
    val status: OrderStatus,
    val sessionId: String?,
    val paymentIntentId: String?,
    val transferId: String?,
    val refundId: String?,
    val checkoutUrl: String?,
    val pendingAction: PendingAction?,
    val actionAttempt: Int,
    val createdAt: Instant,
    val paidAt: Instant?,
    val completedAt: Instant?,
    val cancelledAt: Instant?,
    /** Start of the auto-release clock while PAID: the payment, or the last withdrawn dispute. */
    val releaseClockFrom: Instant?,
) {
    fun isParticipant(userId: UUID) = userId == buyerId || userId == sellerId
    val sellerShareCents: Int get() = amountCents - feeCents

    /** [releaseClockFrom], falling back like [RELEASE_CLOCK] for rows written without it. */
    val releaseClockStart: Instant get() = releaseClockFrom ?: paidAt ?: createdAt
}

/** SQL for the start of a PAID order's auto-release clock (matches `orders_auto_release_idx`). */
internal const val RELEASE_CLOCK = "COALESCE(o.release_clock_from, o.paid_at, o.created_at)"

internal const val ORDER_COLUMNS = """o.id, o.listing_id, o.buyer_id, o.seller_id, o.amount_cents, o.fee_cents, o.currency, o.status,
    o.stripe_checkout_session_id, o.stripe_payment_intent_id, o.stripe_transfer_id, o.stripe_refund_id, o.checkout_url,
    o.pending_action, o.action_attempt, o.created_at, o.paid_at, o.completed_at, o.cancelled_at, o.release_clock_from"""

internal fun ResultSet.toOrderRow() = OrderRow(
    id = uuid("id"),
    listingId = uuid("listing_id"),
    buyerId = uuid("buyer_id"),
    sellerId = uuid("seller_id"),
    amountCents = getInt("amount_cents"),
    feeCents = getInt("fee_cents"),
    currency = getString("currency"),
    status = OrderStatus.valueOf(getString("status")),
    sessionId = getString("stripe_checkout_session_id"),
    paymentIntentId = getString("stripe_payment_intent_id"),
    transferId = getString("stripe_transfer_id"),
    refundId = getString("stripe_refund_id"),
    checkoutUrl = getString("checkout_url"),
    pendingAction = getString("pending_action")?.let(PendingAction::valueOf),
    actionAttempt = getInt("action_attempt"),
    createdAt = instant("created_at"),
    paidAt = instantOrNull("paid_at"),
    completedAt = instantOrNull("completed_at"),
    cancelledAt = instantOrNull("cancelled_at"),
    releaseClockFrom = instantOrNull("release_clock_from"),
)

private fun lock(forUpdate: Boolean) = if (forUpdate) " FOR UPDATE" else ""

internal fun Connection.findOrder(id: UUID, forUpdate: Boolean = false): OrderRow? =
    queryOne("SELECT $ORDER_COLUMNS FROM orders o WHERE o.id = ?${lock(forUpdate)}", id) { it.toOrderRow() }

internal fun Connection.findOrderBySession(sessionId: String, forUpdate: Boolean = false): OrderRow? =
    queryOne("SELECT $ORDER_COLUMNS FROM orders o WHERE o.stripe_checkout_session_id = ?${lock(forUpdate)}", sessionId) { it.toOrderRow() }

internal fun Connection.findOrderByPaymentIntent(paymentIntentId: String, forUpdate: Boolean = false): OrderRow? =
    queryOne("SELECT $ORDER_COLUMNS FROM orders o WHERE o.stripe_payment_intent_id = ?${lock(forUpdate)}", paymentIntentId) { it.toOrderRow() }

/**
 * The order a checkout session belongs to (locked): by session id, else by the order id carried in the session's
 * `client_reference_id`/metadata — but only if that order has no other session recorded.
 */
internal fun Connection.findOrderForCheckout(sessionId: String, orderId: UUID?): OrderRow? =
    findOrderBySession(sessionId, forUpdate = true)
        ?: orderId?.let { findOrder(it, forUpdate = true) }?.takeIf { it.sessionId == null || it.sessionId == sessionId }

/** Puts a RESERVED listing back on sale, unless another live order holds it. */
internal fun Connection.releaseListing(listingId: UUID): Boolean = update(
    """UPDATE listings SET status = 'ACTIVE', updated_at = now()
       WHERE id = ? AND status = 'RESERVED'
         AND NOT EXISTS (SELECT 1 FROM orders WHERE listing_id = ? AND status IN $LIVE_ORDER_STATUSES)""",
    listingId, listingId,
) == 1

/** Whether [userId] is suspended at [now] (a timed suspension that has run out no longer counts). [lock]: FOR SHARE. */
internal fun Connection.isUserSuspended(userId: UUID, now: Instant, lock: Boolean = false): Boolean =
    queryOne(
        "SELECT suspended AND (suspended_until IS NULL OR suspended_until > ?) FROM users WHERE id = ?${if (lock) " FOR SHARE" else ""}",
        now, userId,
    ) { it.getBoolean(1) } ?: false

// ---------- transitions ----------

internal sealed interface PaidOutcome {
    /** PENDING_PAYMENT → PAID. */
    data object Applied : PaidOutcome

    /** Duplicate / stale / not ours: nothing to do. */
    data object NoChange : PaidOutcome

    /** Money arrived for an order that was already cancelled (or can't be accepted): it has to go back to the buyer. */
    data class RefundNeeded(val orderId: UUID, val paymentIntentId: String, val attempt: Int) : PaidOutcome
}

/**
 * The buyer's checkout completed and the money was collected. [order] must be locked by the caller. A payment for
 * a seller who is suspended at [now] isn't accepted: the order is cancelled and the money goes back to the buyer.
 */
internal fun Connection.applyCheckoutPaid(order: OrderRow, sessionId: String, paymentIntentId: String?, now: Instant): PaidOutcome {
    if (paymentIntentId == null) {
        log.error("Paid checkout session {} for order {} carries no payment intent; ignoring", sessionId, order.id)
        return PaidOutcome.NoChange
    }
    return when (order.status) {
        OrderStatus.PENDING_PAYMENT -> if (isUserSuspended(order.sellerId, now)) {
            log.warn("Payment {} arrived for order {} of suspended seller {}; cancelling and refunding", paymentIntentId, order.id, order.sellerId)
            val n = update(
                """UPDATE orders SET status = 'CANCELLED', cancelled_at = now(), paid_at = now(), stripe_payment_intent_id = ?,
                          stripe_checkout_session_id = COALESCE(stripe_checkout_session_id, ?)
                   WHERE id = ? AND status = 'PENDING_PAYMENT'""",
                paymentIntentId, sessionId, order.id,
            )
            if (n == 1) {
                releaseListing(order.listingId)
                PaidOutcome.RefundNeeded(order.id, paymentIntentId, order.actionAttempt)
            } else {
                PaidOutcome.NoChange
            }
        } else {
            val n = update(
                """UPDATE orders SET status = 'PAID', paid_at = now(), release_clock_from = ?, stripe_payment_intent_id = ?,
                          stripe_checkout_session_id = COALESCE(stripe_checkout_session_id, ?)
                   WHERE id = ? AND status = 'PENDING_PAYMENT'""",
                now, paymentIntentId, sessionId, order.id,
            )
            if (n == 1) PaidOutcome.Applied else PaidOutcome.NoChange
        }
        OrderStatus.CANCELLED -> when {
            order.paymentIntentId == null -> {
                log.warn("Payment {} arrived for cancelled order {}; refunding", paymentIntentId, order.id)
                update(
                    """UPDATE orders SET stripe_payment_intent_id = ?, paid_at = now(),
                              stripe_checkout_session_id = COALESCE(stripe_checkout_session_id, ?)
                       WHERE id = ? AND status = 'CANCELLED' AND stripe_payment_intent_id IS NULL""",
                    paymentIntentId, sessionId, order.id,
                )
                PaidOutcome.RefundNeeded(order.id, paymentIntentId, order.actionAttempt)
            }
            // an earlier refund attempt for this stray payment failed: try again
            order.paymentIntentId == paymentIntentId && order.refundId == null ->
                PaidOutcome.RefundNeeded(order.id, paymentIntentId, order.actionAttempt)
            else -> PaidOutcome.NoChange
        }
        // PAID (duplicate delivery), DISPUTED, COMPLETED, REFUNDED: already past this point
        else -> PaidOutcome.NoChange
    }
}

/** The checkout can no longer be paid (expired, or an async payment failed): PENDING_PAYMENT → CANCELLED. */
internal fun Connection.cancelPendingOrder(order: OrderRow): Boolean {
    if (order.status != OrderStatus.PENDING_PAYMENT) return false
    val n = update("UPDATE orders SET status = 'CANCELLED', cancelled_at = now() WHERE id = ? AND status = 'PENDING_PAYMENT'", order.id)
    if (n == 1) releaseListing(order.listingId)
    return n == 1
}

/**
 * The order's payment was fully refunded (by us or from the Stripe dashboard). [order] must be locked by the caller.
 * An open dispute is closed as [closure] says.
 */
internal fun Connection.applyRefunded(
    order: OrderRow,
    refundId: String?,
    paymentIntentId: String?,
    closure: DisputeClosure = DisputeClosure(DisputeOutcome.REFUNDED),
): Boolean = when (order.status) {
    OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.DISPUTED -> {
        val n = update(
            """UPDATE orders SET status = 'REFUNDED', cancelled_at = now(), pending_action = NULL,
                      stripe_refund_id = COALESCE(stripe_refund_id, ?),
                      stripe_payment_intent_id = COALESCE(stripe_payment_intent_id, ?)
               WHERE id = ? AND status IN $LIVE_ORDER_STATUSES""",
            refundId, paymentIntentId, order.id,
        )
        if (n == 1) {
            closeOpenDispute(order.id, closure)
            releaseListing(order.listingId)
        }
        n == 1
    }
    // a stray payment on a cancelled order went back to the buyer (the listing was released at cancellation)
    OrderStatus.CANCELLED -> update(
        """UPDATE orders SET status = 'REFUNDED', pending_action = NULL, stripe_refund_id = COALESCE(stripe_refund_id, ?),
                  stripe_payment_intent_id = COALESCE(stripe_payment_intent_id, ?)
           WHERE id = ? AND status = 'CANCELLED'""",
        refundId, paymentIntentId, order.id,
    ) == 1
    OrderStatus.REFUNDED -> {
        if (refundId != null) update("UPDATE orders SET stripe_refund_id = COALESCE(stripe_refund_id, ?) WHERE id = ?", refundId, order.id)
        false
    }
    OrderStatus.COMPLETED -> {
        log.warn("Order {} was refunded after the seller was paid (transfer {}); reverse the transfer manually", order.id, order.transferId)
        false
    }
}

/**
 * The seller was paid: PAID / DISPUTED → COMPLETED, listing → SOLD; an open dispute is closed as [closure] says.
 * [order] must be locked by the caller.
 */
internal fun Connection.applyCompleted(
    order: OrderRow,
    transferId: String?,
    closure: DisputeClosure = DisputeClosure(DisputeOutcome.BUYER_CONFIRMED),
): Boolean {
    val n = update(
        """UPDATE orders SET status = 'COMPLETED', completed_at = now(), stripe_transfer_id = ?, pending_action = NULL
           WHERE id = ? AND status IN ('PAID', 'DISPUTED')""",
        transferId, order.id,
    )
    if (n == 1) {
        closeOpenDispute(order.id, closure)
        update("UPDATE listings SET status = 'SOLD', updated_at = now() WHERE id = ? AND status = 'RESERVED'", order.listingId)
    } else {
        // A concurrent release under the same claim (same idempotency key, so the very same transfer) completed it first.
        if (order.status != OrderStatus.COMPLETED) log.error("Transfer {} made for order {} but the order is {}", transferId, order.id, order.status)
        update("UPDATE orders SET stripe_transfer_id = COALESCE(stripe_transfer_id, ?) WHERE id = ?", transferId, order.id)
    }
    return n == 1
}

/**
 * Claims a held order for [action] if its status is one of [statuses] (PAID and/or DISPUTED). Re-entrant: a retry of
 * the same action succeeds, also by another actor (buyer confirm vs auto-release, seller refund vs moderator refund),
 * and uses the same idempotency key, so the money still moves once. Exclusive across actions. Returns the claimed
 * row, or null if the status doesn't match or the other action holds the order.
 */
internal fun Connection.claimAction(orderId: UUID, action: PendingAction, statuses: Set<OrderStatus> = setOf(OrderStatus.PAID)): OrderRow? {
    require(statuses.isNotEmpty() && statuses.all { it in HELD_STATUSES }) { "only held orders can be claimed" }
    return queryOne(
        """UPDATE orders o SET pending_action = ?
           WHERE o.id = ? AND o.status = ANY(?) AND (o.pending_action IS NULL OR o.pending_action = ?)
           RETURNING $ORDER_COLUMNS""",
        action, orderId, statuses, action,
    ) { it.toOrderRow() }
}

/** Drops a claim after the processor definitively rejected the action; the next attempt gets a fresh idempotency key. */
internal fun Connection.releaseClaim(orderId: UUID, action: PendingAction) {
    update(
        """UPDATE orders SET pending_action = NULL, action_attempt = action_attempt + 1
           WHERE id = ? AND status IN ('PAID', 'DISPUTED') AND pending_action = ?""",
        orderId, action,
    )
}

// ---------- disputes ----------

/** How an open dispute is closed by the transition that ends it. [resolvedBy] / [note]: moderator outcomes only. */
internal data class DisputeClosure(val outcome: DisputeOutcome, val resolvedBy: UUID? = null, val note: String? = null)

/** Closes the order's OPEN dispute, if it has one. Returns whether it had. */
internal fun Connection.closeOpenDispute(orderId: UUID, closure: DisputeClosure): Boolean = update(
    """UPDATE order_disputes SET status = 'CLOSED', outcome = ?, closed_at = now(), resolved_by = ?, resolution_note = ?
       WHERE order_id = ? AND status = 'OPEN'""",
    closure.outcome, closure.resolvedBy, closure.note, orderId,
) == 1

/**
 * PAID → DISPUTED with a new OPEN dispute, unless money is already moving (a claim is held). [openedBy] null = the
 * platform. Returns false (nothing changed) when the order isn't in that state.
 */
internal fun Connection.openDispute(orderId: UUID, openedBy: UUID?, reason: DisputeReason, details: String): Boolean {
    val n = update("UPDATE orders SET status = 'DISPUTED' WHERE id = ? AND status = 'PAID' AND pending_action IS NULL", orderId)
    if (n == 1) {
        update(
            "INSERT INTO order_disputes (id, order_id, opened_by, reason, details) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), orderId, openedBy, reason, details,
        )
    }
    return n == 1
}

/** DISPUTED → PAID (the buyer withdrew); the auto-release clock starts again at [now]. */
internal fun Connection.withdrawDispute(orderId: UUID, now: Instant): Boolean {
    val n = update(
        "UPDATE orders SET status = 'PAID', release_clock_from = ? WHERE id = ? AND status = 'DISPUTED' AND pending_action IS NULL",
        now, orderId,
    )
    if (n == 1) closeOpenDispute(orderId, DisputeClosure(DisputeOutcome.WITHDRAWN))
    return n == 1
}

internal fun Connection.disputeCount(orderId: UUID): Int =
    queryOne("SELECT count(*) FROM order_disputes WHERE order_id = ?", orderId) { it.getInt(1) } ?: 0

// ---------- background sweep ----------

private const val SELLER_SUSPENDED_AT = "s.suspended AND (s.suspended_until IS NULL OR s.suspended_until > ?)"

/** Ids of PENDING_PAYMENT orders created more than [olderThanSeconds] ago, oldest first. */
internal fun Connection.stalePendingOrders(olderThanSeconds: Long, limit: Int): List<UUID> =
    query(
        """SELECT id FROM orders
           WHERE status = 'PENDING_PAYMENT' AND created_at < now() - (? * interval '1 second')
           ORDER BY created_at LIMIT ?""",
        olderThanSeconds, limit,
    ) { it.uuid("id") }

/**
 * PAID orders whose auto-release clock started at or before [cutoff], whose seller isn't suspended at [now] and
 * that aren't being refunded, oldest first.
 */
internal fun Connection.ordersDueForRelease(cutoff: Instant, now: Instant, limit: Int): List<UUID> =
    query(
        """SELECT o.id FROM orders o JOIN users s ON s.id = o.seller_id
           WHERE o.status = 'PAID' AND $RELEASE_CLOCK <= ?
             AND (o.pending_action IS NULL OR o.pending_action = 'TRANSFER')
             AND NOT ($SELLER_SUSPENDED_AT)
           ORDER BY $RELEASE_CLOCK LIMIT ?""",
        cutoff, now, limit,
    ) { it.uuid("id") }

/**
 * Claims a due PAID order (TRANSFER) for its automatic release. The seller's row is locked (shared) first, so a
 * concurrent suspension either lands before (and the order isn't released) or waits until the claim is taken. The
 * conditions are re-checked by the claim itself, so a dispute, withdrawal or refund that got there first wins.
 */
internal fun Connection.claimForAutoRelease(orderId: UUID, cutoff: Instant, now: Instant): OrderRow? {
    val sellerId = queryOne("SELECT seller_id FROM orders WHERE id = ?", orderId) { it.uuid("seller_id") } ?: return null
    if (isUserSuspended(sellerId, now, lock = true)) return null
    return queryOne(
        """UPDATE orders o SET pending_action = 'TRANSFER'
           WHERE o.id = ? AND o.status = 'PAID' AND $RELEASE_CLOCK <= ?
             AND (o.pending_action IS NULL OR o.pending_action = 'TRANSFER')
           RETURNING $ORDER_COLUMNS""",
        orderId, cutoff,
    ) { it.toOrderRow() }
}

/** Live orders awaiting payment or handoff (PENDING_PAYMENT, PAID) of sellers suspended at [now]: id to status. */
internal fun Connection.liveOrdersOfSuspendedSellers(now: Instant, limit: Int): List<Pair<UUID, OrderStatus>> =
    query(
        """SELECT o.id, o.status FROM users s JOIN orders o ON o.seller_id = s.id
           WHERE $SELLER_SUSPENDED_AT AND o.status IN ('PENDING_PAYMENT', 'PAID')
           ORDER BY o.created_at LIMIT ?""",
        now, limit,
    ) { it.uuid("id") to OrderStatus.valueOf(it.getString("status")) }

/**
 * Freezes a suspended seller's PAID order for a moderator to decide: PAID → DISPUTED with a SELLER_SUSPENDED
 * dispute opened by the platform. Skipped while money is already moving (a claim is held).
 */
internal fun Connection.disputeForSuspendedSeller(orderId: UUID, now: Instant): Boolean {
    val sellerId = queryOne("SELECT seller_id FROM orders WHERE id = ?", orderId) { it.uuid("seller_id") } ?: return false
    if (!isUserSuspended(sellerId, now, lock = true)) return false
    return openDispute(orderId, openedBy = null, reason = DisputeReason.SELLER_SUSPENDED, details = "")
}
