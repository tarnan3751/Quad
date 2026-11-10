package com.campusmarket.server.payments

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.Order
import com.campusmarket.core.OrderRole
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.Page
import com.campusmarket.server.auth
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.campusmarket.server.assertApiError

class OrderLifecycleTest {

    @Test
    fun fullBuyFlowThroughFakeCheckout() = paymentsTest {
        val seller = seller("sam")
        val buyer = buyer("bea")
        val listing = env.listing(seller, title = "Road bike <fast>", priceCents = 10_000)
        val order = buyOk(buyer, listing)

        // the fake hosted checkout page
        val page = client.get("/api/v1/dev/fake-stripe/checkout/${order.id}")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertContains(html, "Road bike &lt;fast&gt;")
        assertContains(html, "\$100")
        assertContains(html, """action="/api/v1/dev/fake-stripe/checkout/${order.id}/pay"""")

        // Pay → back to the app via the success bounce page
        val paid = payViaFakeCheckout(order.id)
        assertEquals("http://localhost/api/v1/payments/return?to=order&id=${order.id}&result=success", paid.location())
        val bounce = client.get(paid.location()!!.removePrefix("http://localhost"))
        assertEquals(HttpStatusCode.OK, bounce.status)
        assertContains(bounce.bodyAsText(), "campusmarket://orders/${order.id}")

        val afterPay = getOrder(buyer, order.id)
        assertEquals(OrderStatus.PAID, afterPay.status)
        assertNotNull(afterPay.paidAt)
        assertNull(afterPay.checkoutUrl)
        assertEquals(ListingStatus.RESERVED, listingStatus(listing))
        assertEquals("pi_fake_${order.id.replace("-", "")}", orderRow(order.id).paymentIntentId)

        // buyer confirms the handoff → seller gets price minus fee
        val done = confirm(buyer, order.id).body<Order>()
        assertEquals(OrderStatus.COMPLETED, done.status)
        assertNotNull(done.completedAt)
        assertEquals(ListingStatus.SOLD, done.listing.status)
        assertEquals(ListingStatus.SOLD, listingStatus(listing))

        val transfer = gateway.args<TransferRequest>("createTransfer").single()
        assertEquals(9_500, transfer.amountCents)
        assertEquals("acct_test_sam", transfer.destinationAccountId)
        assertEquals(orderRow(order.id).paymentIntentId, transfer.paymentIntentId)
        assertEquals("cm-order-${order.id}-transfer-0", transfer.idempotencyKey)
        assertEquals(fake.transferFor(UUID.fromString(order.id)), orderRow(order.id).transferId)

        val sellerView = getOrder(seller, order.id)
        assertEquals(OrderRole.SELLER, sellerView.myRole)
        assertEquals(OrderStatus.COMPLETED, sellerView.status)
    }

    @Test
    fun onlyParticipantsSeeOrders() = paymentsTest {
        val order = buyOk(buyer(), env.listing(seller()))
        val stranger = buyer()
        client.get(ApiRoutes.order(order.id)) { auth(stranger) }.assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        confirm(stranger, order.id).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        cancel(stranger, order.id).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        client.get(ApiRoutes.order("nope")) { auth(stranger) }.assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        client.get(ApiRoutes.order(UUID.randomUUID().toString())) { auth(stranger) }.assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        assertEquals(OrderStatus.PENDING_PAYMENT, orderStatus(order.id))
    }

    @Test
    fun confirmRules() = paymentsTest {
        val seller = seller()
        val buyer = buyer()
        val pending = buyOk(buyer, env.listing(seller))
        confirm(buyer, pending.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)

        payViaFakeCheckout(pending.id)
        confirm(seller, pending.id).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        assertEquals(HttpStatusCode.OK, confirm(buyer, pending.id).status)
        // a second confirm is a state error, and never a second transfer
        confirm(buyer, pending.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
        assertEquals(1, gateway.calls("createTransfer").size)
        // completed orders can't be cancelled
        cancel(buyer, pending.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
        cancel(seller, pending.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
    }

    @Test
    fun cancelPendingExpiresCheckoutAndReleasesListing() = paymentsTest {
        val seller = seller()
        val listing = env.listing(seller)
        val order = buyOk(buyer(), listing)

        val res = cancel(seller, order.id)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals(OrderStatus.CANCELLED, res.body<Order>().status)
        assertEquals(ListingStatus.ACTIVE, listingStatus(listing))
        val (sessionId, key) = gateway.args<Pair<String, String>>("expireCheckoutSession").single()
        assertEquals(orderRow(order.id).sessionId, sessionId)
        assertEquals("cm-order-${order.id}-expire", key)
        assertEquals(CheckoutStatus.EXPIRED, fake.session(sessionId).status)

        // the dead checkout can't be paid any more, and cancelling again is a state error
        payViaFakeCheckout(order.id)
        assertEquals(OrderStatus.CANCELLED, orderStatus(order.id))
        cancel(seller, order.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
        assertTrue(gateway.calls("refund").isEmpty())

        // and the listing is buyable again
        buyOk(buyer(), listing)
    }

    @Test
    fun buyerCanCancelPendingToo() = paymentsTest {
        val buyer = buyer()
        val order = buyOk(buyer, env.listing(seller()))
        assertEquals(OrderStatus.CANCELLED, cancel(buyer, order.id).body<Order>().status)
    }

    @Test
    fun sellerCancellingAPaidOrderRefundsTheBuyer() = paymentsTest {
        val buyer = buyer()
        val seller = seller()
        val order = paidOrder(buyer = buyer, seller = seller)

        val res = cancel(seller, order.id)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals(OrderStatus.REFUNDED, res.body<Order>().status)
        assertEquals(ListingStatus.ACTIVE, listingStatus(order))
        val refund = gateway.args<RefundRequest>("refund").single()
        assertEquals(orderRow(order.id).paymentIntentId, refund.paymentIntentId)
        assertEquals("cm-order-${order.id}-refund-0", refund.idempotencyKey)
        assertEquals(fake.refundFor(UUID.fromString(order.id)), orderRow(order.id).refundId)
        assertTrue(gateway.calls("createTransfer").isEmpty())

        cancel(seller, order.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
        confirm(buyer, order.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
    }

    /** The buyer may already hold the item: they can't refund themselves, they report a problem instead. */
    @Test
    fun buyerCanNotCancelAPaidOrder() = paymentsTest {
        val buyer = buyer()
        val order = paidOrder(buyer = buyer)
        cancel(buyer, order.id).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        assertEquals(OrderStatus.PAID, orderStatus(order.id))
        assertEquals(ListingStatus.RESERVED, listingStatus(order))
        assertTrue(gateway.calls("refund").isEmpty())
        assertEquals(null, orderRow(order.id).pendingAction)
    }

    @Test
    fun sellerCanCancelPaidOrder() = paymentsTest {
        val seller = seller()
        val order = paidOrder(seller = seller)
        assertEquals(OrderStatus.REFUNDED, cancel(seller, order.id).body<Order>().status)
    }

    @Test
    fun fakeCheckoutCancelButtonExpiresTheOrder() = paymentsTest {
        val listing = env.listing(seller())
        val order = buyOk(buyer(), listing)
        val res = raw.post("/api/v1/dev/fake-stripe/checkout/${order.id}/cancel")
        assertEquals(HttpStatusCode.SeeOther, res.status)
        assertEquals("http://localhost/api/v1/payments/return?to=order&id=${order.id}&result=cancel", res.location())
        assertEquals(OrderStatus.CANCELLED, orderStatus(order.id))
        assertEquals(ListingStatus.ACTIVE, listingStatus(listing))

        // the page now says it's gone
        assertContains(client.get("/api/v1/dev/fake-stripe/checkout/${order.id}").bodyAsText(), "no longer available")
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/dev/fake-stripe/checkout/${UUID.randomUUID()}").status)
    }

    @Test
    fun listsOrdersNewestFirstWithRoleFilterAndPaging() = paymentsTest {
        val me = seller("me")
        val other = seller("other")
        val bought1 = buyOk(me, env.listing(other))
        val sold = buyOk(other, env.listing(me))
        val bought2 = buyOk(me, env.listing(other))
        buyOk(buyer(), env.listing(other)) // not mine

        suspend fun list(query: String) = client.get("${ApiRoutes.ORDERS}$query") { auth(me) }.body<Page<Order>>()

        assertEquals(listOf(bought2.id, sold.id, bought1.id), list("").items.map { it.id })
        assertEquals(listOf(bought2.id, bought1.id), list("?role=buyer").items.map { it.id })
        assertEquals(listOf(sold.id), list("?role=seller").items.map { it.id })
        assertTrue(list("?role=seller").items.all { it.myRole == OrderRole.SELLER })

        val p1 = list("?limit=2")
        assertEquals(listOf(bought2.id, sold.id), p1.items.map { it.id })
        val p2 = list("?limit=2&cursor=${p1.nextCursor!!}")
        assertEquals(listOf(bought1.id), p2.items.map { it.id })
        assertNull(p2.nextCursor)

        assertEquals(HttpStatusCode.BadRequest, client.get("${ApiRoutes.ORDERS}?role=admin") { auth(me) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("${ApiRoutes.ORDERS}?cursor=garbage") { auth(me) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(ApiRoutes.ORDERS).status)
    }
}
