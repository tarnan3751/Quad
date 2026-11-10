package com.campusmarket.server.payments

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.DisputeCase
import com.campusmarket.core.DisputeOutcome
import com.campusmarket.core.DisputeReason
import com.campusmarket.core.DisputeResolution
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.Order
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.Page
import com.campusmarket.server.TestEnv
import com.campusmarket.server.auth
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Suspended accounts and money: a suspended seller's unpaid checkouts are closed and can't be paid any more, their
 * paid orders are frozen for a moderator, and nothing is released to them automatically. A suspended buyer's orders
 * are settled by the hold running out (or by a moderator).
 */
class SuspendedSellerTest {

    private fun clocked(block: suspend PaymentsScope.(MutableClock) -> Unit) {
        val clock = MutableClock()
        paymentsTest(options = PaymentOptions(expiryJobEnabled = false, clock = clock)) { block(clock) }
    }

    private suspend fun PaymentsScope.confirm(user: TestEnv.TestUser, id: String) = client.post(ApiRoutes.orderConfirm(id)) { auth(user) }
    private suspend fun PaymentsScope.getOrder(user: TestEnv.TestUser, id: String) = client.get(ApiRoutes.order(id)) { auth(user) }.body<Order>()

    @Test
    fun aSuspendedSellersUnpaidCheckoutsAreClosed() = clocked {
        val seller = seller()
        val listing = env.listing(seller)
        val order = buyOk(buyer(), listing)
        val other = buyOk(buyer(), env.listing(seller()))
        suspendUser(seller)

        assertEquals(1, module.orders.holdSuspendedSellersOrders())
        assertEquals(OrderStatus.CANCELLED, orderStatus(order.id))
        assertEquals(CheckoutStatus.EXPIRED, fake.session(orderRow(order.id).sessionId!!).status)
        assertEquals(OrderStatus.PENDING_PAYMENT, orderStatus(other.id))
        // the listing is released (browse hides a suspended seller's listings anyway)
        assertEquals(ListingStatus.ACTIVE, listingStatus(listing))
        // and the closed checkout can't be paid any more
        payViaFakeCheckout(order.id)
        assertEquals(OrderStatus.CANCELLED, orderStatus(order.id))
        assertEquals(0, module.orders.holdSuspendedSellersOrders())
    }

    @Test
    fun aPaymentThatArrivesAfterTheSellerWasSuspendedIsRefunded() = clocked {
        val seller = seller()
        val listing = env.listing(seller)
        val order = buyOk(buyer(), listing)
        suspendUser(seller)
        // the buyer pays before the sweep closed the checkout
        payViaFakeCheckout(order.id)
        assertEquals(OrderStatus.REFUNDED, orderStatus(order.id))
        assertEquals(1, gateway.calls("refund").size)
        assertTrue(gateway.calls("createTransfer").isEmpty())
        assertEquals(ListingStatus.ACTIVE, listingStatus(listing))
        assertEquals(0, module.orders.holdSuspendedSellersOrders())
    }

    @Test
    fun aSuspendedSellersPaidOrdersGoToAModerator() = clocked { clock ->
        val buyer = buyer()
        val seller = seller()
        val order = paidOrder(buyer = buyer, seller = seller)
        val untouched = paidOrder()
        suspendUser(seller)

        assertEquals(1, module.orders.holdSuspendedSellersOrders())
        val held = getOrder(buyer, order.id)
        assertEquals(OrderStatus.DISPUTED, held.status)
        assertNull(held.autoReleaseAt)
        val d = held.dispute!!
        assertEquals(DisputeReason.SELLER_SUSPENDED, d.reason)
        assertEquals(DisputeStatus.OPEN, d.status)
        assertTrue(d.openedBySystem)
        assertEquals(OrderStatus.PAID, orderStatus(untouched.id))
        assertEquals(0, module.orders.holdSuspendedSellersOrders(), "idempotent")

        // the buyer can't withdraw a hold the platform placed …
        withdrawDispute(buyer, order.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
        // … nothing is released, however long it waits …
        clock.advance(500.hours)
        assertEquals(1, module.orders.releaseDueOrders()) // only the other seller's order
        assertEquals(OrderStatus.DISPUTED, orderStatus(order.id))
        assertNull(fake.transferFor(UUID.fromString(order.id)))

        // … until a moderator decides
        val mod = moderator()
        val queue = client.get(ApiRoutes.MOD_DISPUTES) { auth(mod) }.body<Page<DisputeCase>>().items
        val case = queue.single()
        assertEquals(order.id, case.orderId)
        assertTrue(case.sellerSuspended)
        assertTrue(case.dispute.openedBySystem)
        val resolved = resolveDispute(mod, order.id, DisputeResolution.REFUND_BUYER, "Seller banned for fraud").body<DisputeCase>()
        assertEquals(OrderStatus.REFUNDED, resolved.orderStatus)
        assertEquals(DisputeOutcome.REFUNDED_BY_MODERATOR, resolved.dispute.outcome)
        assertEquals(1, gateway.calls("refund").size)
    }

    @Test
    fun aModeratorCanStillReleaseToASuspendedSeller() = clocked {
        val buyer = buyer()
        val seller = seller()
        val order = paidOrder(buyer = buyer, seller = seller)
        suspendUser(seller)
        module.orders.holdSuspendedSellersOrders()
        val resolved = resolveDispute(moderator(), order.id, DisputeResolution.RELEASE_TO_SELLER, "Item was delivered").body<DisputeCase>()
        assertEquals(OrderStatus.COMPLETED, resolved.orderStatus)
        assertEquals(1, gateway.calls("createTransfer").size)
    }

    @Test
    fun theBuyerMayStillConfirmReceiptOfAHeldOrder() = clocked {
        val buyer = buyer()
        val seller = seller()
        val order = paidOrder(buyer = buyer, seller = seller)
        suspendUser(seller)
        module.orders.holdSuspendedSellersOrders()
        val done = confirm(buyer, order.id).body<Order>()
        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(DisputeOutcome.BUYER_CONFIRMED, done.dispute!!.outcome)
    }

    @Test
    fun autoReleaseNeverPaysASuspendedSellerEvenBeforeTheSweepHeldTheOrder() = clocked { clock ->
        val seller = seller()
        val order = paidOrder(seller = seller)
        clock.advance(73.hours)
        suspendUser(seller)
        assertEquals(0, module.orders.releaseDueOrders())
        assertNull(env.db.txBlocking { c -> c.claimForAutoRelease(UUID.fromString(order.id), clock.now.minusSeconds(72 * 3600), clock.now) })
        assertEquals(OrderStatus.PAID, orderStatus(order.id))
        // the full sweep holds it for a moderator instead
        val r = module.runSweep()
        assertEquals(1, r.heldForSuspendedSellers)
        assertEquals(0, r.autoReleased)
        assertEquals(OrderStatus.DISPUTED, orderStatus(order.id))
    }

    @Test
    fun anExpiredSuspensionNoLongerCounts() = clocked { clock ->
        val seller = seller()
        val order = paidOrder(seller = seller)
        suspendUser(seller, until = clock.now.plusSeconds(3600))
        assertEquals(1, module.orders.holdSuspendedSellersOrders(), "suspended right now")

        val seller2 = seller()
        val order2 = paidOrder(seller = seller2)
        suspendUser(seller2, until = clock.now.minusSeconds(60))
        assertEquals(0, module.orders.holdSuspendedSellersOrders())
        clock.advance(73.hours)
        assertEquals(1, module.orders.releaseDueOrders())
        assertEquals(OrderStatus.COMPLETED, orderStatus(order2.id))
        assertEquals(OrderStatus.DISPUTED, orderStatus(order.id))
    }

    @Test
    fun aSuspendedBuyersPaidOrderIsSettledByTheHoldRunningOut() = clocked { clock ->
        val buyer = buyer()
        val order = paidOrder(buyer = buyer)
        suspendUser(buyer)
        // they can't act any more
        assertEquals(HttpStatusCode.Forbidden, confirm(buyer, order.id).status)
        assertEquals(0, module.orders.holdSuspendedSellersOrders())
        clock.advance(73.hours)
        assertEquals(1, module.orders.releaseDueOrders())
        assertEquals(OrderStatus.COMPLETED, orderStatus(order.id))
    }

    @Test
    fun aPaymentRecordedAtTransitionTimeChecksTheSuspensionToo() = clocked {
        // the webhook path (not the fake pages): checkout.session.completed for a suspended seller's order
        val seller = seller()
        val order = buyOk(buyer(), env.listing(seller))
        suspendUser(seller)
        val sessionId = orderRow(order.id).sessionId!!
        fake.pay(sessionId)
        module.webhooks.handle(
            PaymentEvent.CheckoutCompleted("evt_susp", "checkout.session.completed", Instant.now(), sessionId, UUID.fromString(order.id), "pi_fake_x", paid = true),
        )
        assertEquals(OrderStatus.REFUNDED, orderStatus(order.id))
        assertEquals("pi_fake_x", gateway.args<RefundRequest>("refund").single().paymentIntentId)
    }
}
