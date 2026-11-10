package com.campusmarket.server.payments

import java.time.Instant
import java.util.UUID

/**
 * Everything the server needs from the payment processor (Stripe Connect, Express accounts, separate charges
 * and transfers). Two implementations: [StripePaymentGateway] and [FakePaymentGateway].
 *
 * Contract for implementations:
 *  - calls are blocking: run them off the request thread and never while a database transaction is open;
 *  - every failure surfaces as [PaymentGatewayException], flagged [PaymentGatewayException.retryable] when the
 *    outcome is unknown (network, 5xx, rate limit) rather than a definitive rejection;
 *  - money movements are idempotent per order: no matter how often [createTransfer] / [refund] are retried for
 *    one order, at most one transfer and one refund ever happen.
 */
interface PaymentGateway {
    /** Creates an Express connected account for [userId] (idempotent per user). Returns the account id. */
    fun createExpressAccount(userId: UUID, email: String): String

    /** A single-use hosted onboarding URL for [accountId]. */
    fun createOnboardingLink(accountId: String, refreshUrl: String, returnUrl: String): String

    /** Current account flags, or null when the processor has no record of [accountId] (caller keeps its local state). */
    fun retrieveAccount(accountId: String): PayoutAccountState?

    fun createCheckoutSession(request: CheckoutRequest): CheckoutSession

    fun retrieveCheckoutSession(sessionId: String): CheckoutSessionState

    /**
     * Expires an open session so it can no longer be paid. Never fails merely because the session already
     * completed or expired: the session's final state is returned instead (status is never OPEN).
     */
    fun expireCheckoutSession(sessionId: String, idempotencyKey: String): CheckoutSessionState

    /** Transfers the seller's share of the order's charge to their connected account. Returns the transfer id. */
    fun createTransfer(request: TransferRequest): String

    /** Fully refunds the order's payment. Returns the refund id. */
    fun refund(request: RefundRequest): String
}

data class PayoutAccountState(val detailsSubmitted: Boolean, val payoutsEnabled: Boolean)

data class CheckoutRequest(
    val orderId: UUID,
    val listingId: UUID,
    val buyerId: UUID,
    val amountCents: Int,
    val currency: String,
    val title: String,
    val buyerEmail: String?,
    val successUrl: String,
    val cancelUrl: String,
    val expiresAt: Instant,
    val idempotencyKey: String,
)

data class CheckoutSession(val id: String, val url: String)

enum class CheckoutStatus { OPEN, COMPLETE, EXPIRED }

data class CheckoutSessionState(
    val id: String,
    val status: CheckoutStatus,
    /** true once the money has been collected (status COMPLETE with payment_status paid). */
    val paid: Boolean,
    val paymentIntentId: String?,
)

data class TransferRequest(
    val orderId: UUID,
    val amountCents: Int,
    val currency: String,
    val destinationAccountId: String,
    /** The order's payment; its charge is used as `source_transaction`. */
    val paymentIntentId: String,
    val idempotencyKey: String,
)

data class RefundRequest(val orderId: UUID, val paymentIntentId: String, val idempotencyKey: String)

class PaymentGatewayException(message: String, val retryable: Boolean, cause: Throwable? = null) : RuntimeException(message, cause)

/** Idempotency keys for every mutating processor call, derived from the order (or user) they act on. */
object IdempotencyKeys {
    fun account(userId: UUID) = "cm-user-$userId-account"
    fun checkout(orderId: UUID) = "cm-order-$orderId-checkout"
    fun expire(orderId: UUID) = "cm-order-$orderId-expire"
    fun transfer(orderId: UUID, attempt: Int) = "cm-order-$orderId-transfer-$attempt"
    fun refund(orderId: UUID, attempt: Int) = "cm-order-$orderId-refund-$attempt"
}
