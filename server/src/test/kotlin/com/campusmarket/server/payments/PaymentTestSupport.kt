package com.campusmarket.server.payments

import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CreateOrderRequest
import com.campusmarket.core.DisputeReason
import com.campusmarket.core.DisputeResolution
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.OpenDisputeRequest
import com.campusmarket.core.Order
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.ResolveDisputeRequest
import com.campusmarket.core.UserRole
import com.campusmarket.server.ApiTestScope
import com.campusmarket.server.AppConfig
import com.campusmarket.server.AppDeps
import com.campusmarket.server.TestEnv
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import com.campusmarket.server.json
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.time.toJavaDuration

/**
 * The fake gateway plus a call log, injectable failures (before or after the operation took effect — the latter
 * simulates a lost response) and artificial latency.
 */
class RecordingGateway(publicBaseUrl: String = TestEnv.config.publicBaseUrl) : FakePaymentGateway(publicBaseUrl) {
    data class Call(val method: String, val arg: Any?)

    private data class Failure(val retryable: Boolean, val afterPerforming: Boolean)

    val calls = CopyOnWriteArrayList<Call>()
    private val failures = ConcurrentHashMap<String, ConcurrentLinkedQueue<Failure>>()
    private val latencyMillis = ConcurrentHashMap<String, Long>()

    /** When set, what the "remote" side reports for every account. */
    @Volatile var remoteAccount: PayoutAccountState? = null

    fun failNext(method: String, retryable: Boolean = true, afterPerforming: Boolean = false) {
        failures.computeIfAbsent(method) { ConcurrentLinkedQueue() }.add(Failure(retryable, afterPerforming))
    }

    fun slow(method: String, millis: Long) { latencyMillis[method] = millis }

    fun calls(method: String): List<Any?> = calls.filter { it.method == method }.map { it.arg }

    @Suppress("UNCHECKED_CAST")
    fun <T> args(method: String): List<T> = calls(method) as List<T>

    private fun <T> record(method: String, arg: Any?, perform: () -> T): T {
        calls += Call(method, arg)
        latencyMillis[method]?.let { Thread.sleep(it) }
        val failure = failures[method]?.poll()
        if (failure != null && !failure.afterPerforming) throw PaymentGatewayException("injected $method failure", failure.retryable)
        val result = perform()
        if (failure != null) throw PaymentGatewayException("injected $method failure after it took effect", failure.retryable)
        return result
    }

    override fun createExpressAccount(userId: UUID, email: String) =
        record("createExpressAccount", userId) { super.createExpressAccount(userId, email) }

    override fun createOnboardingLink(accountId: String, refreshUrl: String, returnUrl: String) =
        record("createOnboardingLink", Triple(accountId, refreshUrl, returnUrl)) { super.createOnboardingLink(accountId, refreshUrl, returnUrl) }

    override fun retrieveAccount(accountId: String) =
        record("retrieveAccount", accountId) { remoteAccount ?: super.retrieveAccount(accountId) }

    override fun createCheckoutSession(request: CheckoutRequest) =
        record("createCheckoutSession", request) { super.createCheckoutSession(request) }

    override fun retrieveCheckoutSession(sessionId: String) =
        record("retrieveCheckoutSession", sessionId) { super.retrieveCheckoutSession(sessionId) }

    override fun expireCheckoutSession(sessionId: String, idempotencyKey: String) =
        record("expireCheckoutSession", sessionId to idempotencyKey) { super.expireCheckoutSession(sessionId, idempotencyKey) }

    override fun createTransfer(request: TransferRequest) =
        record("createTransfer", request) { super.createTransfer(request) }

    override fun refund(request: RefundRequest) =
        record("refund", request) { super.refund(request) }
}

/** Runs [block] against the app with its own [AppDeps] whose payments use [gateway] (expiry job off by default). */
fun paymentsTest(
    gateway: PaymentGateway = RecordingGateway(),
    config: AppConfig = TestEnv.config,
    options: PaymentOptions = PaymentOptions(expiryJobEnabled = false),
    block: suspend PaymentsScope.() -> Unit,
) {
    val deps = AppDeps(config, TestEnv.db)
    val module = Payments.install(deps, gateway, options)
    apiTest(deps) { env -> PaymentsScope(this, env, module).block() }
}

class PaymentsScope(val api: ApiTestScope, val env: TestEnv, val module: PaymentsModule) {
    val client: HttpClient get() = api.client
    val gateway: RecordingGateway get() = module.gateway as RecordingGateway
    val fake: FakePaymentGateway get() = module.gateway as FakePaymentGateway

    /** For asserting redirects. */
    val raw: HttpClient by lazy { api.app.createClient { followRedirects = false } }

    fun seller(name: String = "seller" + UUID.randomUUID().toString().take(6), domain: String = "state.edu") =
        env.user(name, domain, payoutsEnabled = true)

    fun buyer(name: String = "buyer" + UUID.randomUUID().toString().take(6), domain: String = "state.edu") = env.user(name, domain)

    suspend fun buy(buyer: TestEnv.TestUser, listingId: UUID): HttpResponse =
        client.post(ApiRoutes.ORDERS) { auth(buyer); json(CreateOrderRequest(listingId.toString())) }

    suspend fun buyOk(buyer: TestEnv.TestUser, listingId: UUID): Order {
        val res = buy(buyer, listingId)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return res.body()
    }

    /** "Pay" on the fake hosted checkout page. */
    suspend fun payViaFakeCheckout(orderId: String): HttpResponse {
        val res = raw.post("${ApiRoutes.BASE}/dev/fake-stripe/checkout/$orderId/pay")
        assertEquals(HttpStatusCode.SeeOther, res.status)
        return res
    }

    /** A PAID order for [listingId] (default: fresh listing of a fresh seller). */
    suspend fun paidOrder(
        buyer: TestEnv.TestUser = buyer(),
        seller: TestEnv.TestUser = seller(),
        listingId: UUID = env.listing(seller),
    ): Order {
        val order = buyOk(buyer, listingId)
        payViaFakeCheckout(order.id)
        assertEquals(OrderStatus.PAID, orderStatus(order.id))
        return order
    }

    fun orderStatus(id: String): OrderStatus =
        env.db.txBlocking { c -> c.queryOne("SELECT status FROM orders WHERE id = ?", UUID.fromString(id)) { OrderStatus.valueOf(it.getString(1)) } }!!

    internal fun orderRow(id: String): OrderRow = env.db.txBlocking { c -> c.findOrder(UUID.fromString(id)) }!!

    fun listingStatus(id: UUID): ListingStatus =
        env.db.txBlocking { c -> c.queryOne("SELECT status FROM listings WHERE id = ?", id) { ListingStatus.valueOf(it.getString(1)) } }!!

    fun listingStatus(order: Order): ListingStatus = listingStatus(UUID.fromString(order.listing.id))

    fun sql(statement: String, vararg params: Any?) = env.db.txBlocking { c -> c.update(statement, *params) }

    /** Makes an order look [minutes] old. */
    fun age(orderId: String, minutes: Long) =
        sql("UPDATE orders SET created_at = now() - (? * interval '1 minute') WHERE id = ?", minutes, UUID.fromString(orderId))

    /** Inserts a COMPLETED order (listing SOLD) between [buyer] and [seller] directly. */
    fun completedOrder(buyer: TestEnv.TestUser, seller: TestEnv.TestUser, title: String = "Desk lamp"): UUID {
        val listingId = env.listing(seller, title = title, status = ListingStatus.SOLD)
        val id = UUID.randomUUID()
        sql(
            """INSERT INTO orders (id, listing_id, buyer_id, seller_id, amount_cents, fee_cents, status, stripe_payment_intent_id,
                                  stripe_transfer_id, paid_at, completed_at)
               VALUES (?, ?, ?, ?, 2500, 125, 'COMPLETED', ?, ?, now(), now())""",
            id, listingId, buyer.id, seller.id, "pi_$id", "tr_$id",
        )
        return id
    }
}

// ---------- disputes, suspension, time ----------

/** A [Clock] tests move by hand (the payments sweep's deadlines follow it; nothing sleeps). */
class MutableClock(@Volatile var now: Instant = Instant.now()) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    fun advance(d: kotlin.time.Duration) {
        now = now.plus(d.toJavaDuration())
    }
}

suspend fun PaymentsScope.openDispute(
    user: TestEnv.TestUser,
    orderId: String,
    reason: DisputeReason = DisputeReason.SELLER_NO_SHOW,
    details: String = "They never showed up",
): HttpResponse = client.post(ApiRoutes.orderDispute(orderId)) { auth(user); json(OpenDisputeRequest(reason, details)) }

suspend fun PaymentsScope.withdrawDispute(user: TestEnv.TestUser, orderId: String): HttpResponse =
    client.post(ApiRoutes.orderDisputeWithdraw(orderId)) { auth(user) }

suspend fun PaymentsScope.resolveDispute(mod: TestEnv.TestUser, orderId: String, resolution: DisputeResolution, note: String = "Checked the chat"): HttpResponse =
    client.post(ApiRoutes.modDisputeResolve(orderId)) { auth(mod); json(ResolveDisputeRequest(resolution, note)) }

fun PaymentsScope.moderator(name: String = "mod" + UUID.randomUUID().toString().take(6)) = env.user(name, role = UserRole.MODERATOR)

/** Suspends [user] directly (as moderation does), until [until] (null = indefinitely). */
fun PaymentsScope.suspendUser(user: TestEnv.TestUser, until: Instant? = null) =
    sql("UPDATE users SET suspended = true, suspended_until = ?, suspension_reason = 'Violation of community guidelines' WHERE id = ?", until, user.id)

suspend fun HttpResponse.assertApiError(status: HttpStatusCode, code: String): ErrorResponse {
    val text = bodyAsText()
    assertEquals(status, this.status, text)
    return ApiJson.decodeFromString(ErrorResponse.serializer(), text).also { assertEquals(code, it.code, text) }
}

// ---------- Stripe webhook payloads ----------

object StripePayloads {
    fun event(id: String, type: String, obj: String, created: Long = Instant.now().epochSecond, account: String? = null): String =
        """{"id":"$id","object":"event","api_version":"2026-10-28.endive","created":$created,"livemode":false,"pending_webhooks":1,""" +
            (if (account != null) """"account":"$account",""" else "") +
            """"request":{"id":null,"idempotency_key":null},"type":"$type","data":{"object":$obj}}"""

    fun session(
        sessionId: String,
        orderId: String?,
        status: String = "complete",
        paymentStatus: String = "paid",
        paymentIntent: String? = "pi_test_1",
    ): String =
        """{"id":"$sessionId","object":"checkout.session","mode":"payment","status":"$status","payment_status":"$paymentStatus",""" +
            """"client_reference_id":${orderId?.let { "\"$it\"" } ?: "null"},"metadata":{${orderId?.let { "\"order_id\":\"$it\"" } ?: ""}},""" +
            """"payment_intent":${paymentIntent?.let { "\"$it\"" } ?: "null"},"amount_total":2500,"currency":"usd"}"""

    fun account(accountId: String, detailsSubmitted: Boolean, payoutsEnabled: Boolean) =
        """{"id":"$accountId","object":"account","type":"express","details_submitted":$detailsSubmitted,"payouts_enabled":$payoutsEnabled,"charges_enabled":false}"""

    fun charge(chargeId: String, paymentIntent: String?, orderId: String?, amount: Long = 2500, refunded: Long = 2500) =
        """{"id":"$chargeId","object":"charge","amount":$amount,"amount_refunded":$refunded,"refunded":${refunded >= amount},""" +
            """"payment_intent":${paymentIntent?.let { "\"$it\"" } ?: "null"},"transfer_group":${orderId?.let { "\"$it\"" } ?: "null"},"currency":"usd"}"""

    /** A valid `Stripe-Signature` header (scheme v1: HMAC-SHA256 over "<t>.<payload>"). */
    fun signature(payload: String, secret: String, timestamp: Long = Instant.now().epochSecond): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val hex = mac.doFinal("$timestamp.$payload".toByteArray()).joinToString("") { "%02x".format(it) }
        return "t=$timestamp,v1=$hex"
    }
}

/** The redirect target, with origin-relative redirects (the fake pages send those) resolved against the test origin. */
fun HttpResponse.location(): String? =
    headers[HttpHeaders.Location]?.let { if (it.startsWith("/")) TestEnv.config.publicBaseUrl + it else it }
