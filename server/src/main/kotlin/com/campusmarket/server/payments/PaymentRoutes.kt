package com.campusmarket.server.payments

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CreateOrderRequest
import com.campusmarket.core.CreateRatingRequest
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.OkResponse
import com.campusmarket.core.OpenDisputeRequest
import com.campusmarket.core.ResolveDisputeRequest
import com.campusmarket.server.AUTH_JWT
import com.campusmarket.server.AppConfig
import com.campusmarket.server.AppDeps
import com.campusmarket.server.badRequest
import com.campusmarket.server.invalidField
import com.campusmarket.server.requireModerator
import com.campusmarket.server.toUuidOrNull
import com.campusmarket.server.user
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.Collections
import java.util.WeakHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Tunables of the payments feature (defaults follow docs/CONTRACT.md). */
data class PaymentOptions(
    /**
     * Run the background sweep (every [expiryJobInterval]): cancels abandoned checkouts, puts suspended sellers' orders
     * on hold, and releases PAID orders whose hold has run out.
     */
    val expiryJobEnabled: Boolean = true,
    val expiryJobInterval: Duration = 1.minutes,
    /** PENDING_PAYMENT orders older than this are cancelled by the sweep. */
    val pendingOrderTtl: Duration = 35.minutes,
    /** Checkout Session lifetime: 30 minutes (Stripe's minimum) plus a margin so Stripe never rejects it as too short. */
    val checkoutSessionTtl: Duration = 31.minutes,
    /**
     * A PAID order is released to the seller automatically this long after payment (or after the buyer withdrew a
     * dispute), unless the buyer confirms receipt first or reports a problem (`AUTO_RELEASE_HOURS`, default 72).
     */
    val autoReleaseAfter: Duration = DEFAULT_AUTO_RELEASE_HOURS.hours,
    /** "Now" for the sweep's deadlines and suspension checks; tests inject a movable clock instead of sleeping. */
    val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(autoReleaseAfter.isPositive()) { "AUTO_RELEASE_HOURS must be positive" }
    }

    companion object {
        const val DEFAULT_AUTO_RELEASE_HOURS = 72

        /** Production options: defaults, plus `AUTO_RELEASE_HOURS` (whole hours, >= 1). */
        fun fromEnv(env: Map<String, String> = System.getenv()): PaymentOptions {
            val raw = env["AUTO_RELEASE_HOURS"]?.trim()?.takeIf { it.isNotEmpty() }
            val hours = raw?.let { it.toIntOrNull()?.takeIf { h -> h >= 1 } ?: error("AUTO_RELEASE_HOURS must be a whole number of hours >= 1, got '$it'") }
            return PaymentOptions(autoReleaseAfter = (hours ?: DEFAULT_AUTO_RELEASE_HOURS).hours)
        }
    }
}

/** The payments feature's services, wired for one [AppDeps]. */
class PaymentsModule internal constructor(val deps: AppDeps, val gateway: PaymentGateway, val options: PaymentOptions) {
    internal val orders = OrderService(deps, gateway, options)
    internal val disputes = DisputeService(deps, orders)
    internal val payouts = PayoutService(deps, gateway)
    internal val webhooks = WebhookService(deps, orders)
    internal val ratings = RatingService(deps)

    /** One run of the background sweep; each part is independent (a failure in one doesn't stop the others). */
    internal suspend fun runSweep(): SweepResult {
        suspend fun part(name: String, block: suspend () -> Int): Int = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Payments sweep: {} failed", name, e)
            0
        }
        return SweepResult(
            expired = part("expiring abandoned checkouts") { orders.expireStaleOrders() },
            heldForSuspendedSellers = part("holding suspended sellers' orders") { orders.holdSuspendedSellersOrders() },
            autoReleased = part("auto-release") { orders.releaseDueOrders() },
        )
    }
}

internal data class SweepResult(val expired: Int, val heldForSuspendedSellers: Int, val autoReleased: Int)

/**
 * Per-[AppDeps] registry of payment wiring. By default the gateway follows the config: [StripePaymentGateway] when
 * `STRIPE_SECRET_KEY` is set, else [FakePaymentGateway]. Tests (or tooling) can [install] a different gateway or
 * options for a deps instance before the application starts.
 */
object Payments {
    private val modules: MutableMap<AppDeps, PaymentsModule> = Collections.synchronizedMap(WeakHashMap())

    fun install(deps: AppDeps, gateway: PaymentGateway = defaultGateway(deps.config), options: PaymentOptions = PaymentOptions()): PaymentsModule =
        PaymentsModule(deps, gateway, options).also { modules[deps] = it }

    fun forDeps(deps: AppDeps): PaymentsModule = synchronized(modules) {
        modules.getOrPut(deps) { PaymentsModule(deps, defaultGateway(deps.config), PaymentOptions.fromEnv()) }
    }

    fun defaultGateway(config: AppConfig): PaymentGateway =
        config.stripeSecretKey?.let { StripePaymentGateway(it) } ?: FakePaymentGateway(config.publicBaseUrl)
}

private val log = LoggerFactory.getLogger("com.campusmarket.server.payments")

/** Stripe events are a few KB; anything this big isn't one. */
private const val MAX_WEBHOOK_BYTES = 1L shl 20

/** Entry point for the payments feature; called once from Application.module. See docs/CONTRACT.md. */
fun Route.paymentRoutes(deps: AppDeps) {
    val module = Payments.forDeps(deps)
    val config = deps.config
    val urls = PaymentUrls(config.publicBaseUrl, config.appDeepLinkBase)
    val fake = module.gateway as? FakePaymentGateway
    log.info("Payments via {}", if (fake != null) "the fake processor (dev pages under ${ApiRoutes.BASE}${PaymentUrls.FAKE_PATH})" else module.gateway::class.simpleName)
    if (config.stripeSecretKey != null && config.stripeWebhookSecret == null) {
        log.error("STRIPE_SECRET_KEY is set but STRIPE_WEBHOOK_SECRET is not: payments will only be picked up when an order is viewed")
    }
    if (fake != null && !config.devMode) {
        log.error("DEV_MODE=false but no STRIPE_SECRET_KEY: checkout is unavailable (the fake processor's pages are dev-only)")
    }
    application.launchOrderExpiryJob(module)

    // Stripe → us. Public; authenticated by the signature.
    post(ApiRoutes.STRIPE_WEBHOOK) {
        if ((call.request.contentLength() ?: 0L) > MAX_WEBHOOK_BYTES) badRequest("Payload too large")
        val payload = String(call.receive<ByteArray>(), Charsets.UTF_8)
        val event = module.webhooks.verify(payload, call.request.headers["Stripe-Signature"])
        module.webhooks.handle(event)
        call.respond(OkResponse())
    }

    // Browser landing pages for Account Links and Checkout that bounce into the app.
    get("${ApiRoutes.BASE}${PaymentUrls.RETURN_PATH}") {
        val q = call.request.queryParameters
        val orderId = q["id"]?.toUuidOrNull()
        when {
            q["to"] == "payouts" -> call.respondHtml(Html.bounce(urls.appPayouts(), "Payout setup"))
            q["to"] == "order" && orderId != null -> call.respondHtml(
                Html.bounce(urls.appOrder(orderId), if (q["result"] == "success") "Payment received" else "Checkout closed"),
            )
            else -> call.respondHtml(Html.page("Not found", "<h1>Nothing to see here</h1>"), HttpStatusCode.NotFound)
        }
    }

    // Never outside dev mode: anyone could "pay" through them.
    if (fake != null && config.devMode) fakeStripeRoutes(module, fake)

    authenticate(AUTH_JWT) {
        get(ApiRoutes.PAYOUT_ACCOUNT) { call.respond(module.payouts.status(call.user)) }
        post(ApiRoutes.PAYOUT_ONBOARDING) { call.respond(module.payouts.onboardingLink(call.user)) }

        post(ApiRoutes.ORDERS) {
            val req = call.receive<CreateOrderRequest>()
            call.respond(module.orders.create(call.user, req.listingId))
        }
        get(ApiRoutes.ORDERS) {
            call.respond(module.orders.list(call.user, call.request.queryParameters["role"], call.pageRequest()))
        }
        get("${ApiRoutes.ORDERS}/{id}") { call.respond(module.orders.get(call.user, call.parameters["id"].orEmpty())) }
        post("${ApiRoutes.ORDERS}/{id}/confirm") { call.respond(module.orders.confirm(call.user, call.parameters["id"].orEmpty())) }
        post("${ApiRoutes.ORDERS}/{id}/cancel") { call.respond(module.orders.cancel(call.user, call.parameters["id"].orEmpty())) }
        post("${ApiRoutes.ORDERS}/{id}/dispute") {
            val req = call.receive<OpenDisputeRequest>()
            call.respond(module.orders.openDispute(call.user, call.parameters["id"].orEmpty(), req))
        }
        post("${ApiRoutes.ORDERS}/{id}/dispute/withdraw") {
            call.respond(module.orders.withdrawDispute(call.user, call.parameters["id"].orEmpty()))
        }

        // Moderators: the dispute queue and its resolutions (money moves, so this lives with the order state machine).
        get(ApiRoutes.MOD_DISPUTES) {
            call.requireModerator()
            val status = when (val s = call.request.queryParameters["status"]) {
                null, "" -> DisputeStatus.OPEN
                else -> runCatching { DisputeStatus.valueOf(s) }.getOrNull() ?: invalidField("status", "Unknown status '$s'")
            }
            call.respond(module.disputes.list(status, call.pageRequest()))
        }
        get("${ApiRoutes.MOD_DISPUTES}/{orderId}") {
            call.requireModerator()
            call.respond(module.disputes.case(call.parameters["orderId"].orEmpty()))
        }
        post("${ApiRoutes.MOD_DISPUTES}/{orderId}/resolve") {
            val mod = call.requireModerator()
            val req = call.receive<ResolveDisputeRequest>()
            call.respond(module.disputes.resolve(mod, call.parameters["orderId"].orEmpty(), req))
        }

        post("${ApiRoutes.ORDERS}/{id}/rating") {
            val req = call.receive<CreateRatingRequest>()
            call.respond(module.ratings.rate(call.user, call.parameters["id"].orEmpty(), req))
        }
        get("${ApiRoutes.BASE}/users/{id}/ratings") {
            call.respond(module.ratings.received(call.user, call.parameters["id"].orEmpty(), call.pageRequest()))
        }
    }
}

/**
 * Every [PaymentOptions.expiryJobInterval], runs the payments sweep ([PaymentsModule.runSweep]): cancels abandoned
 * checkouts, puts suspended sellers' orders on hold and auto-releases PAID orders whose hold has run out. Lives in
 * the application's scope, so it stops with the application; runs on the IO dispatcher with real-time delays (also
 * under the test engine, where the first sweep is a full interval away and so never fires during an ordinary test).
 */
internal fun Application.launchOrderExpiryJob(module: PaymentsModule): Job? {
    if (!module.options.expiryJobEnabled) return null
    return launch(Dispatchers.IO + CoroutineName("order-sweep")) {
        while (isActive) {
            delay(module.options.expiryJobInterval)
            try {
                val r = module.runSweep()
                if (r.expired + r.heldForSuspendedSellers + r.autoReleased > 0) {
                    log.info(
                        "Payments sweep: {} abandoned checkout(s) closed, {} order(s) of suspended sellers held, {} order(s) auto-released",
                        r.expired, r.heldForSuspendedSellers, r.autoReleased,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Payments sweep failed", e)
            }
        }
    }
}
