package com.campusmarket.server.payments

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CreateOrderRequest
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.OrderRole
import com.campusmarket.core.OrderStatus
import com.campusmarket.server.TestEnv
import com.campusmarket.server.auth
import com.campusmarket.server.json
import com.campusmarket.server.query
import com.campusmarket.server.queryOne
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.campusmarket.server.assertApiError

class OrderCreationTest {

    @Test
    fun createsPendingOrderReservesListingAndOpensCheckout() = paymentsTest {
        val seller = seller("sam")
        val buyer = buyer("bea")
        val listingId = env.listing(seller, title = "Desk lamp", priceCents = 2500)

        val before = Instant.now()
        val order = buyOk(buyer, listingId)

        assertEquals(OrderStatus.PENDING_PAYMENT, order.status)
        assertEquals(OrderRole.BUYER, order.myRole)
        assertEquals(2500, order.amountCents)
        assertEquals(125, order.feeCents) // 500 bps
        assertEquals("usd", order.currency)
        assertEquals(listingId.toString(), order.listing.id)
        assertEquals(ListingStatus.RESERVED, order.listing.status)
        assertEquals(seller.id.toString(), order.seller.id)
        assertEquals(buyer.id.toString(), order.buyer.id)
        assertEquals("http://localhost/api/v1/dev/fake-stripe/checkout/${order.id}", order.checkoutUrl)
        assertEquals(false, order.iHaveRated)
        assertNull(order.paidAt)
        assertEquals(ListingStatus.RESERVED, listingStatus(listingId))

        val req = gateway.args<CheckoutRequest>("createCheckoutSession").single()
        assertEquals(order.id, req.orderId.toString())
        assertEquals(2500, req.amountCents)
        assertEquals("Desk lamp", req.title)
        assertEquals(buyer.email, req.buyerEmail)
        assertEquals("cm-order-${order.id}-checkout", req.idempotencyKey)
        assertEquals("http://localhost/api/v1/payments/return?to=order&id=${order.id}&result=success", req.successUrl)
        assertEquals("http://localhost/api/v1/payments/return?to=order&id=${order.id}&result=cancel", req.cancelUrl)
        val ttl = Duration.between(before, req.expiresAt)
        assertTrue(ttl >= Duration.ofMinutes(30) && ttl <= Duration.ofMinutes(32), "session expiry $ttl")

        val row = orderRow(order.id)
        assertEquals(FakePaymentGateway.sessionId(UUID.fromString(order.id)), row.sessionId)

        // the seller sees it too, without the buyer's checkout link
        val asSeller = module.orders.render(UUID.fromString(order.id), seller.id)
        assertEquals(OrderRole.SELLER, asSeller.myRole)
        assertNull(asSeller.checkoutUrl)
    }

    @Test
    fun feeFollowsConfiguredBasisPoints() = paymentsTest(config = TestEnv.config.copy(platformFeeBps = 1234)) {
        val order = buyOk(buyer(), env.listing(seller(), priceCents = 9999))
        assertEquals(9999L * 1234 / 10000, order.feeCents.toLong())
    }

    @Test
    fun requiresAuthentication() = paymentsTest {
        val res = client.post(ApiRoutes.ORDERS) { json(CreateOrderRequest(UUID.randomUUID().toString())) }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun cannotBuyOwnListing() = paymentsTest {
        val seller = seller()
        buy(seller, env.listing(seller)).assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
    }

    @Test
    fun unknownMalformedHiddenAndOtherCampusListingsAre404() = paymentsTest {
        val buyer = buyer()
        val seller = seller()
        buy(buyer, UUID.randomUUID()).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        client.post(ApiRoutes.ORDERS) { auth(buyer); json(CreateOrderRequest("not-a-uuid")) }
            .assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        buy(buyer, env.listing(seller, status = ListingStatus.REMOVED)).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        buy(buyer, env.listing(seller, status = ListingStatus.DELETED)).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)

        val elsewhere = seller(domain = "other.edu")
        buy(buyer, env.listing(elsewhere)).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun blocksEitherWayAreForbidden() = paymentsTest {
        val seller = seller()
        val buyer = buyer()
        val listing = env.listing(seller)
        sql("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", seller.id, buyer.id)
        buy(buyer, listing).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.BLOCKED)

        sql("DELETE FROM blocks")
        sql("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", buyer.id, seller.id)
        buy(buyer, listing).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.BLOCKED)
        assertEquals(ListingStatus.ACTIVE, listingStatus(listing))
    }

    @Test
    fun listingMustBeActive() = paymentsTest {
        val seller = seller()
        val buyer = buyer()
        buy(buyer, env.listing(seller, status = ListingStatus.RESERVED)).assertApiError(HttpStatusCode.Conflict, ErrorCodes.LISTING_UNAVAILABLE)
        buy(buyer, env.listing(seller, status = ListingStatus.SOLD)).assertApiError(HttpStatusCode.Conflict, ErrorCodes.LISTING_UNAVAILABLE)
    }

    @Test
    fun suspendedSellersListingsAreUnavailable() = paymentsTest {
        val seller = seller()
        val listing = env.listing(seller)
        sql("UPDATE users SET suspended = true WHERE id = ?", seller.id)
        buy(buyer(), listing).assertApiError(HttpStatusCode.Conflict, ErrorCodes.LISTING_UNAVAILABLE)
        assertEquals(ListingStatus.ACTIVE, listingStatus(listing))
    }

    @Test
    fun sellerMustHavePayoutsEnabled() = paymentsTest {
        val seller = env.user("nopayouts")
        val listing = env.listing(seller)
        buy(buyer(), listing).assertApiError(HttpStatusCode.Conflict, ErrorCodes.PAYOUTS_NOT_ENABLED)
        assertEquals(ListingStatus.ACTIVE, listingStatus(listing))
        assertEquals(0, env.db.txBlocking { c -> c.findOrderCount() })
    }

    @Test
    fun secondBuyerGetsListingUnavailable() = paymentsTest {
        val listing = env.listing(seller())
        buyOk(buyer(), listing)
        buy(buyer(), listing).assertApiError(HttpStatusCode.Conflict, ErrorCodes.LISTING_UNAVAILABLE)
    }

    @Test
    fun concurrentBuysYieldExactlyOneOrder() = paymentsTest {
        val listing = env.listing(seller())
        val buyers = (1..8).map { buyer() }
        val results = coroutineScope { buyers.map { b -> async { buy(b, listing) } }.awaitAll() }

        assertEquals(1, results.count { it.status == HttpStatusCode.OK })
        results.filter { it.status != HttpStatusCode.OK }.forEach { it.assertApiError(HttpStatusCode.Conflict, ErrorCodes.LISTING_UNAVAILABLE) }
        assertEquals(1, env.db.txBlocking { c -> c.findOrderCount() })
        assertEquals(ListingStatus.RESERVED, listingStatus(listing))
    }

    @Test
    fun liveOrderIndexIsTheBackstop() = paymentsTest {
        // Even if the listing row says ACTIVE, an existing live order blocks a second one (unique index → 409).
        val listing = env.listing(seller())
        buyOk(buyer(), listing)
        sql("UPDATE listings SET status = 'ACTIVE' WHERE id = ?", listing)
        buy(buyer(), listing).assertApiError(HttpStatusCode.Conflict, ErrorCodes.LISTING_UNAVAILABLE)
    }

    @Test
    fun checkoutFailureRollsBackTheReservation() = paymentsTest {
        val listing = env.listing(seller())
        val buyer = buyer()
        gateway.failNext("createCheckoutSession", retryable = true)
        buy(buyer, listing).assertApiError(HttpStatusCode.BadGateway, ErrorCodes.PAYMENT_FAILED)

        assertEquals(ListingStatus.ACTIVE, listingStatus(listing))
        val statuses = env.db.txBlocking { c -> c.orderStatuses() }
        assertEquals(listOf(OrderStatus.CANCELLED), statuses)

        // and the listing can be bought again
        val order = buyOk(buyer, listing)
        assertNotNull(order.checkoutUrl)
    }
}

private fun java.sql.Connection.findOrderCount(): Int = queryOne("SELECT count(*) FROM orders") { it.getInt(1) }!!

private fun java.sql.Connection.orderStatuses(): List<OrderStatus> = query("SELECT status FROM orders") { OrderStatus.valueOf(it.getString(1)) }
