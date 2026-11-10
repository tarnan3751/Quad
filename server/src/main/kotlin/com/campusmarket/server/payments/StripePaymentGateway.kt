package com.campusmarket.server.payments

import com.stripe.StripeClient
import com.stripe.exception.ApiConnectionException
import com.stripe.exception.InvalidRequestException
import com.stripe.exception.RateLimitException
import com.stripe.exception.StripeException
import com.stripe.model.checkout.Session
import com.stripe.net.RequestOptions
import com.stripe.param.AccountCreateParams
import com.stripe.param.AccountLinkCreateParams
import com.stripe.param.RefundCreateParams
import com.stripe.param.RefundListParams
import com.stripe.param.TransferCreateParams
import com.stripe.param.TransferListParams
import com.stripe.param.checkout.SessionCreateParams
import com.stripe.param.checkout.SessionExpireParams
import java.util.UUID
import com.stripe.exception.ApiException as StripeApiException

/**
 * [PaymentGateway] backed by stripe-java. Uses its own [StripeClient] (never the global `Stripe.apiKey`) and
 * sends an idempotency key derived from the order on every mutating call.
 *
 * @param apiBase override for tests (a local mock of the Stripe API); null = api.stripe.com
 * @param country country of newly created Express accounts (campuses are US `.edu` domains)
 */
class StripePaymentGateway(
    secretKey: String,
    apiBase: String? = null,
    maxNetworkRetries: Int = 2,
    private val country: String = "US",
) : PaymentGateway {

    private val client: StripeClient = StripeClient.builder()
        .setApiKey(secretKey)
        .setMaxNetworkRetries(maxNetworkRetries)
        .setConnectTimeout(10_000)
        .setReadTimeout(30_000)
        .apply { if (apiBase != null) setApiBase(apiBase) }
        .build()

    private val v1 get() = client.v1()

    override fun createExpressAccount(userId: UUID, email: String): String = stripe("create connected account") {
        val params = AccountCreateParams.builder()
            .setType(AccountCreateParams.Type.EXPRESS)
            .setCountry(country)
            .setEmail(email)
            .setCapabilities(
                AccountCreateParams.Capabilities.builder()
                    .setTransfers(AccountCreateParams.Capabilities.Transfers.builder().setRequested(true).build())
                    .build(),
            )
            .putMetadata("user_id", userId.toString())
            .build()
        v1.accounts().create(params, options(IdempotencyKeys.account(userId))).id
    }

    override fun createOnboardingLink(accountId: String, refreshUrl: String, returnUrl: String): String = stripe("create account link") {
        val params = AccountLinkCreateParams.builder()
            .setAccount(accountId)
            .setRefreshUrl(refreshUrl)
            .setReturnUrl(returnUrl)
            .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
            .build()
        // account links are single-use and short-lived; a fresh one per request is intended
        v1.accountLinks().create(params, options(null)).url
    }

    override fun retrieveAccount(accountId: String): PayoutAccountState? = try {
        val account = v1.accounts().retrieve(accountId, options(null))
        PayoutAccountState(detailsSubmitted = account.detailsSubmitted == true, payoutsEnabled = account.payoutsEnabled == true)
    } catch (e: InvalidRequestException) {
        if (e.statusCode == 404) null else throw e.toGatewayException("retrieve account")
    } catch (e: StripeException) {
        throw e.toGatewayException("retrieve account")
    }

    override fun createCheckoutSession(request: CheckoutRequest): CheckoutSession = stripe("create checkout session") {
        val orderId = request.orderId.toString()
        val product = SessionCreateParams.LineItem.PriceData.ProductData.builder()
            .setName(request.title.take(250).ifBlank { "Quad item" })
            .build()
        val lineItem = SessionCreateParams.LineItem.builder()
            .setQuantity(1L)
            .setPriceData(
                SessionCreateParams.LineItem.PriceData.builder()
                    .setCurrency(request.currency)
                    .setUnitAmount(request.amountCents.toLong())
                    .setProductData(product)
                    .build(),
            )
            .build()
        val params = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            // cards only (incl. Apple/Google Pay): payment is final when the session completes
            .addAllowedPaymentMethodType(SessionCreateParams.AllowedPaymentMethodType.CARD)
            .addLineItem(lineItem)
            .setPaymentIntentData(
                SessionCreateParams.PaymentIntentData.builder()
                    .setTransferGroup(orderId)
                    .setDescription("Quad order $orderId")
                    .putMetadata("order_id", orderId)
                    .build(),
            )
            .setClientReferenceId(orderId)
            .putMetadata("order_id", orderId)
            .putMetadata("listing_id", request.listingId.toString())
            .putMetadata("buyer_id", request.buyerId.toString())
            .setSuccessUrl(request.successUrl)
            .setCancelUrl(request.cancelUrl)
            .setExpiresAt(request.expiresAt.epochSecond)
            .apply { request.buyerEmail?.let { setCustomerEmail(it) } }
            .build()
        val session = v1.checkout().sessions().create(params, options(request.idempotencyKey))
        CheckoutSession(session.id, session.url ?: throw PaymentGatewayException("Checkout session ${session.id} has no URL", retryable = false))
    }

    override fun retrieveCheckoutSession(sessionId: String): CheckoutSessionState = stripe("retrieve checkout session") {
        v1.checkout().sessions().retrieve(sessionId, options(null)).toState()
    }

    override fun expireCheckoutSession(sessionId: String, idempotencyKey: String): CheckoutSessionState {
        try {
            return v1.checkout().sessions().expire(sessionId, SessionExpireParams.builder().build(), options(idempotencyKey)).toState()
        } catch (e: InvalidRequestException) {
            // Only open sessions can be expired: it already completed or expired. Report what it is now.
            val state = retrieveCheckoutSession(sessionId)
            if (state.status == CheckoutStatus.OPEN) throw e.toGatewayException("expire checkout session")
            return state
        } catch (e: StripeException) {
            throw e.toGatewayException("expire checkout session")
        }
    }

    override fun createTransfer(request: TransferRequest): String = stripe("create transfer") {
        val group = request.orderId.toString()
        // Idempotency keys only live for 24h: also look for a transfer already made for this order before
        // creating one, so a confirm retried at any later time can never pay the seller twice.
        val existing = v1.transfers()
            .list(TransferListParams.builder().setTransferGroup(group).setLimit(10L).build(), options(null))
            .data.firstOrNull { it.destination == request.destinationAccountId && it.reversed != true }
        if (existing != null) return@stripe existing.id

        val charge = v1.paymentIntents().retrieve(request.paymentIntentId, options(null)).latestCharge
            ?: throw PaymentGatewayException("Payment ${request.paymentIntentId} has no charge yet", retryable = true)
        val params = TransferCreateParams.builder()
            .setAmount(request.amountCents.toLong())
            .setCurrency(request.currency)
            .setDestination(request.destinationAccountId)
            .setSourceTransaction(charge)
            .setTransferGroup(group)
            .setDescription("Quad order $group")
            .putMetadata("order_id", group)
            .build()
        v1.transfers().create(params, options(request.idempotencyKey)).id
    }

    override fun refund(request: RefundRequest): String {
        try {
            val params = RefundCreateParams.builder()
                .setPaymentIntent(request.paymentIntentId)
                .setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER)
                .putMetadata("order_id", request.orderId.toString())
                .build()
            return v1.refunds().create(params, options(request.idempotencyKey)).id
        } catch (e: InvalidRequestException) {
            // Refunded already (an earlier attempt whose response was lost, or from the dashboard): report that refund.
            if (e.code == "charge_already_refunded") {
                val existing = stripe("list refunds") {
                    v1.refunds().list(RefundListParams.builder().setPaymentIntent(request.paymentIntentId).setLimit(10L).build(), options(null))
                        .data.firstOrNull { it.status != "failed" && it.status != "canceled" }
                }
                if (existing != null) return existing.id
            }
            throw e.toGatewayException("refund")
        } catch (e: StripeException) {
            throw e.toGatewayException("refund")
        }
    }

    private fun options(idempotencyKey: String?): RequestOptions =
        RequestOptions.builder().apply { if (idempotencyKey != null) setIdempotencyKey(idempotencyKey) }.build()

    private inline fun <T> stripe(what: String, block: () -> T): T = try {
        block()
    } catch (e: StripeException) {
        throw e.toGatewayException(what)
    }

    private fun Session.toState(): CheckoutSessionState {
        val status = when (status) {
            "complete" -> CheckoutStatus.COMPLETE
            "expired" -> CheckoutStatus.EXPIRED
            else -> CheckoutStatus.OPEN
        }
        val paid = status == CheckoutStatus.COMPLETE && (paymentStatus == "paid" || paymentStatus == "no_payment_required")
        return CheckoutSessionState(id, status, paid, paymentIntent)
    }

    companion object {
        /** Unknown outcomes (network, 5xx, rate limiting, concurrent use of a key) are retryable; other 4xx are definitive. */
        fun StripeException.toGatewayException(what: String): PaymentGatewayException {
            val status = statusCode
            val retryable = this is ApiConnectionException || this is RateLimitException || this is StripeApiException ||
                status == null || status == 409 || status == 429 || status >= 500
            return PaymentGatewayException("Stripe: $what failed (${status ?: "no response"}${code?.let { ", $it" } ?: ""}): $message", retryable, this)
        }
    }
}
