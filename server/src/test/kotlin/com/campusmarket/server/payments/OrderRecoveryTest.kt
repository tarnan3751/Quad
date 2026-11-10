package com.campusmarket.server.payments

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.Order
import com.campusmarket.core.OrderStatus
import com.campusmarket.server.TestEnv
import com.campusmarket.server.auth
import com.campusmarket.server.update
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Crashes, lost responses, races and the background sweep. */
class OrderRecoveryTest {

    private suspend fun HttpResponse.assertError(status: HttpStatusCode, code: String) {
        assertEquals(status, this.status, bodyAsText())
        assertEquals(code, body<ErrorResponse>().code)
    }

    private suspend fun PaymentsScope.confirm(user: TestEnv.TestUser, id: String) = client.post(ApiRoutes.orderConfirm(id)) { auth(user) }
    private suspend fun PaymentsScope.cancel(user: TestEnv.TestUser, id: String) = client.post(ApiRoutes.orderCancel(id)) { auth(user) }

    @Test
    fun confirmRetriedAfterTransferSucceededDoesNotPayTwice() = paymentsTest {
        val buyer = buyer()
        val seller = seller()
        val order = paidOrder(buyer = buyer, seller = seller)

        // the transfer goes through but the response is lost
        gateway.failNext("createTransfer", retryable = true, afterPerforming = true)
        confirm(buyer, order.id).assertError(HttpStatusCode.BadGateway, ErrorCodes.PAYMENT_FAILED)
        assertEquals(OrderStatus.PAID, orderStatus(order.id))
        assertEquals(PendingAction.TRANSFER, orderRow(order.id).pendingAction)

        // the seller can't slip a refund in while the transfer's outcome is unknown
        cancel(seller, order.id).assertError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
        assertTrue(gateway.calls("refund").isEmpty())

        // retry: same idempotency key, the one transfer is reused, order completes
        assertEquals(OrderStatus.COMPLETED, confirm(buyer, order.id).body<Order>().status)
        val transfers = gateway.args<TransferRequest>("createTransfer")
        assertEquals(2, transfers.size)
        assertEquals(1, transfers.map { it.idempotencyKey }.toSet().size)
        assertEquals(fake.transferFor(UUID.fromString(order.id)), orderRow(order.id).transferId)
        assertEquals(null, orderRow(order.id).pendingAction)
    }

    @Test
    fun definitiveTransferFailureReleasesTheClaim() = paymentsTest {
        val buyer = buyer()
        val seller = seller()
        val order = paidOrder(buyer = buyer, seller = seller)

        gateway.failNext("createTransfer", retryable = false)
        confirm(buyer, order.id).assertError(HttpStatusCode.BadGateway, ErrorCodes.PAYMENT_FAILED)
        val row = orderRow(order.id)
        assertEquals(OrderStatus.PAID, row.status)
        assertEquals(null, row.pendingAction)
        assertEquals(1, row.actionAttempt)

        // a later confirm uses a fresh idempotency key …
        assertEquals(OrderStatus.COMPLETED, confirm(buyer, order.id).body<Order>().status)
        assertEquals(
            listOf("cm-order-${order.id}-transfer-0", "cm-order-${order.id}-transfer-1"),
            gateway.args<TransferRequest>("createTransfer").map { it.idempotencyKey },
        )
    }

    @Test
    fun afterDefinitiveTransferFailureTheOrderCanBeRefunded() = paymentsTest {
        val buyer = buyer()
        val seller = seller()
        val order = paidOrder(buyer = buyer, seller = seller)
        gateway.failNext("createTransfer", retryable = false)
        confirm(buyer, order.id)
        assertEquals(OrderStatus.REFUNDED, cancel(seller, order.id).body<Order>().status)
        assertEquals("cm-order-${order.id}-refund-1", gateway.args<RefundRequest>("refund").single().idempotencyKey)
    }

    @Test
    fun refundRetriedAfterLostResponseCompletes() = paymentsTest {
        val buyer = buyer()
        val seller = seller()
        val order = paidOrder(buyer = buyer, seller = seller)
        gateway.failNext("refund", retryable = true, afterPerforming = true)
        cancel(seller, order.id).assertError(HttpStatusCode.BadGateway, ErrorCodes.PAYMENT_FAILED)
        assertEquals(PendingAction.REFUND, orderRow(order.id).pendingAction)
        // the buyer can't confirm (transfer) while a refund may be under way
        confirm(buyer, order.id).assertError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)

        assertEquals(OrderStatus.REFUNDED, cancel(seller, order.id).body<Order>().status)
        assertEquals(1, gateway.args<RefundRequest>("refund").map { it.idempotencyKey }.toSet().size)
        assertTrue(gateway.calls("createTransfer").isEmpty())
    }

    @Test
    fun concurrentConfirmAndCancelMoveMoneyOnlyOnce() = paymentsTest {
        repeat(3) {
            val buyer = buyer()
            val seller = seller()
            val order = paidOrder(buyer = buyer, seller = seller)
            gateway.slow("createTransfer", 150)
            gateway.slow("refund", 150)

            val (c1, c2) = coroutineScope {
                listOf(async { confirm(buyer, order.id) }, async { cancel(seller, order.id) }).awaitAll()
            }
            val statuses = setOf(c1.status, c2.status)
            assertTrue(HttpStatusCode.OK in statuses, "one of them succeeds: $statuses")

            val id = UUID.fromString(order.id)
            val final = orderStatus(order.id)
            when (final) {
                OrderStatus.COMPLETED -> assertEquals(null, fake.refundFor(id))
                OrderStatus.REFUNDED -> assertEquals(null, fake.transferFor(id))
                else -> error("unexpected final state $final")
            }
            if (c1.status != HttpStatusCode.OK) c1.assertError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
            if (c2.status != HttpStatusCode.OK) c2.assertError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
        }
    }

    @Test
    fun sellerCancellingAPendingOrderThatWasJustPaidRefundsIt() = paymentsTest {
        val seller = seller()
        val listing = env.listing(seller)
        val order = buyOk(buyer(), listing)
        // buyer paid on the processor's side, but the webhook hasn't arrived
        fake.pay(orderRow(order.id).sessionId!!)

        val res = cancel(seller, order.id)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals(OrderStatus.REFUNDED, res.body<Order>().status)
        assertEquals(1, gateway.calls("refund").size)
        assertEquals(ListingStatus.ACTIVE, listingStatus(listing))
    }

    /** A buyer can't refund a paid order, not even by racing their own payment with a cancel: it stays PAID. */
    @Test
    fun buyerCancellingAPendingOrderThatWasJustPaidLeavesItPaid() = paymentsTest {
        val buyer = buyer()
        val listing = env.listing(seller())
        val order = buyOk(buyer, listing)
        fake.pay(orderRow(order.id).sessionId!!)

        val res = cancel(buyer, order.id)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals(OrderStatus.PAID, res.body<Order>().status)
        assertTrue(gateway.calls("refund").isEmpty())
        assertEquals(ListingStatus.RESERVED, listingStatus(listing))
    }

    @Test
    fun viewingAnOrderCatchesUpWithAMissedWebhook() = paymentsTest {
        val buyer = buyer()
        val order = buyOk(buyer, env.listing(seller()))
        val sessionId = orderRow(order.id).sessionId!!
        fake.pay(sessionId)

        val seen = client.get(ApiRoutes.order(order.id)) { auth(buyer) }.body<Order>()
        assertEquals(OrderStatus.PAID, seen.status)
        assertEquals("pi_fake_${order.id.replace("-", "")}", orderRow(order.id).paymentIntentId)
        // the late webhook is then a no-op
        assertEquals(
            WebhookService.Result.PROCESSED,
            module.webhooks.handle(PaymentEvent.CheckoutCompleted("evt_late", "checkout.session.completed", java.time.Instant.now(), sessionId, null, "pi_other", true)),
        )
        assertEquals("pi_fake_${order.id.replace("-", "")}", orderRow(order.id).paymentIntentId)
    }

    @Test
    fun viewingAnOrderWhoseSessionExpiredCancelsIt() = paymentsTest {
        val buyer = buyer()
        val listing = env.listing(seller())
        val order = buyOk(buyer, listing)
        fake.expireCheckoutSession(orderRow(order.id).sessionId!!, "test")
        assertEquals(OrderStatus.CANCELLED, client.get(ApiRoutes.order(order.id)) { auth(buyer) }.body<Order>().status)
        assertEquals(ListingStatus.ACTIVE, listingStatus(listing))
    }

    @Test
    fun gatewayOutageDoesNotBreakViewingOrders() = paymentsTest {
        val buyer = buyer()
        val order = buyOk(buyer, env.listing(seller()))
        gateway.failNext("retrieveCheckoutSession")
        val res = client.get(ApiRoutes.order(order.id)) { auth(buyer) }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(OrderStatus.PENDING_PAYMENT, res.body<Order>().status)
    }

    @Test
    fun cancelFailsCleanlyWhenTheCheckoutCantBeExpired() = paymentsTest {
        val buyer = buyer()
        val listing = env.listing(seller())
        val order = buyOk(buyer, listing)
        gateway.failNext("expireCheckoutSession")
        cancel(buyer, order.id).assertError(HttpStatusCode.BadGateway, ErrorCodes.PAYMENT_FAILED)
        assertEquals(OrderStatus.PENDING_PAYMENT, orderStatus(order.id))
        assertEquals(ListingStatus.RESERVED, listingStatus(listing))
        assertEquals(OrderStatus.CANCELLED, cancel(buyer, order.id).body<Order>().status)
    }

    @Test
    fun sweepCancelsOnlyStalePendingOrders() = paymentsTest {
        val staleListing = env.listing(seller())
        val stale = buyOk(buyer(), staleListing)
        val fresh = buyOk(buyer(), env.listing(seller()))
        val paidStale = paidOrder()
        age(stale.id, 36)
        age(paidStale.id, 60)
        age(fresh.id, 20)

        assertEquals(1, module.orders.expireStaleOrders())
        assertEquals(OrderStatus.CANCELLED, orderStatus(stale.id))
        assertEquals(ListingStatus.ACTIVE, listingStatus(staleListing))
        assertEquals(OrderStatus.PENDING_PAYMENT, orderStatus(fresh.id))
        assertEquals(OrderStatus.PAID, orderStatus(paidStale.id))
        assertEquals(listOf(orderRow(stale.id).sessionId), gateway.args<Pair<String, String>>("expireCheckoutSession").map { it.first })
        assertEquals(0, module.orders.expireStaleOrders())
    }

    @Test
    fun sweepTurnsALatePaymentIntoPaidInsteadOfCancelling() = paymentsTest {
        val order = buyOk(buyer(), env.listing(seller()))
        fake.pay(orderRow(order.id).sessionId!!)
        age(order.id, 40)
        assertEquals(1, module.orders.expireStaleOrders())
        assertEquals(OrderStatus.PAID, orderStatus(order.id))
    }

    @Test
    fun sweepSurvivesGatewayErrorsAndRetriesNextTime() = paymentsTest {
        val a = buyOk(buyer(), env.listing(seller()))
        val b = buyOk(buyer(), env.listing(seller()))
        age(a.id, 50)
        age(b.id, 40)
        gateway.failNext("expireCheckoutSession") // hits the oldest (a)
        assertEquals(1, module.orders.expireStaleOrders())
        assertEquals(OrderStatus.PENDING_PAYMENT, orderStatus(a.id))
        assertEquals(OrderStatus.CANCELLED, orderStatus(b.id))
        assertEquals(1, module.orders.expireStaleOrders())
        assertEquals(OrderStatus.CANCELLED, orderStatus(a.id))
    }

    @Test
    fun backgroundJobRunsOnItsInterval() = paymentsTest(options = PaymentOptions(expiryJobEnabled = true, expiryJobInterval = 50.milliseconds)) {
        val listing = env.listing(seller())
        val order = buyOk(buyer(), listing)
        age(order.id, 36)
        var waited = 0
        while (orderStatus(order.id) != OrderStatus.CANCELLED && waited < 5_000) {
            delay(25); waited += 25
        }
        assertEquals(OrderStatus.CANCELLED, orderStatus(order.id))
        assertEquals(ListingStatus.ACTIVE, listingStatus(listing))
    }

    @Test
    fun backgroundJobStopsWithTheApplication() {
        lateinit var orderId: String
        paymentsTest(options = PaymentOptions(expiryJobEnabled = true, expiryJobInterval = 20.milliseconds)) {
            orderId = buyOk(buyer(), env.listing(seller())).id
        }
        // the application is gone; a leaked sweep would cancel this now-stale order within milliseconds
        TestEnv.db.txBlocking { c ->
            c.update("UPDATE orders SET created_at = now() - interval '1 hour' WHERE id = ?", UUID.fromString(orderId))
        }
        Thread.sleep(300)
        val status = TestEnv.db.txBlocking { c -> c.findOrder(UUID.fromString(orderId))!!.status }
        assertEquals(OrderStatus.PENDING_PAYMENT, status)
    }
}
