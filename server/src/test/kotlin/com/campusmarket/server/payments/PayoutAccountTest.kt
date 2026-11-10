package com.campusmarket.server.payments

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.OnboardingLinkResponse
import com.campusmarket.core.PayoutAccountStatus
import com.campusmarket.server.TestEnv
import com.campusmarket.server.auth
import com.campusmarket.server.queryOne
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PayoutAccountTest {

    private suspend fun PaymentsScope.status(user: TestEnv.TestUser) =
        client.get(ApiRoutes.PAYOUT_ACCOUNT) { auth(user) }.body<PayoutAccountStatus>()

    private suspend fun PaymentsScope.onboard(user: TestEnv.TestUser) = client.post(ApiRoutes.PAYOUT_ONBOARDING) { auth(user) }

    private fun PaymentsScope.accountId(user: TestEnv.TestUser): String? =
        env.db.txBlocking { c -> c.queryOne("SELECT stripe_account_id FROM users WHERE id = ?", user.id) { it.getString(1) } }

    @Test
    fun noAccountYet() = paymentsTest {
        assertEquals(PayoutAccountStatus(hasAccount = false), status(env.user("newbie")))
        assertTrue(gateway.calls.isEmpty())
        assertEquals(HttpStatusCode.Unauthorized, client.get(ApiRoutes.PAYOUT_ACCOUNT).status)
    }

    @Test
    fun onboardingCreatesAccountOnceAndCompletesThroughFakePages() = paymentsTest {
        val user = env.user("sally")
        val link = onboard(user).body<OnboardingLinkResponse>()
        val accountId = FakePaymentGateway.accountId(user.id)
        assertEquals("http://localhost/api/v1/dev/fake-stripe/onboarding/$accountId", link.url)
        assertEquals(accountId, accountId(user))
        assertEquals(listOf<Any?>(user.id), gateway.calls("createExpressAccount"))
        val (_, refreshUrl, returnUrl) = gateway.args<Triple<String, String, String>>("createOnboardingLink").single()
        assertEquals("http://localhost/api/v1/payments/return?to=payouts", returnUrl)
        assertEquals("http://localhost/api/v1/payments/return?to=payouts&refresh=1", refreshUrl)

        assertEquals(PayoutAccountStatus(hasAccount = true, detailsSubmitted = false, payoutsEnabled = false), status(user))

        // a second link reuses the account
        onboard(user).body<OnboardingLinkResponse>()
        assertEquals(1, gateway.calls("createExpressAccount").size)
        assertEquals(2, gateway.calls("createOnboardingLink").size)

        // the fake hosted onboarding
        val page = client.get(link.url.removePrefix("http://localhost"))
        assertEquals(HttpStatusCode.OK, page.status)
        assertContains(page.bodyAsText(), "Complete onboarding")
        val done = raw.post(link.url.removePrefix("http://localhost") + "/complete")
        assertEquals(HttpStatusCode.SeeOther, done.status)
        assertEquals(returnUrl, done.location())
        val bounce = client.get(done.location()!!.removePrefix("http://localhost")).bodyAsText()
        assertContains(bounce, "campusmarket://payouts")

        assertEquals(PayoutAccountStatus(hasAccount = true, detailsSubmitted = true, payoutsEnabled = true), status(user))
        // now buyers can order from them
        buyOk(buyer(), env.listing(user))
    }

    @Test
    fun statusRefreshesFromTheProcessor() = paymentsTest {
        val seller = seller("refreshing")
        gateway.remoteAccount = PayoutAccountState(detailsSubmitted = true, payoutsEnabled = false)
        assertEquals(PayoutAccountStatus(hasAccount = true, detailsSubmitted = true, payoutsEnabled = false), status(seller))
        assertEquals(listOf<Any?>("acct_test_refreshing"), gateway.calls("retrieveAccount"))
        assertFalse(env.db.txBlocking { c -> c.queryOne("SELECT payouts_enabled FROM users WHERE id = ?", seller.id) { it.getBoolean(1) }!! })
    }

    @Test
    fun processorOutageFallsBackToStoredStatus() = paymentsTest {
        val seller = seller("cached")
        gateway.failNext("retrieveAccount")
        assertEquals(PayoutAccountStatus(hasAccount = true, detailsSubmitted = true, payoutsEnabled = true), status(seller))
    }

    @Test
    fun accountCreationFailureIsReported() = paymentsTest {
        val user = env.user("unlucky")
        gateway.failNext("createExpressAccount")
        val res = onboard(user)
        assertEquals(HttpStatusCode.BadGateway, res.status)
        assertEquals(ErrorCodes.PAYMENT_FAILED, res.body<ErrorResponse>().code)
        assertEquals(null, accountId(user))
        // retry works
        assertEquals(HttpStatusCode.OK, onboard(user).status)
        assertEquals(FakePaymentGateway.accountId(user.id), accountId(user))
    }

    @Test
    fun returnPagesBounceIntoTheApp() = paymentsTest(config = TestEnv.config.copy(appDeepLinkBase = "campusmarket-dev://")) {
        val payouts = client.get("/api/v1/payments/return?to=payouts&refresh=1")
        assertEquals(HttpStatusCode.OK, payouts.status)
        assertEquals("no-store", payouts.headers["Cache-Control"])
        val html = payouts.bodyAsText()
        assertContains(html, """<meta http-equiv="refresh" content="0;url=campusmarket-dev://payouts">""")
        assertContains(html, """window.location.replace("campusmarket-dev://payouts")""")

        val id = java.util.UUID.randomUUID()
        assertContains(client.get("/api/v1/payments/return?to=order&id=$id&result=cancel").bodyAsText(), "campusmarket-dev://orders/$id")
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/payments/return?to=order&id=javascript:alert(1)").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/payments/return?to=https://evil.example").status)
    }

    @Test
    fun devPagesAreNotMountedWithTheRealGateway() = paymentsTest(gateway = StripePaymentGateway("sk_test_unused", apiBase = "http://127.0.0.1:9")) {
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/dev/fake-stripe/checkout/${java.util.UUID.randomUUID()}").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/dev/fake-stripe/onboarding/acct_x").status)
    }

    @Test
    fun devPagesAreNotMountedOutsideDevMode() = paymentsTest(config = TestEnv.config.copy(devMode = false)) {
        val order = buyOk(buyer(), env.listing(seller()))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/dev/fake-stripe/checkout/${order.id}").status)
        assertEquals(HttpStatusCode.NotFound, raw.post("/api/v1/dev/fake-stripe/checkout/${order.id}/pay").status)
        assertEquals(com.campusmarket.core.OrderStatus.PENDING_PAYMENT, orderStatus(order.id))
    }
}
