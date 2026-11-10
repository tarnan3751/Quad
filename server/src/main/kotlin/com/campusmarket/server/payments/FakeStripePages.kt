package com.campusmarket.server.payments

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.formatPrice
import com.campusmarket.server.queryOne
import com.campusmarket.server.toUuidOrNull
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant
import java.util.UUID

/**
 * Dev-only stand-ins for Stripe's hosted pages, mounted only while the [FakePaymentGateway] is active.
 * Their buttons feed synthetic events through the real webhook handler, so the state transitions are exactly
 * those of production (`checkout.session.completed` / `.expired`, `account.updated`).
 */
internal fun Route.fakeStripeRoutes(module: PaymentsModule, fake: FakePaymentGateway) {
    val urls = PaymentUrls(module.deps.config.publicBaseUrl, module.deps.config.appDeepLinkBase)
    val db = module.deps.db

    fun eventId() = "evt_fake_${UUID.randomUUID().toString().replace("-", "")}"
    suspend fun ApplicationCall.notFoundPage(what: String) =
        respondHtml(Html.page("Not found", "<h1>Unknown ${Html.escape(what)}</h1>"), HttpStatusCode.NotFound)

    route("${ApiRoutes.BASE}${PaymentUrls.FAKE_PATH}") {

        get("/onboarding/{accountId}") {
            val accountId = call.parameters["accountId"].orEmpty()
            val name = db.tx { c -> c.queryOne("SELECT display_name FROM users WHERE stripe_account_id = ?", accountId) { it.getString(1) } }
                ?: return@get call.notFoundPage("account")
            call.respondHtml(
                Html.page(
                    "Payout setup (test mode)",
                    """<span class="badge">Fake Stripe · test mode</span>
<h1>Set up payouts</h1>
<p>${Html.escape(name)}, this simulates Stripe Express onboarding. No real details are collected.</p>
<p class="muted">Account ${Html.escape(accountId)}</p>
${Html.postButton(urls.local("${urls.fakeOnboarding(accountId)}/complete"), "Complete onboarding")}
${Html.linkButton(urls.local(urls.onboardingRefresh()), "Not now", secondary = true)}""",
                ),
            )
        }

        post("/onboarding/{accountId}/complete") {
            val accountId = call.parameters["accountId"].orEmpty()
            val known = db.tx { c -> c.queryOne("SELECT 1 FROM users WHERE stripe_account_id = ?", accountId) { true } } ?: false
            if (!known) return@post call.notFoundPage("account")
            val state = fake.completeOnboarding(accountId)
            module.webhooks.handle(
                PaymentEvent.AccountUpdated(eventId(), "account.updated", Instant.now(), accountId, state.detailsSubmitted, state.payoutsEnabled),
            )
            call.seeOther(urls.local(fake.onboardingReturnUrl(accountId) ?: urls.onboardingReturn()))
        }

        get("/checkout/{orderId}") {
            val orderId = call.parameters["orderId"]?.toUuidOrNull() ?: return@get call.notFoundPage("checkout")
            val view = db.tx { c ->
                c.queryOne(
                    """SELECT o.status, o.amount_cents, o.currency, o.stripe_checkout_session_id, l.title
                       FROM orders o JOIN listings l ON l.id = o.listing_id WHERE o.id = ?""",
                    orderId,
                ) { CheckoutView(OrderStatus.valueOf(it.getString(1)), it.getInt(2), it.getString(3), it.getString(4), it.getString(5)) }
            } ?: return@get call.notFoundPage("checkout")
            val open = view.status == OrderStatus.PENDING_PAYMENT && view.sessionId != null &&
                fake.session(view.sessionId).status == CheckoutStatus.OPEN
            val price = formatPrice(view.amountCents, view.currency)
            val body = if (open) {
                """<span class="badge">Fake Stripe · test mode</span>
<h1>${Html.escape(view.title)}</h1>
<div class="amount">${Html.escape(price)}</div>
<p class="muted">Simulated checkout — no card needed.</p>
${Html.postButton(urls.local("${urls.fakeCheckout(orderId)}/pay"), "Pay $price")}
${Html.postButton(urls.local("${urls.fakeCheckout(orderId)}/cancel"), "Cancel", secondary = true)}"""
            } else {
                """<h1>This checkout is no longer available</h1>
<p class="muted">Order status: ${view.status.name}</p>
${Html.linkButton(urls.local(urls.checkoutCancel(orderId)), "Back to Quad")}"""
            }
            call.respondHtml(Html.page("Checkout (test mode)", body))
        }

        post("/checkout/{orderId}/pay") {
            val orderId = call.parameters["orderId"]?.toUuidOrNull() ?: return@post call.notFoundPage("checkout")
            val sessionId = db.tx { c -> c.findOrder(orderId) }?.sessionId ?: return@post call.notFoundPage("checkout")
            val state = fake.pay(sessionId)
            val session = fake.session(sessionId)
            if (state.status == CheckoutStatus.COMPLETE) {
                module.webhooks.handle(
                    PaymentEvent.CheckoutCompleted(eventId(), "checkout.session.completed", Instant.now(), sessionId, orderId, state.paymentIntentId, paid = true),
                )
                call.seeOther(urls.local(session.successUrl ?: urls.checkoutSuccess(orderId)))
            } else {
                module.webhooks.handle(PaymentEvent.CheckoutFailed(eventId(), "checkout.session.expired", Instant.now(), sessionId, orderId))
                call.seeOther(urls.local(session.cancelUrl ?: urls.checkoutCancel(orderId)))
            }
        }

        post("/checkout/{orderId}/cancel") {
            val orderId = call.parameters["orderId"]?.toUuidOrNull() ?: return@post call.notFoundPage("checkout")
            val sessionId = db.tx { c -> c.findOrder(orderId) }?.sessionId ?: return@post call.notFoundPage("checkout")
            val state = fake.expireCheckoutSession(sessionId, IdempotencyKeys.expire(orderId))
            if (state.status == CheckoutStatus.EXPIRED) {
                module.webhooks.handle(PaymentEvent.CheckoutFailed(eventId(), "checkout.session.expired", Instant.now(), sessionId, orderId))
            }
            call.seeOther(urls.local(fake.session(sessionId).cancelUrl ?: urls.checkoutCancel(orderId)))
        }
    }
}

private data class CheckoutView(val status: OrderStatus, val amountCents: Int, val currency: String, val sessionId: String?, val title: String)
