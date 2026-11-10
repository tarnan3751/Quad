package com.campusmarket.server.payments

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises [StripePaymentGateway] (real stripe-java) against a local mock of the Stripe API. */
class StripePaymentGatewayTest {

    private class StripeMock : AutoCloseable {
        data class Req(val method: String, val path: String, val params: Map<String, String>, val idempotencyKey: String?, val auth: String?)

        val requests = CopyOnWriteArrayList<Req>()
        private val routes = ConcurrentHashMap<String, (Req) -> Pair<Int, String>>()
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val query = ex.requestURI.rawQuery.orEmpty()
                val req = Req(
                    ex.requestMethod, ex.requestURI.path, decodeForm(if (ex.requestMethod == "GET") query else body),
                    ex.requestHeaders.getFirst("Idempotency-Key"), ex.requestHeaders.getFirst("Authorization"),
                )
                requests += req
                val (status, json) = routes["${req.method} ${req.path}"]?.invoke(req)
                    ?: (404 to """{"error":{"type":"invalid_request_error","code":"resource_missing","message":"No such route ${req.path}"}}""")
                val bytes = json.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.responseHeaders.add("Request-Id", "req_mock")
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }

        val baseUrl = "http://127.0.0.1:${server.address.port}"

        fun on(method: String, path: String, status: Int = 200, json: (Req) -> String) {
            routes["$method $path"] = { status to json(it) }
        }

        fun only(method: String, path: String): Req = requests.single { it.method == method && it.path == path }

        override fun close() = server.stop(0)

        private fun decodeForm(s: String): Map<String, String> = s.split('&').filter { it.isNotEmpty() }.associate {
            val (k, v) = (it.split('=', limit = 2) + "").take(2)
            URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
        }
    }

    private val mock = StripeMock()
    private val gateway = StripePaymentGateway("sk_test_123", apiBase = mock.baseUrl, maxNetworkRetries = 0)
    private val orderId: UUID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")

    @AfterTest fun stop() = mock.close()

    private fun error(type: String, code: String?, message: String = "nope") =
        """{"error":{"type":"$type",${code?.let { "\"code\":\"$it\"," } ?: ""}"message":"$message"}}"""

    @Test
    fun createsCheckoutSessionWithTransferGroupAndIdempotencyKey() {
        mock.on("POST", "/v1/checkout/sessions") {
            """{"id":"cs_test_1","object":"checkout.session","url":"https://checkout.stripe.com/c/pay/cs_test_1","status":"open","payment_status":"unpaid"}"""
        }
        val expiresAt = Instant.ofEpochSecond(1_900_000_000)
        val buyerId = UUID.randomUUID()
        val listingId = UUID.randomUUID()
        val session = gateway.createCheckoutSession(
            CheckoutRequest(
                orderId, listingId, buyerId, 2500, "usd", "Desk lamp", "bea@state.edu",
                "https://api.example/success", "https://api.example/cancel", expiresAt, "cm-order-$orderId-checkout",
            ),
        )
        assertEquals(CheckoutSession("cs_test_1", "https://checkout.stripe.com/c/pay/cs_test_1"), session)

        val req = mock.only("POST", "/v1/checkout/sessions")
        assertEquals("Bearer sk_test_123", req.auth)
        assertEquals("cm-order-$orderId-checkout", req.idempotencyKey)
        val p = req.params
        assertEquals("payment", p["mode"])
        assertEquals("card", p["allowed_payment_method_types[0]"])
        assertEquals("1", p["line_items[0][quantity]"])
        assertEquals("2500", p["line_items[0][price_data][unit_amount]"])
        assertEquals("usd", p["line_items[0][price_data][currency]"])
        assertEquals("Desk lamp", p["line_items[0][price_data][product_data][name]"])
        assertEquals(orderId.toString(), p["payment_intent_data[transfer_group]"])
        assertEquals(orderId.toString(), p["payment_intent_data[metadata][order_id]"])
        assertEquals(orderId.toString(), p["client_reference_id"])
        assertEquals(orderId.toString(), p["metadata[order_id]"])
        assertEquals(listingId.toString(), p["metadata[listing_id]"])
        assertEquals(buyerId.toString(), p["metadata[buyer_id]"])
        assertEquals("https://api.example/success", p["success_url"])
        assertEquals("https://api.example/cancel", p["cancel_url"])
        assertEquals("1900000000", p["expires_at"])
        assertEquals("bea@state.edu", p["customer_email"])
    }

    @Test
    fun expireReportsFinalStateOfAlreadyCompletedSession() {
        mock.on("POST", "/v1/checkout/sessions/cs_1/expire", status = 400) { error("invalid_request_error", "checkout_session_not_open") }
        mock.on("GET", "/v1/checkout/sessions/cs_1") {
            """{"id":"cs_1","object":"checkout.session","status":"complete","payment_status":"paid","payment_intent":"pi_1"}"""
        }
        assertEquals(CheckoutSessionState("cs_1", CheckoutStatus.COMPLETE, paid = true, paymentIntentId = "pi_1"), gateway.expireCheckoutSession("cs_1", "k-expire"))
        assertEquals("k-expire", mock.only("POST", "/v1/checkout/sessions/cs_1/expire").idempotencyKey)
    }

    @Test
    fun expireOpenSession() {
        mock.on("POST", "/v1/checkout/sessions/cs_2/expire") {
            """{"id":"cs_2","object":"checkout.session","status":"expired","payment_status":"unpaid","payment_intent":null}"""
        }
        assertEquals(CheckoutSessionState("cs_2", CheckoutStatus.EXPIRED, paid = false, paymentIntentId = null), gateway.expireCheckoutSession("cs_2", "k"))
    }

    @Test
    fun transferUsesTheChargeAsSourceTransaction() {
        mock.on("GET", "/v1/transfers") { """{"object":"list","data":[],"has_more":false,"url":"/v1/transfers"}""" }
        mock.on("GET", "/v1/payment_intents/pi_1") { """{"id":"pi_1","object":"payment_intent","latest_charge":"ch_1"}""" }
        mock.on("POST", "/v1/transfers") { """{"id":"tr_1","object":"transfer","destination":"acct_s","transfer_group":"$orderId"}""" }

        val id = gateway.createTransfer(TransferRequest(orderId, 2375, "usd", "acct_s", "pi_1", "cm-order-$orderId-transfer-0"))
        assertEquals("tr_1", id)

        assertEquals(orderId.toString(), mock.only("GET", "/v1/transfers").params["transfer_group"])
        val create = mock.only("POST", "/v1/transfers")
        assertEquals("cm-order-$orderId-transfer-0", create.idempotencyKey)
        assertEquals("2375", create.params["amount"])
        assertEquals("usd", create.params["currency"])
        assertEquals("acct_s", create.params["destination"])
        assertEquals("ch_1", create.params["source_transaction"])
        assertEquals(orderId.toString(), create.params["transfer_group"])
    }

    @Test
    fun transferIsNeverRepeatedForAnOrder() {
        mock.on("GET", "/v1/transfers") {
            """{"object":"list","data":[{"id":"tr_old","object":"transfer","destination":"acct_s","reversed":false}],"has_more":false,"url":"/v1/transfers"}"""
        }
        assertEquals("tr_old", gateway.createTransfer(TransferRequest(orderId, 2375, "usd", "acct_s", "pi_1", "k-new")))
        assertTrue(mock.requests.none { it.method == "POST" })
    }

    @Test
    fun refundOfAlreadyRefundedChargeReturnsTheExistingRefund() {
        mock.on("POST", "/v1/refunds", status = 400) { error("invalid_request_error", "charge_already_refunded") }
        mock.on("GET", "/v1/refunds") {
            """{"object":"list","data":[{"id":"re_1","object":"refund","status":"succeeded"}],"has_more":false,"url":"/v1/refunds"}"""
        }
        assertEquals("re_1", gateway.refund(RefundRequest(orderId, "pi_1", "cm-order-$orderId-refund-0")))
        val create = mock.only("POST", "/v1/refunds")
        assertEquals("pi_1", create.params["payment_intent"])
        assertEquals("requested_by_customer", create.params["reason"])
        assertEquals("cm-order-$orderId-refund-0", create.idempotencyKey)
        assertEquals("pi_1", mock.only("GET", "/v1/refunds").params["payment_intent"])
    }

    @Test
    fun createsExpressAccountAndOnboardingLink() {
        val userId = UUID.randomUUID()
        mock.on("POST", "/v1/accounts") { """{"id":"acct_new","object":"account","type":"express"}""" }
        mock.on("POST", "/v1/account_links") {
            """{"object":"account_link","url":"https://connect.stripe.com/setup/e/acct_new/xyz","created":1,"expires_at":2}"""
        }
        assertEquals("acct_new", gateway.createExpressAccount(userId, "sam@state.edu"))
        val acct = mock.only("POST", "/v1/accounts")
        assertEquals("express", acct.params["type"])
        assertEquals("US", acct.params["country"])
        assertEquals("sam@state.edu", acct.params["email"])
        assertEquals("true", acct.params["capabilities[transfers][requested]"])
        assertEquals(userId.toString(), acct.params["metadata[user_id]"])
        assertEquals("cm-user-$userId-account", acct.idempotencyKey)

        assertEquals("https://connect.stripe.com/setup/e/acct_new/xyz", gateway.createOnboardingLink("acct_new", "https://r/refresh", "https://r/return"))
        val link = mock.only("POST", "/v1/account_links")
        assertEquals("acct_new", link.params["account"])
        assertEquals("account_onboarding", link.params["type"])
        assertEquals("https://r/refresh", link.params["refresh_url"])
        assertEquals("https://r/return", link.params["return_url"])
    }

    @Test
    fun retrievesAccountFlags() {
        mock.on("GET", "/v1/accounts/acct_1") { """{"id":"acct_1","object":"account","details_submitted":true,"payouts_enabled":false}""" }
        assertEquals(PayoutAccountState(detailsSubmitted = true, payoutsEnabled = false), gateway.retrieveAccount("acct_1"))
        assertNull(gateway.retrieveAccount("acct_missing")) // 404 from the mock
    }

    @Test
    fun classifiesFailures() {
        mock.on("POST", "/v1/refunds", status = 500) { error("api_error", null, "boom") }
        val serverError = assertFailsWith<PaymentGatewayException> { gateway.refund(RefundRequest(orderId, "pi_1", "k")) }
        assertTrue(serverError.retryable)

        mock.on("POST", "/v1/refunds", status = 400) { error("invalid_request_error", "resource_missing") }
        val rejected = assertFailsWith<PaymentGatewayException> { gateway.refund(RefundRequest(orderId, "pi_1", "k")) }
        assertFalse(rejected.retryable)

        val down = StripePaymentGateway("sk_test_123", apiBase = "http://127.0.0.1:9", maxNetworkRetries = 0)
        assertTrue(assertFailsWith<PaymentGatewayException> { down.retrieveCheckoutSession("cs_1") }.retryable)
    }
}
