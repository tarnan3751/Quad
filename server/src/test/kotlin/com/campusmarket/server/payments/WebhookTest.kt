package com.campusmarket.server.payments

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.OrderStatus
import com.campusmarket.server.TestEnv
import com.campusmarket.server.queryOne
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WebhookTest {
    private val secret = "whsec_test_secret"
    private val config = TestEnv.config.copy(stripeWebhookSecret = secret)

    private suspend fun PaymentsScope.deliver(payload: String, signature: String? = StripePayloads.signature(payload, secret)): HttpResponse =
        client.post(ApiRoutes.STRIPE_WEBHOOK) {
            contentType(ContentType.Application.Json)
            if (signature != null) header("Stripe-Signature", signature)
            setBody(payload)
        }

    private fun PaymentsScope.eventCount(): Int = env.db.txBlocking { c -> c.queryOne("SELECT count(*) FROM stripe_events") { it.getInt(1) }!! }

    private fun PaymentsScope.payoutsEnabled(user: TestEnv.TestUser): Boolean =
        env.db.txBlocking { c -> c.queryOne("SELECT payouts_enabled FROM users WHERE id = ?", user.id) { it.getBoolean(1) }!! }

    // ---------- over HTTP, with real signatures ----------

    @Test
    fun signedCheckoutCompletedMarksOrderPaidExactlyOnce() = paymentsTest(config = config) {
        val order = buyOk(buyer(), env.listing(seller()))
        val sessionId = orderRow(order.id).sessionId!!
        val payload = StripePayloads.event("evt_1", "checkout.session.completed", StripePayloads.session(sessionId, order.id, paymentIntent = "pi_123"))

        val res = deliver(payload)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val row = orderRow(order.id)
        assertEquals(OrderStatus.PAID, row.status)
        assertEquals("pi_123", row.paymentIntentId)

        // redelivery: acknowledged, nothing changes
        assertEquals(HttpStatusCode.OK, deliver(payload).status)
        assertEquals(1, eventCount())
        assertEquals("pi_123", orderRow(order.id).paymentIntentId)
    }

    @Test
    fun badOrMissingSignatureIsRejected() = paymentsTest(config = config) {
        val order = buyOk(buyer(), env.listing(seller()))
        val payload = StripePayloads.event("evt_bad", "checkout.session.completed", StripePayloads.session(orderRow(order.id).sessionId!!, order.id))

        val forged = deliver(payload, StripePayloads.signature(payload, "whsec_someone_else"))
        assertEquals(HttpStatusCode.BadRequest, forged.status)
        assertEquals(ErrorCodes.VALIDATION, forged.body<ErrorResponse>().code)
        assertEquals(HttpStatusCode.BadRequest, deliver(payload, signature = null).status)
        val stale = deliver(payload, StripePayloads.signature(payload, secret, timestamp = Instant.now().epochSecond - 3600))
        assertEquals(HttpStatusCode.BadRequest, stale.status)
        val tampered = deliver(payload.replace("pi_test_1", "pi_evil"), StripePayloads.signature(payload, secret))
        assertEquals(HttpStatusCode.BadRequest, tampered.status)

        assertEquals(OrderStatus.PENDING_PAYMENT, orderStatus(order.id))
        assertEquals(0, eventCount())
    }

    @Test
    fun oversizedPayloadIsRejected() = paymentsTest(config = config) {
        val huge = StripePayloads.event("evt_big", "customer.created", """{"id":"cus_1","object":"customer","pad":"${"x".repeat(2 shl 20)}"}""")
        // the application-wide request size guard answers before the webhook's own check
        assertEquals(HttpStatusCode.PayloadTooLarge, deliver(huge).status)
        assertEquals(0, eventCount())
    }

    @Test
    fun webhooksWithoutConfiguredSecretAreRefused() = paymentsTest {
        val payload = StripePayloads.event("evt_x", "account.updated", StripePayloads.account("acct_x", true, true))
        assertEquals(HttpStatusCode.ServiceUnavailable, deliver(payload).status)
    }

    @Test
    fun acceptsEitherOfSeveralSigningSecrets() = paymentsTest(config = TestEnv.config.copy(stripeWebhookSecret = "whsec_platform, whsec_connect")) {
        val seller = env.user("connected")
        sql("UPDATE users SET stripe_account_id = 'acct_conn' WHERE id = ?", seller.id)
        val payload = StripePayloads.event("evt_acct", "account.updated", StripePayloads.account("acct_conn", true, true), account = "acct_conn")
        val res = deliver(payload, StripePayloads.signature(payload, "whsec_connect"))
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertTrue(payoutsEnabled(seller))
    }

    @Test
    fun unknownAndUnhandledEventsAreAcknowledged() = paymentsTest(config = config) {
        assertEquals(HttpStatusCode.OK, deliver(StripePayloads.event("evt_u1", "customer.created", """{"id":"cus_1","object":"customer"}""")).status)
        val unknownSession = StripePayloads.event("evt_u2", "checkout.session.completed", StripePayloads.session("cs_unknown", UUID.randomUUID().toString()))
        assertEquals(HttpStatusCode.OK, deliver(unknownSession).status)
    }

    // ---------- the parsing seam (no signature) ----------

    @Test
    fun parsesStripePayloads() {
        val orderId = UUID.randomUUID().toString()
        val completed = StripeEvents.parseUnverified(
            StripePayloads.event("evt_c", "checkout.session.completed", StripePayloads.session("cs_1", orderId, paymentIntent = "pi_9"), created = 1_700_000_000),
        )
        assertEquals(
            PaymentEvent.CheckoutCompleted("evt_c", "checkout.session.completed", Instant.ofEpochSecond(1_700_000_000), "cs_1", UUID.fromString(orderId), "pi_9", paid = true),
            completed,
        )
        val unpaid = StripeEvents.parseUnverified(
            StripePayloads.event("evt_c2", "checkout.session.completed", StripePayloads.session("cs_1", orderId, paymentStatus = "unpaid")),
        )
        assertEquals(false, (unpaid as PaymentEvent.CheckoutCompleted).paid)

        // expanded payment_intent object
        val expanded = StripeEvents.parseUnverified(
            StripePayloads.event(
                "evt_c3", "checkout.session.async_payment_succeeded",
                """{"id":"cs_2","object":"checkout.session","status":"complete","payment_status":"paid","metadata":{"order_id":"$orderId"},"payment_intent":{"id":"pi_x","object":"payment_intent"}}""",
            ),
        )
        assertIs<PaymentEvent.CheckoutCompleted>(expanded)
        assertEquals("pi_x", expanded.paymentIntentId)
        assertEquals(UUID.fromString(orderId), expanded.orderId)

        val expired = StripeEvents.parseUnverified(StripePayloads.event("evt_e", "checkout.session.expired", StripePayloads.session("cs_3", orderId, status = "expired", paymentStatus = "unpaid", paymentIntent = null)))
        assertEquals(PaymentEvent.CheckoutFailed("evt_e", "checkout.session.expired", expired.createdAt, "cs_3", UUID.fromString(orderId)), expired)

        val account = StripeEvents.parseUnverified(StripePayloads.event("evt_a", "account.updated", StripePayloads.account("acct_1", true, false)))
        assertEquals(PaymentEvent.AccountUpdated("evt_a", "account.updated", account.createdAt, "acct_1", detailsSubmitted = true, payoutsEnabled = false), account)

        val refund = StripeEvents.parseUnverified(StripePayloads.event("evt_r", "charge.refunded", StripePayloads.charge("ch_1", "pi_1", orderId)))
        assertEquals(PaymentEvent.ChargeRefunded("evt_r", "charge.refunded", refund.createdAt, "ch_1", "pi_1", UUID.fromString(orderId), fullyRefunded = true), refund)
        val partial = StripeEvents.parseUnverified(StripePayloads.event("evt_r2", "charge.refunded", StripePayloads.charge("ch_1", "pi_1", orderId, refunded = 100)))
        assertEquals(false, (partial as PaymentEvent.ChargeRefunded).fullyRefunded)

        assertIs<PaymentEvent.Unhandled>(StripeEvents.parseUnverified(StripePayloads.event("evt_o", "payout.paid", """{"id":"po_1","object":"payout"}""")))
    }

    // ---------- the handler, fed parsed events ----------

    @Test
    fun checkoutExpiredCancelsAndReleasesTheListing() = paymentsTest {
        val listing = env.listing(seller())
        val order = buyOk(buyer(), listing)
        val sessionId = orderRow(order.id).sessionId!!
        module.webhooks.handle(StripeEvents.parseUnverified(StripePayloads.event("evt_exp", "checkout.session.expired", StripePayloads.session(sessionId, order.id, status = "expired", paymentStatus = "unpaid", paymentIntent = null))))
        assertEquals(OrderStatus.CANCELLED, orderStatus(order.id))
        assertEquals(ListingStatus.ACTIVE, listingStatus(listing))
    }

    @Test
    fun expiredAfterPaidIsIgnored() = paymentsTest {
        val order = paidOrder()
        val sessionId = orderRow(order.id).sessionId!!
        module.webhooks.handle(PaymentEvent.CheckoutFailed("evt_late_exp", "checkout.session.expired", Instant.now(), sessionId, null))
        assertEquals(OrderStatus.PAID, orderStatus(order.id))
        assertEquals(ListingStatus.RESERVED, listingStatus(order))
    }

    @Test
    fun unpaidCompletionWaitsForAsyncSuccess() = paymentsTest {
        val order = buyOk(buyer(), env.listing(seller()))
        val sessionId = orderRow(order.id).sessionId!!
        module.webhooks.handle(PaymentEvent.CheckoutCompleted("evt_1", "checkout.session.completed", Instant.now(), sessionId, null, "pi_1", paid = false))
        assertEquals(OrderStatus.PENDING_PAYMENT, orderStatus(order.id))
        module.webhooks.handle(PaymentEvent.CheckoutCompleted("evt_2", "checkout.session.async_payment_succeeded", Instant.now(), sessionId, null, "pi_1", paid = true))
        assertEquals(OrderStatus.PAID, orderStatus(order.id))
    }

    @Test
    fun findsOrderByClientReferenceWhenSessionIdWasNeverStored() = paymentsTest {
        val order = buyOk(buyer(), env.listing(seller()))
        sql("UPDATE orders SET stripe_checkout_session_id = NULL WHERE id = ?", UUID.fromString(order.id))
        module.webhooks.handle(PaymentEvent.CheckoutCompleted("evt_ref", "checkout.session.completed", Instant.now(), "cs_real", UUID.fromString(order.id), "pi_1", paid = true))
        val row = orderRow(order.id)
        assertEquals(OrderStatus.PAID, row.status)
        assertEquals("cs_real", row.sessionId)
    }

    @Test
    fun refundArrivingBeforeCompletionIsToleratedOutOfOrder() = paymentsTest {
        val listing = env.listing(seller())
        val order = buyOk(buyer(), listing)
        val sessionId = orderRow(order.id).sessionId!!
        module.webhooks.handle(StripeEvents.parseUnverified(StripePayloads.event("evt_ref", "charge.refunded", StripePayloads.charge("ch_1", "pi_7", order.id))))
        assertEquals(OrderStatus.REFUNDED, orderStatus(order.id))
        assertEquals(ListingStatus.ACTIVE, listingStatus(listing))

        module.webhooks.handle(PaymentEvent.CheckoutCompleted("evt_done", "checkout.session.completed", Instant.now(), sessionId, null, "pi_7", paid = true))
        assertEquals(OrderStatus.REFUNDED, orderStatus(order.id))
        assertEquals(ListingStatus.ACTIVE, listingStatus(listing))
    }

    @Test
    fun dashboardRefundOfPaidOrderMarksItRefunded() = paymentsTest {
        val order = paidOrder()
        val pi = orderRow(order.id).paymentIntentId!!
        module.webhooks.handle(PaymentEvent.ChargeRefunded("evt_r", "charge.refunded", Instant.now(), "ch_1", pi, null, fullyRefunded = true))
        assertEquals(OrderStatus.REFUNDED, orderStatus(order.id))
        assertEquals(ListingStatus.ACTIVE, listingStatus(order))
    }

    @Test
    fun partialRefundLeavesOrderAlone() = paymentsTest {
        val order = paidOrder()
        val pi = orderRow(order.id).paymentIntentId!!
        module.webhooks.handle(PaymentEvent.ChargeRefunded("evt_r", "charge.refunded", Instant.now(), "ch_1", pi, null, fullyRefunded = false))
        assertEquals(OrderStatus.PAID, orderStatus(order.id))
    }

    @Test
    fun paymentForCancelledOrderIsRefunded() = paymentsTest {
        val listing = env.listing(seller())
        val order = buyOk(buyer(), listing)
        val sessionId = orderRow(order.id).sessionId!!
        sql("UPDATE orders SET status = 'CANCELLED', cancelled_at = now() WHERE id = ?", UUID.fromString(order.id))
        sql("UPDATE listings SET status = 'ACTIVE' WHERE id = ?", listing)
        val next = buyOk(buyer(), listing) // someone else reserved it meanwhile

        module.webhooks.handle(PaymentEvent.CheckoutCompleted("evt_stray", "checkout.session.completed", Instant.now(), sessionId, null, "pi_stray", paid = true))
        val row = orderRow(order.id)
        assertEquals(OrderStatus.REFUNDED, row.status)
        assertEquals("pi_stray", row.paymentIntentId)
        assertEquals("pi_stray", gateway.args<RefundRequest>("refund").single().paymentIntentId)
        // the other buyer's reservation is untouched
        assertEquals(OrderStatus.PENDING_PAYMENT, orderStatus(next.id))
        assertEquals(ListingStatus.RESERVED, listingStatus(listing))
    }

    @Test
    fun failedStrayRefundIsRetriedOnRedelivery() = paymentsTest {
        val order = buyOk(buyer(), env.listing(seller()))
        val sessionId = orderRow(order.id).sessionId!!
        sql("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", UUID.fromString(order.id))
        val event = PaymentEvent.CheckoutCompleted("evt_stray", "checkout.session.completed", Instant.now(), sessionId, null, "pi_stray", paid = true)

        gateway.failNext("refund")
        assertIs<PaymentGatewayException>(runCatching { module.webhooks.handle(event) }.exceptionOrNull())
        assertEquals(OrderStatus.CANCELLED, orderStatus(order.id))
        assertEquals(0, eventCount()) // forgotten, so Stripe's retry is processed

        assertEquals(WebhookService.Result.PROCESSED, module.webhooks.handle(event))
        assertEquals(OrderStatus.REFUNDED, orderStatus(order.id))
        assertEquals(WebhookService.Result.DUPLICATE, module.webhooks.handle(event))
        assertEquals(2, gateway.calls("refund").size)
    }

    @Test
    fun accountUpdatesApplyInOrderOfCreationNotDelivery() = paymentsTest {
        val seller = env.user("onboarding")
        sql("UPDATE users SET stripe_account_id = 'acct_live' WHERE id = ?", seller.id)
        val t0 = Instant.now().minusSeconds(60)

        module.webhooks.handle(PaymentEvent.AccountUpdated("evt_new", "account.updated", t0.plusSeconds(10), "acct_live", true, true))
        assertTrue(payoutsEnabled(seller))
        // an older snapshot delivered late doesn't roll it back
        module.webhooks.handle(PaymentEvent.AccountUpdated("evt_old", "account.updated", t0, "acct_live", true, false))
        assertTrue(payoutsEnabled(seller))
        // a newer one does
        module.webhooks.handle(PaymentEvent.AccountUpdated("evt_newer", "account.updated", t0.plusSeconds(20), "acct_live", true, false))
        assertEquals(false, payoutsEnabled(seller))
    }
}
