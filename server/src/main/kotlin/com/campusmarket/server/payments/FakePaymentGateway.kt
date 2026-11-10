package com.campusmarket.server.payments

import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Deterministic, network-free [PaymentGateway] used in development and tests (the default when no Stripe key
 * is configured). Ids are derived from the order/user they belong to; sessions, accounts, transfers and refunds
 * live in memory. The database stays the source of truth: anything the fake doesn't know (e.g. after a restart)
 * is treated as "untouched" (unknown sessions are open, unknown accounts report no state).
 *
 * Its hosted pages are the dev routes under `/api/v1/dev/fake-stripe/` (see FakeStripePages.kt), which drive the
 * same state transitions as the real Stripe webhooks.
 */
open class FakePaymentGateway(publicBaseUrl: String, private val clock: Clock = Clock.systemUTC()) : PaymentGateway {

    private val urls = PaymentUrls(publicBaseUrl, appDeepLinkBase = "")

    data class FakeSession(
        val id: String,
        val orderId: UUID,
        val status: CheckoutStatus,
        val paymentIntentId: String?,
        val successUrl: String?,
        val cancelUrl: String?,
        val expiresAt: Instant?,
    )

    private val accounts = ConcurrentHashMap<String, PayoutAccountState>()
    private val accountReturnUrls = ConcurrentHashMap<String, String>()
    private val sessions = ConcurrentHashMap<String, FakeSession>()
    private val transfers = ConcurrentHashMap<UUID, String>()
    private val refunds = ConcurrentHashMap<UUID, String>()

    override fun createExpressAccount(userId: UUID, email: String): String = accountId(userId)

    override fun createOnboardingLink(accountId: String, refreshUrl: String, returnUrl: String): String {
        accountReturnUrls[accountId] = returnUrl
        return urls.fakeOnboarding(accountId)
    }

    override fun retrieveAccount(accountId: String): PayoutAccountState? = accounts[accountId]

    override fun createCheckoutSession(request: CheckoutRequest): CheckoutSession {
        val id = sessionId(request.orderId)
        sessions.putIfAbsent(
            id,
            FakeSession(id, request.orderId, CheckoutStatus.OPEN, null, request.successUrl, request.cancelUrl, request.expiresAt),
        )
        return CheckoutSession(id, urls.fakeCheckout(request.orderId))
    }

    override fun retrieveCheckoutSession(sessionId: String): CheckoutSessionState = session(sessionId).toState()

    override fun expireCheckoutSession(sessionId: String, idempotencyKey: String): CheckoutSessionState =
        sessions.compute(sessionId) { _, s ->
            val current = expireIfDue(s ?: unknownSession(sessionId))
            if (current.status == CheckoutStatus.OPEN) current.copy(status = CheckoutStatus.EXPIRED) else current
        }!!.toState()

    override fun createTransfer(request: TransferRequest): String =
        transfers.computeIfAbsent(request.orderId) { "tr_fake_${hex(it)}" }

    override fun refund(request: RefundRequest): String =
        refunds.computeIfAbsent(request.orderId) { "re_fake_${hex(it)}" }

    // ---------- operations of the fake hosted pages ----------

    /** "Complete onboarding": details submitted, payouts enabled. */
    fun completeOnboarding(accountId: String): PayoutAccountState =
        PayoutAccountState(detailsSubmitted = true, payoutsEnabled = true).also { accounts[accountId] = it }

    fun onboardingReturnUrl(accountId: String): String? = accountReturnUrls[accountId]

    /** "Pay": completes an open session (an expired one stays expired). Returns the session's resulting state. */
    fun pay(sessionId: String): CheckoutSessionState =
        sessions.compute(sessionId) { _, s ->
            val current = expireIfDue(s ?: unknownSession(sessionId))
            if (current.status == CheckoutStatus.OPEN) {
                current.copy(status = CheckoutStatus.COMPLETE, paymentIntentId = "pi_fake_${hex(current.orderId)}")
            } else current
        }!!.toState()

    fun session(sessionId: String): FakeSession = expireIfDue(sessions[sessionId] ?: unknownSession(sessionId))

    /** Test hooks: inspect what the fake has done. */
    fun transferFor(orderId: UUID): String? = transfers[orderId]
    fun refundFor(orderId: UUID): String? = refunds[orderId]

    private fun expireIfDue(s: FakeSession): FakeSession =
        if (s.status == CheckoutStatus.OPEN && s.expiresAt != null && clock.instant().isAfter(s.expiresAt)) s.copy(status = CheckoutStatus.EXPIRED) else s

    /** A session this process didn't create (e.g. before a restart): open, unless its id isn't one of ours. */
    private fun unknownSession(sessionId: String): FakeSession {
        val orderId = orderIdOf(sessionId) ?: throw PaymentGatewayException("No such checkout session: $sessionId", retryable = false)
        return FakeSession(sessionId, orderId, CheckoutStatus.OPEN, null, null, null, null)
    }

    private fun FakeSession.toState() = CheckoutSessionState(id, status, paid = status == CheckoutStatus.COMPLETE, paymentIntentId)

    companion object {
        private const val SESSION_PREFIX = "cs_fake_"

        fun accountId(userId: UUID) = "acct_fake_${hex(userId)}"
        fun sessionId(orderId: UUID) = "$SESSION_PREFIX${hex(orderId)}"

        fun orderIdOf(sessionId: String): UUID? {
            if (!sessionId.startsWith(SESSION_PREFIX)) return null
            val h = sessionId.removePrefix(SESSION_PREFIX)
            if (h.length != 32) return null
            return runCatching {
                UUID.fromString("${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}")
            }.getOrNull()
        }

        private fun hex(id: UUID) = id.toString().replace("-", "")
    }
}
