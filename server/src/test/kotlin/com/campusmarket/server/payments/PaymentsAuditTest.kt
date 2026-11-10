package com.campusmarket.server.payments

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CreateRatingRequest
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.Order
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.Rating
import com.campusmarket.server.Budget
import com.campusmarket.server.TestEnv
import com.campusmarket.server.UserRateLimits
import com.campusmarket.server.auth
import com.campusmarket.server.json
import com.campusmarket.server.queryOne
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import com.campusmarket.server.assertApiError

class PaymentsAuditTest {
    /** Wall-clock wait (independent of any virtual test time) while a request runs concurrently. */
    private suspend fun realSleep(ms: Long) = withContext(Dispatchers.IO) { Thread.sleep(ms) }

    private fun limits(l: UserRateLimits) = TestEnv.config.copy(userRateLimits = l)

    private fun PaymentsScope.liveOrders(listingId: UUID): Int = env.db.txBlocking { c ->
        c.queryOne("SELECT count(*) FROM orders WHERE listing_id = ? AND status IN ('PENDING_PAYMENT', 'PAID', 'DISPUTED')", listingId) { it.getInt(1) }!!
    }

    @Test
    fun aBuyerCanHoldOnlyAFewUnpaidCheckoutsAtOnce() = paymentsTest {
        val buyer = buyer()
        val seller = seller()
        val first = buyOk(buyer, env.listing(seller))
        buyOk(buyer, env.listing(seller))
        buyOk(buyer, env.listing(seller))
        val fourth = env.listing(seller)
        buy(buyer, fourth).assertApiError(HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED)
        assertEquals(ListingStatus.ACTIVE, listingStatus(fourth))

        // paying or cancelling one frees a slot
        client.post(ApiRoutes.orderCancel(first.id)) { auth(buyer) }
        assertEquals(OrderStatus.CANCELLED, orderStatus(first.id))
        buyOk(buyer, fourth)
        // other buyers are unaffected
        buyOk(buyer(), env.listing(seller))
    }

    @Test
    fun orderAttemptsAreRateLimited() = paymentsTest(config = limits(UserRateLimits(orders = Budget(2, 1.hours)))) {
        val buyer = buyer()
        val seller = seller()
        val a = buyOk(buyer, env.listing(seller))
        client.post(ApiRoutes.orderCancel(a.id)) { auth(buyer) }
        buyOk(buyer, env.listing(seller))
        buy(buyer, env.listing(seller)).assertApiError(HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED)
    }

    @Test
    fun processorRefreshesAreBudgetedPerUser() = paymentsTest(config = limits(UserRateLimits(processorRefreshes = Budget(2, 1.hours)))) {
        val seller = seller()
        val buyer = buyer()
        val order = buyOk(buyer, env.listing(seller))

        repeat(5) { assertEquals(HttpStatusCode.OK, client.get(ApiRoutes.order(order.id)) { auth(buyer) }.status) }
        repeat(5) { assertEquals(HttpStatusCode.OK, client.get(ApiRoutes.PAYOUT_ACCOUNT) { auth(seller) }.status) }
        assertEquals(2, gateway.calls("retrieveCheckoutSession").size)
        assertEquals(2, gateway.calls("retrieveAccount").size)

        // past the budget the stored state is still served, and webhooks / the fake pages still move it forward
        payViaFakeCheckout(order.id)
        assertEquals(OrderStatus.PAID, client.get(ApiRoutes.order(order.id)) { auth(buyer) }.body<Order>().status)
    }

    /**
     * Account deletion locks the user row, checks for live orders, then takes the listings down. An order created
     * meanwhile must wait for it and then see the seller gone, rather than reserve a listing for a deleted account,
     * and must not deadlock with it now that order creation also locks the listing.
     */
    @Test
    fun orderCreationWaitsForAConcurrentAccountDeletionWithoutDeadlock() = paymentsTest {
        val seller = seller()
        val listing = env.listing(seller)
        val buyer = buyer()
        val deletion = env.db.dataSource.connection.apply { autoCommit = false }
        try {
            deletion.prepareStatement("SELECT 1 FROM users WHERE id = ? FOR UPDATE").use { it.setObject(1, seller.id); it.executeQuery().close() }
            val res = coroutineScope {
                val pending = async { buy(buyer, listing) }
                realSleep(500)
                assertFalse(pending.isCompleted, "order creation should wait for the deleting transaction")
                deletion.prepareStatement("UPDATE users SET deleted_at = now() WHERE id = ?").use { it.setObject(1, seller.id); it.executeUpdate() }
                deletion.prepareStatement("UPDATE listings SET status = 'DELETED' WHERE seller_id = ? AND status IN ('ACTIVE', 'RESERVED')")
                    .use { it.setObject(1, seller.id); it.executeUpdate() }
                deletion.commit()
                pending.await()
            }
            // a clean "listing gone", not a 500 from an aborted (deadlocked) transaction
            assertTrue(res.status.value in 400..499, "${res.status}: ${res.bodyAsText()}")
        } finally {
            deletion.close()
        }
        assertEquals(0, liveOrders(listing))
        assertEquals(ListingStatus.DELETED, listingStatus(listing))
    }

    /** The amount charged is the listing's price at the moment it is reserved, even if the seller edits it concurrently. */
    @Test
    fun orderAmountMatchesThePriceTheListingWasReservedAt() = paymentsTest {
        val seller = seller()
        val listing = env.listing(seller, priceCents = 2500)
        val buyer = buyer()
        val edit = env.db.dataSource.connection.apply { autoCommit = false }
        val order = try {
            edit.prepareStatement("SELECT 1 FROM listings WHERE id = ? FOR UPDATE").use { it.setObject(1, listing); it.executeQuery().close() }
            coroutineScope {
                val pending = async { buy(buyer, listing) }
                realSleep(500)
                edit.prepareStatement("UPDATE listings SET price_cents = 9900 WHERE id = ?").use { it.setObject(1, listing); it.executeUpdate() }
                edit.commit()
                pending.await()
            }
        } finally {
            edit.close()
        }
        assertEquals(HttpStatusCode.OK, order.status, order.bodyAsText())
        assertEquals(9900, order.body<Order>().amountCents)
    }

    @Test
    fun controlCharactersInRatingCommentsAreDropped() = paymentsTest {
        val buyer = buyer()
        val seller = seller()
        val done = completedOrder(buyer, seller)
        val res = client.post(ApiRoutes.orderRating(done.toString())) { auth(buyer); json(CreateRatingRequest(5, "great\u0000 seller")) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals("great seller", res.body<Rating>().comment)
    }

    @Test
    fun serverRenderedPagesSendSecurityHeaders() = paymentsTest {
        val res = raw.get("${ApiRoutes.BASE}${PaymentUrls.RETURN_PATH}?to=payouts")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(HTML_CSP, res.headers["Content-Security-Policy"])
        assertEquals("DENY", res.headers["X-Frame-Options"])
        assertEquals("nosniff", res.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", res.headers["Referrer-Policy"])
    }
}
