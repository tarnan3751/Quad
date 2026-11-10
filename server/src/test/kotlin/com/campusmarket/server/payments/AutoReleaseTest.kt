package com.campusmarket.server.payments

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.Order
import com.campusmarket.core.OrderStatus
import com.campusmarket.server.TestEnv
import com.campusmarket.server.auth
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Automatic release of held payments: timing (on a test clock), Stripe failures, and races with the parties. */
class AutoReleaseTest {

    private fun clocked(autoRelease: Duration = 72.hours, block: suspend PaymentsScope.(MutableClock) -> Unit) {
        val clock = MutableClock()
        paymentsTest(options = PaymentOptions(expiryJobEnabled = false, autoReleaseAfter = autoRelease, clock = clock)) { block(clock) }
    }

    private suspend fun PaymentsScope.confirm(user: TestEnv.TestUser, id: String) = client.post(ApiRoutes.orderConfirm(id)) { auth(user) }
    private suspend fun PaymentsScope.cancel(user: TestEnv.TestUser, id: String) = client.post(ApiRoutes.orderCancel(id)) { auth(user) }
    private suspend fun PaymentsScope.getOrder(user: TestEnv.TestUser, id: String) = client.get(ApiRoutes.order(id)) { auth(user) }.body<Order>()

    /** Wall-clock pause (the request under test runs concurrently on the engine's threads). */
    private suspend fun realSleep(ms: Long) = withContext(Dispatchers.IO) { Thread.sleep(ms) }

    @Test
    fun aPaidOrderIsReleasedToTheSeller72HoursAfterPayment() = clocked { clock ->
        val buyer = buyer()
        val seller = seller()
        val order = paidOrder(buyer = buyer, seller = seller)
        val paidAt = clock.now

        // both parties see the deadline
        val expected = paidAt.plusSeconds(72 * 3600).toEpochMilli()
        assertEquals(expected, getOrder(buyer, order.id).autoReleaseAt)
        assertEquals(expected, getOrder(seller, order.id).autoReleaseAt)

        assertEquals(0, module.orders.releaseDueOrders())
        clock.advance(72.hours - 1.minutes)
        assertEquals(0, module.orders.releaseDueOrders())
        assertEquals(OrderStatus.PAID, orderStatus(order.id))
        assertTrue(gateway.calls("createTransfer").isEmpty())

        clock.advance(2.minutes)
        assertEquals(1, module.orders.releaseDueOrders())
        val done = getOrder(seller, order.id)
        assertEquals(OrderStatus.COMPLETED, done.status)
        assertNull(done.autoReleaseAt)
        assertEquals(ListingStatus.SOLD, listingStatus(order))
        val transfer = gateway.args<TransferRequest>("createTransfer").single()
        assertEquals(order.amountCents - order.feeCents, transfer.amountCents)
        assertEquals("cm-order-${order.id}-transfer-0", transfer.idempotencyKey)
        assertEquals(fake.transferFor(UUID.fromString(order.id)), orderRow(order.id).transferId)
        assertNull(done.dispute)

        // idempotent: nothing left to do, no second transfer; the buyer can't confirm or dispute a completed order
        clock.advance(10.hours)
        assertEquals(0, module.orders.releaseDueOrders())
        assertEquals(1, gateway.calls("createTransfer").size)
        confirm(buyer, order.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
        openDispute(buyer, order.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
    }

    @Test
    fun theHoldIsConfigurable() = clocked(autoRelease = 2.hours) { clock ->
        val buyer = buyer()
        val order = paidOrder(buyer = buyer)
        assertEquals(clock.now.plusSeconds(2 * 3600).toEpochMilli(), getOrder(buyer, order.id).autoReleaseAt)
        clock.advance(2.hours + 1.seconds)
        assertEquals(1, module.orders.releaseDueOrders())
        assertEquals(OrderStatus.COMPLETED, orderStatus(order.id))
    }

    @Test
    fun autoReleaseHoursComesFromTheEnvironment() {
        assertEquals(72.hours, PaymentOptions.fromEnv(emptyMap()).autoReleaseAfter)
        assertEquals(24.hours, PaymentOptions.fromEnv(mapOf("AUTO_RELEASE_HOURS" to "24")).autoReleaseAfter)
        assertEquals(72.hours, PaymentOptions.fromEnv(mapOf("AUTO_RELEASE_HOURS" to " ")).autoReleaseAfter)
        for (bad in listOf("0", "-1", "1.5", "soon")) {
            assertTrue(runCatching { PaymentOptions.fromEnv(mapOf("AUTO_RELEASE_HOURS" to bad)) }.isFailure, bad)
        }
    }

    @Test
    fun onlyDueOrdersAreReleasedOldestFirst() = clocked { clock ->
        val early = paidOrder()
        clock.advance(10.hours)
        val late = paidOrder()
        val pending = buyOk(buyer(), env.listing(seller()))
        clock.advance(63.hours)
        assertEquals(1, module.orders.releaseDueOrders())
        assertEquals(OrderStatus.COMPLETED, orderStatus(early.id))
        assertEquals(OrderStatus.PAID, orderStatus(late.id))
        assertEquals(OrderStatus.PENDING_PAYMENT, orderStatus(pending.id))
        clock.advance(10.hours)
        assertEquals(1, module.orders.releaseDueOrders())
        assertEquals(OrderStatus.COMPLETED, orderStatus(late.id))
    }

    @Test
    fun aDisputeFreezesTheClockAndWithdrawingRestartsIt() = clocked { clock ->
        val buyer = buyer()
        val order = paidOrder(buyer = buyer)
        clock.advance(70.hours)
        openDispute(buyer, order.id).assertStatus(HttpStatusCode.OK)
        clock.advance(500.hours)
        assertEquals(0, module.orders.releaseDueOrders())
        assertEquals(OrderStatus.DISPUTED, orderStatus(order.id))

        withdrawDispute(buyer, order.id).assertStatus(HttpStatusCode.OK)
        assertEquals(clock.now.plusSeconds(72 * 3600).toEpochMilli(), getOrder(buyer, order.id).autoReleaseAt)
        clock.advance(71.hours)
        assertEquals(0, module.orders.releaseDueOrders())
        clock.advance(2.hours)
        assertEquals(1, module.orders.releaseDueOrders())
        assertEquals(OrderStatus.COMPLETED, orderStatus(order.id))
        assertTrue(gateway.calls("refund").isEmpty())
    }

    @Test
    fun aRetryableStripeFailureIsRetriedOnTheNextRunWithTheSameKey() = clocked { clock ->
        val buyer = buyer()
        val order = paidOrder(buyer = buyer)
        clock.advance(73.hours)
        // the transfer went through but the response was lost
        gateway.failNext("createTransfer", retryable = true, afterPerforming = true)
        assertEquals(0, module.orders.releaseDueOrders())
        assertEquals(OrderStatus.PAID, orderStatus(order.id))
        assertEquals(PendingAction.TRANSFER, orderRow(order.id).pendingAction)
        // the money may already be on its way: no dispute, no refund now
        openDispute(buyer, order.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)

        assertEquals(1, module.orders.releaseDueOrders())
        assertEquals(OrderStatus.COMPLETED, orderStatus(order.id))
        val keys = gateway.args<TransferRequest>("createTransfer").map { it.idempotencyKey }
        assertEquals(listOf("cm-order-${order.id}-transfer-0", "cm-order-${order.id}-transfer-0"), keys)
        assertEquals(fake.transferFor(UUID.fromString(order.id)), orderRow(order.id).transferId)
    }

    @Test
    fun aDefinitiveStripeRejectionReleasesTheClaimAndIsRetriedWithAFreshKey() = clocked { clock ->
        val buyer = buyer()
        val order = paidOrder(buyer = buyer)
        clock.advance(73.hours)
        gateway.failNext("createTransfer", retryable = false)
        assertEquals(0, module.orders.releaseDueOrders())
        assertEquals(null, orderRow(order.id).pendingAction)
        assertEquals(1, orderRow(order.id).actionAttempt)
        assertEquals(1, module.orders.releaseDueOrders())
        assertEquals(
            listOf("cm-order-${order.id}-transfer-0", "cm-order-${order.id}-transfer-1"),
            gateway.args<TransferRequest>("createTransfer").map { it.idempotencyKey },
        )
    }

    @Test
    fun oneBadOrderDoesNotStopTheOthers() = clocked { clock ->
        val a = paidOrder()
        val b = paidOrder()
        clock.advance(73.hours)
        gateway.failNext("createTransfer", retryable = true) // hits the first one
        assertEquals(1, module.orders.releaseDueOrders())
        assertEquals(setOf(OrderStatus.PAID, OrderStatus.COMPLETED), setOf(orderStatus(a.id), orderStatus(b.id)))
        assertEquals(1, module.orders.releaseDueOrders())
        assertEquals(OrderStatus.COMPLETED, orderStatus(a.id))
        assertEquals(OrderStatus.COMPLETED, orderStatus(b.id))
    }

    @Test
    fun anOrderBeingRefundedIsNotReleased() = clocked { clock ->
        val seller = seller()
        val order = paidOrder(seller = seller)
        gateway.failNext("refund", retryable = true, afterPerforming = true)
        cancel(seller, order.id).assertApiError(HttpStatusCode.BadGateway, ErrorCodes.PAYMENT_FAILED)
        clock.advance(100.hours)
        assertEquals(0, module.orders.releaseDueOrders())
        assertTrue(gateway.calls("createTransfer").isEmpty())
        assertEquals(OrderStatus.REFUNDED, cancel(seller, order.id).body<Order>().status)
    }

    @Test
    fun theSweepRunsAutoRelease() = clocked { clock ->
        val order = paidOrder()
        clock.advance(73.hours)
        val r = module.runSweep()
        assertEquals(1, r.autoReleased)
        assertEquals(OrderStatus.COMPLETED, orderStatus(order.id))
    }

    // ---------------------------------------------------------------- races: exactly one outcome, money moves once

    @Test
    fun autoReleaseRacingTheBuyersConfirmTransfersOnce() = clocked { clock ->
        repeat(3) {
            val buyer = buyer()
            val order = paidOrder(buyer = buyer)
            clock.advance(73.hours)
            gateway.slow("createTransfer", 150)
            val confirmed = coroutineScope {
                val sweep = async { module.orders.releaseDueOrders() }
                val c = async { confirm(buyer, order.id) }
                sweep.await()
                c.await()
            }
            assertEquals(OrderStatus.COMPLETED, orderStatus(order.id))
            if (confirmed.status != HttpStatusCode.OK) confirmed.assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
            // possibly two calls, but one idempotency key: one transfer
            assertEquals(1, gateway.args<TransferRequest>("createTransfer").filter { it.orderId.toString() == order.id }.map { it.idempotencyKey }.toSet().size)
            assertTrue(gateway.calls("refund").isEmpty())
            gateway.slow("createTransfer", 0)
        }
    }

    @Test
    fun autoReleaseRacingADisputeHasExactlyOneOutcome() = clocked { clock ->
        repeat(4) { i ->
            val buyer = buyer()
            val order = paidOrder(buyer = buyer)
            clock.advance(73.hours)
            gateway.slow("createTransfer", 200)
            val (released, disputeRes) = coroutineScope {
                val sweep = async { module.orders.releaseDueOrders() }
                if (i % 2 == 0) realSleep(50) // let the sweep claim first half of the time
                val d = async { openDispute(buyer, order.id) }
                sweep.await() to d.await()
            }
            val id = UUID.fromString(order.id)
            when (orderStatus(order.id)) {
                OrderStatus.COMPLETED -> {
                    assertEquals(1, released)
                    disputeRes.assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
                    assertTrue(fake.transferFor(id) != null)
                }
                OrderStatus.DISPUTED -> {
                    assertEquals(0, released)
                    assertEquals(HttpStatusCode.OK, disputeRes.status)
                    assertNull(fake.transferFor(id))
                }
                else -> error("unexpected ${orderStatus(order.id)}")
            }
            gateway.slow("createTransfer", 0)
        }
    }

    @Test
    fun autoReleaseRacingTheSellersRefundMovesMoneyOnce() = clocked { clock ->
        repeat(4) { i ->
            val seller = seller()
            val order = paidOrder(seller = seller)
            clock.advance(73.hours)
            gateway.slow("createTransfer", 150)
            gateway.slow("refund", 150)
            val cancelRes = coroutineScope {
                val sweep = async { module.orders.releaseDueOrders() }
                if (i % 2 == 0) realSleep(50)
                val c = async { cancel(seller, order.id) }
                sweep.await()
                c.await()
            }
            val id = UUID.fromString(order.id)
            when (val final = orderStatus(order.id)) {
                OrderStatus.COMPLETED -> {
                    assertNull(fake.refundFor(id))
                    cancelRes.assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
                }
                OrderStatus.REFUNDED -> {
                    assertNull(fake.transferFor(id))
                    assertEquals(HttpStatusCode.OK, cancelRes.status)
                }
                else -> error("unexpected $final")
            }
            gateway.slow("createTransfer", 0)
            gateway.slow("refund", 0)
        }
    }

    @Test
    fun aWithdrawalJustBeforeTheSweepIsNotReleased() = clocked { clock ->
        val buyer = buyer()
        val order = paidOrder(buyer = buyer)
        openDispute(buyer, order.id).assertStatus(HttpStatusCode.OK)
        clock.advance(80.hours)
        // the sweep picked the order as due under the old clock, but the withdrawal restarted it
        withdrawDispute(buyer, order.id).assertStatus(HttpStatusCode.OK)
        val cutoff = clock.now.minusSeconds(72 * 3600)
        assertNull(env.db.txBlocking { c -> c.claimForAutoRelease(UUID.fromString(order.id), cutoff, clock.now) })
        assertEquals(0, module.orders.releaseDueOrders())
        assertEquals(OrderStatus.PAID, orderStatus(order.id))
    }

    private suspend fun HttpResponse.assertStatus(status: HttpStatusCode) = assertEquals(status, this.status)
}
