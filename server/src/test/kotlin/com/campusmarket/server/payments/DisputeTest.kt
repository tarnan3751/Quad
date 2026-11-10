package com.campusmarket.server.payments

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CreateReportRequest
import com.campusmarket.core.DisputeCase
import com.campusmarket.core.DisputeOutcome
import com.campusmarket.core.DisputeReason
import com.campusmarket.core.DisputeResolution
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ModerationAction
import com.campusmarket.core.ModerationLogEntry
import com.campusmarket.core.Order
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.Page
import com.campusmarket.core.ReportReason
import com.campusmarket.core.ReportTargetType
import com.campusmarket.core.UserRole
import com.campusmarket.server.TestEnv
import com.campusmarket.server.auth
import com.campusmarket.server.json
import com.campusmarket.server.query
import com.campusmarket.server.queryOne
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Buyer disputes, their effect on the order, and moderators resolving them. */
class DisputeTest {

    private suspend fun PaymentsScope.confirm(user: TestEnv.TestUser, id: String) = client.post(ApiRoutes.orderConfirm(id)) { auth(user) }
    private suspend fun PaymentsScope.cancel(user: TestEnv.TestUser, id: String) = client.post(ApiRoutes.orderCancel(id)) { auth(user) }
    private suspend fun PaymentsScope.getOrder(user: TestEnv.TestUser, id: String) = client.get(ApiRoutes.order(id)) { auth(user) }.body<Order>()
    private suspend fun PaymentsScope.case(mod: TestEnv.TestUser, orderId: String) = client.get(ApiRoutes.modDispute(orderId)) { auth(mod) }

    private suspend fun PaymentsScope.queue(mod: TestEnv.TestUser, query: String = ""): Page<DisputeCase> {
        val res = client.get("${ApiRoutes.MOD_DISPUTES}$query") { auth(mod) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return res.body()
    }

    private fun PaymentsScope.modLog(): List<Triple<String, String, String>> = env.db.txBlocking { c ->
        c.query("SELECT action, target_type, note FROM moderation_actions ORDER BY created_at") { Triple(it.getString(1), it.getString(2), it.getString(3)) }
    }

    private fun PaymentsScope.disputeRows(orderId: String): Int =
        env.db.txBlocking { c -> c.queryOne("SELECT count(*) FROM order_disputes WHERE order_id = ?", UUID.fromString(orderId)) { it.getInt(1) }!! }

    // ---------------------------------------------------------------- opening

    @Test
    fun buyerReportsAProblemAndThePaymentIsFrozen() = paymentsTest {
        val buyer = buyer()
        val seller = seller()
        val order = paidOrder(buyer = buyer, seller = seller)
        assertNotNull(getOrder(buyer, order.id).autoReleaseAt)

        val res = openDispute(buyer, order.id, DisputeReason.ITEM_NOT_AS_DESCRIBED, "  The screen is\u0000 cracked  ")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val disputed = res.body<Order>()
        assertEquals(OrderStatus.DISPUTED, disputed.status)
        assertNull(disputed.autoReleaseAt, "a dispute freezes the auto-release clock")
        val d = assertNotNull(disputed.dispute)
        assertEquals(DisputeReason.ITEM_NOT_AS_DESCRIBED, d.reason)
        assertEquals("The screen is cracked", d.details)
        assertEquals(DisputeStatus.OPEN, d.status)
        assertNull(d.outcome)
        assertFalse(d.openedBySystem)
        assertEquals(ListingStatus.RESERVED, listingStatus(order))

        // the seller sees the same dispute
        val sellerView = getOrder(seller, order.id)
        assertEquals(OrderStatus.DISPUTED, sellerView.status)
        assertEquals(d, sellerView.dispute)
        // no money moved
        assertTrue(gateway.calls("createTransfer").isEmpty())
        assertTrue(gateway.calls("refund").isEmpty())
    }

    @Test
    fun onlyTheBuyerOfAPaidOrderCanReportAProblem() = paymentsTest {
        val buyer = buyer()
        val seller = seller()
        val order = paidOrder(buyer = buyer, seller = seller)
        openDispute(seller, order.id).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        openDispute(buyer(), order.id).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        openDispute(buyer, "nope").assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        assertEquals(HttpStatusCode.Unauthorized, client.post(ApiRoutes.orderDispute(order.id)).status)

        // validation
        openDispute(buyer, order.id, DisputeReason.SELLER_SUSPENDED, "").assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
            .also { assertNotNull(it.fields["reason"]) }
        openDispute(buyer, order.id, DisputeReason.OTHER, "   ").assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
            .also { assertNotNull(it.fields["details"]) }
        openDispute(buyer, order.id, DisputeReason.SELLER_NO_SHOW, "x".repeat(1001)).assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
            .also { assertNotNull(it.fields["details"]) }
        assertEquals(OrderStatus.PAID, orderStatus(order.id))

        // details are optional except for OTHER
        assertEquals(HttpStatusCode.OK, openDispute(buyer, order.id, DisputeReason.SELLER_NO_SHOW, "").status)
        openDispute(buyer, order.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)

        // not before payment, not after completion
        val pending = buyOk(buyer, env.listing(seller))
        openDispute(buyer, pending.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
        val done = paidOrder(buyer = buyer, seller = seller)
        assertEquals(HttpStatusCode.OK, confirm(buyer, done.id).status)
        openDispute(buyer, done.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
    }

    @Test
    fun aDisputeCantBeOpenedWhileThePaymentIsBeingReleased() = paymentsTest {
        val buyer = buyer()
        val order = paidOrder(buyer = buyer)
        // the buyer confirmed; the transfer went through but its response was lost: the release is still claimed
        gateway.failNext("createTransfer", retryable = true, afterPerforming = true)
        confirm(buyer, order.id).assertApiError(HttpStatusCode.BadGateway, ErrorCodes.PAYMENT_FAILED)
        openDispute(buyer, order.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
        assertEquals(OrderStatus.PAID, orderStatus(order.id))
        assertEquals(OrderStatus.COMPLETED, confirm(buyer, order.id).body<Order>().status)
    }

    // ---------------------------------------------------------------- the parties while DISPUTED

    @Test
    fun buyerCanWithdrawAndTheAutoReleaseClockStartsOver() = paymentsTest(
        options = PaymentOptions(expiryJobEnabled = false, clock = MutableClock()),
    ) {
        val clock = module.options.clock as MutableClock
        val buyer = buyer()
        val seller = seller()
        val order = paidOrder(buyer = buyer, seller = seller)
        val firstDeadline = getOrder(buyer, order.id).autoReleaseAt!!
        openDispute(buyer, order.id).assertStatus(HttpStatusCode.OK)

        withdrawDispute(seller, order.id).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        withdrawDispute(buyer(), order.id).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)

        clock.advance(kotlin.time.Duration.parse("10h"))
        val res = withdrawDispute(buyer, order.id)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val back = res.body<Order>()
        assertEquals(OrderStatus.PAID, back.status)
        assertEquals(DisputeStatus.CLOSED, back.dispute!!.status)
        assertEquals(DisputeOutcome.WITHDRAWN, back.dispute!!.outcome)
        assertNotNull(back.dispute!!.closedAt)
        assertEquals(clock.now.plusSeconds(72 * 3600).toEpochMilli(), back.autoReleaseAt)
        assertTrue(back.autoReleaseAt!! > firstDeadline)

        // nothing to withdraw any more
        withdrawDispute(buyer, order.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
        assertEquals(ListingStatus.RESERVED, listingStatus(order))
    }

    @Test
    fun aBuyerCanReopenButOnlyAFewTimes() = paymentsTest {
        val buyer = buyer()
        val order = paidOrder(buyer = buyer)
        repeat(MAX_DISPUTES_PER_ORDER) {
            openDispute(buyer, order.id).assertStatus(HttpStatusCode.OK)
            withdrawDispute(buyer, order.id).assertStatus(HttpStatusCode.OK)
        }
        openDispute(buyer, order.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
        assertEquals(OrderStatus.PAID, orderStatus(order.id))
        assertEquals(MAX_DISPUTES_PER_ORDER, disputeRows(order.id))
    }

    @Test
    fun buyerCanStillConfirmReceiptWhileDisputed() = paymentsTest {
        val buyer = buyer()
        val seller = seller()
        val order = paidOrder(buyer = buyer, seller = seller)
        openDispute(buyer, order.id).assertStatus(HttpStatusCode.OK)
        confirm(seller, order.id).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)

        val done = confirm(buyer, order.id).body<Order>()
        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(DisputeOutcome.BUYER_CONFIRMED, done.dispute!!.outcome)
        assertEquals(ListingStatus.SOLD, listingStatus(order))
        assertEquals(1, gateway.calls("createTransfer").size)
        assertEquals(DisputeOutcome.BUYER_CONFIRMED, getOrder(seller, order.id).dispute!!.outcome)
    }

    @Test
    fun onlyTheSellerCanRefundADisputedOrder() = paymentsTest {
        val buyer = buyer()
        val seller = seller()
        val order = paidOrder(buyer = buyer, seller = seller)
        openDispute(buyer, order.id).assertStatus(HttpStatusCode.OK)
        cancel(buyer, order.id).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)

        val refunded = cancel(seller, order.id).body<Order>()
        assertEquals(OrderStatus.REFUNDED, refunded.status)
        assertEquals(DisputeOutcome.SELLER_REFUNDED, refunded.dispute!!.outcome)
        assertEquals(ListingStatus.ACTIVE, listingStatus(order))
        assertEquals(1, gateway.calls("refund").size)
        assertTrue(gateway.calls("createTransfer").isEmpty())
        withdrawDispute(buyer, order.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
    }

    @Test
    fun aRefundFromTheProcessorDashboardClosesTheDispute() = paymentsTest {
        val buyer = buyer()
        val order = paidOrder(buyer = buyer)
        openDispute(buyer, order.id).assertStatus(HttpStatusCode.OK)
        val pi = orderRow(order.id).paymentIntentId!!
        module.webhooks.handle(
            PaymentEvent.ChargeRefunded("evt_dash_refund", "charge.refunded", Instant.now(), "ch_1", pi, UUID.fromString(order.id), fullyRefunded = true),
        )
        val o = getOrder(buyer, order.id)
        assertEquals(OrderStatus.REFUNDED, o.status)
        assertEquals(DisputeOutcome.REFUNDED, o.dispute!!.outcome)
        assertEquals(ListingStatus.ACTIVE, listingStatus(order))
    }

    @Test
    fun aDisputedOrderStillHoldsItsListing() = paymentsTest {
        val buyer = buyer()
        val seller = seller()
        val listing = env.listing(seller)
        val order = paidOrder(buyer = buyer, seller = seller, listingId = listing)
        openDispute(buyer, order.id).assertStatus(HttpStatusCode.OK)
        // nobody else can buy it, and it can't be put back on sale behind the dispute's back
        buy(buyer(), listing).assertApiError(HttpStatusCode.Conflict, ErrorCodes.LISTING_UNAVAILABLE)
        env.db.txBlocking { c -> c.releaseListing(listing) }
        assertEquals(ListingStatus.RESERVED, listingStatus(listing))
        // the one-live-order-per-listing index covers DISPUTED
        val err = runCatching {
            sql(
                "INSERT INTO orders (id, listing_id, buyer_id, seller_id, amount_cents, fee_cents, status) VALUES (?, ?, ?, ?, 1, 0, 'PAID')",
                UUID.randomUUID(), listing, buyer.id, seller.id,
            )
        }.exceptionOrNull()
        assertNotNull(err)
    }

    // ---------------------------------------------------------------- moderators

    @Test
    fun moderatorsSeeTheQueueAndOthersDont() = paymentsTest {
        val mod = moderator()
        val admin = env.user("boss", role = UserRole.ADMIN)
        val buyer = buyer()
        val seller = seller()
        val first = paidOrder(buyer = buyer, seller = seller)
        val second = paidOrder(buyer = buyer, seller = seller)
        openDispute(buyer, second.id).assertStatus(HttpStatusCode.OK)
        openDispute(buyer, first.id, DisputeReason.OTHER, "Wrong colour").assertStatus(HttpStatusCode.OK)

        // regular users (also the parties) get 403
        assertEquals(HttpStatusCode.Forbidden, client.get(ApiRoutes.MOD_DISPUTES) { auth(buyer) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get(ApiRoutes.modDispute(first.id)) { auth(seller) }.status)
        resolveDispute(buyer, first.id, DisputeResolution.REFUND_BUYER).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)

        // open queue: oldest first, with everything a moderator needs
        val open = queue(mod)
        assertEquals(listOf(second.id, first.id), open.items.map { it.orderId })
        val c = open.items.last()
        assertEquals(buyer.id.toString(), c.buyer.id)
        assertEquals(seller.id.toString(), c.seller.id)
        assertEquals(first.listing.id, c.listing.id)
        assertEquals(first.amountCents, c.amountCents)
        assertEquals(first.feeCents, c.feeCents)
        assertEquals(OrderStatus.DISPUTED, c.orderStatus)
        assertEquals(DisputeReason.OTHER, c.dispute.reason)
        assertEquals("Wrong colour", c.dispute.details)
        assertNotNull(c.paidAt)
        assertFalse(c.sellerSuspended)
        assertEquals(listOf(second.id, first.id), queue(admin).items.map { it.orderId })

        // paging
        val p1 = queue(mod, "?limit=1")
        assertEquals(listOf(second.id), p1.items.map { it.orderId })
        val p2 = queue(mod, "?limit=1&cursor=${p1.nextCursor!!}")
        assertEquals(listOf(first.id), p2.items.map { it.orderId })
        assertNull(p2.nextCursor)

        assertEquals(HttpStatusCode.BadRequest, client.get("${ApiRoutes.MOD_DISPUTES}?status=WHATEVER") { auth(mod) }.status)
        assertEquals(emptyList(), queue(mod, "?status=CLOSED").items)
        case(mod, UUID.randomUUID().toString()).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        case(mod, "nope").assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        assertEquals(first.id, case(mod, first.id).body<DisputeCase>().orderId)
    }

    @Test
    fun moderatorRefundsTheBuyer() = paymentsTest {
        val mod = moderator()
        val buyer = buyer()
        val seller = seller()
        val order = paidOrder(buyer = buyer, seller = seller)
        openDispute(buyer, order.id).assertStatus(HttpStatusCode.OK)

        // a note is required
        resolveDispute(mod, order.id, DisputeResolution.REFUND_BUYER, note = "  ").assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
            .also { assertNotNull(it.fields["note"]) }
        assertEquals(OrderStatus.DISPUTED, orderStatus(order.id))

        val res = resolveDispute(mod, order.id, DisputeResolution.REFUND_BUYER, note = "Seller admitted the no-show in chat")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val resolved = res.body<DisputeCase>()
        assertEquals(OrderStatus.REFUNDED, resolved.orderStatus)
        assertEquals(DisputeStatus.CLOSED, resolved.dispute.status)
        assertEquals(DisputeOutcome.REFUNDED_BY_MODERATOR, resolved.dispute.outcome)
        assertEquals(mod.id.toString(), resolved.resolvedBy?.id)
        assertEquals("Seller admitted the no-show in chat", resolved.resolutionNote)
        assertEquals(ListingStatus.ACTIVE, listingStatus(order))
        assertEquals(1, gateway.calls("refund").size)
        assertTrue(gateway.calls("createTransfer").isEmpty())

        // both parties see the outcome, but not the moderator's note or identity
        for (party in listOf(buyer, seller)) {
            val raw = client.get(ApiRoutes.order(order.id)) { auth(party) }.bodyAsText()
            assertFalse("admitted" in raw, raw)
            assertFalse(mod.id.toString() in raw, raw)
            assertEquals(DisputeOutcome.REFUNDED_BY_MODERATOR, getOrder(party, order.id).dispute!!.outcome)
        }

        // audit row in the moderation log
        assertEquals(
            listOf(Triple("RESOLVE_DISPUTE", "ORDER", "Refunded the buyer: Seller admitted the no-show in chat")),
            modLog(),
        )
        val log = client.get(ApiRoutes.MOD_LOG) { auth(mod) }.body<Page<ModerationLogEntry>>().items.single()
        assertEquals(ModerationAction.RESOLVE_DISPUTE, log.action)
        assertEquals(ReportTargetType.ORDER, log.targetType)
        assertEquals(order.id, log.targetId)
        assertEquals(mod.id.toString(), log.moderator.id)

        // closed now: out of the queue, into the history; can't be resolved twice
        assertEquals(emptyList(), queue(mod).items)
        assertEquals(listOf(order.id), queue(mod, "?status=CLOSED").items.map { it.orderId })
        resolveDispute(mod, order.id, DisputeResolution.RELEASE_TO_SELLER).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
        assertEquals(1, modLog().size)
    }

    @Test
    fun moderatorReleasesThePaymentToTheSeller() = paymentsTest {
        val admin = env.user("admin1", role = UserRole.ADMIN)
        val buyer = buyer()
        val seller = seller("sel")
        val order = paidOrder(buyer = buyer, seller = seller)
        openDispute(buyer, order.id).assertStatus(HttpStatusCode.OK)

        val resolved = resolveDispute(admin, order.id, DisputeResolution.RELEASE_TO_SELLER, "Photos show the handoff").body<DisputeCase>()
        assertEquals(OrderStatus.COMPLETED, resolved.orderStatus)
        assertEquals(DisputeOutcome.RELEASED_BY_MODERATOR, resolved.dispute.outcome)
        assertEquals(ListingStatus.SOLD, listingStatus(order))
        val transfer = gateway.args<TransferRequest>("createTransfer").single()
        assertEquals(order.amountCents - order.feeCents, transfer.amountCents)
        assertEquals("acct_test_sel", transfer.destinationAccountId)
        assertTrue(gateway.calls("refund").isEmpty())
        assertEquals(listOf("RESOLVE_DISPUTE"), modLog().map { it.first })
        assertEquals(DisputeOutcome.RELEASED_BY_MODERATOR, getOrder(seller, order.id).dispute!!.outcome)
        // a completed order can then be rated as usual
        assertEquals(HttpStatusCode.OK, client.post(ApiRoutes.orderRating(order.id)) { auth(buyer); json(com.campusmarket.core.CreateRatingRequest(3)) }.status)
    }

    @Test
    fun moderatorsCantResolveTheirOwnOrdersOrClosedDisputes() = paymentsTest {
        val modBuyer = env.user("modbuyer", role = UserRole.MODERATOR)
        val order = paidOrder(buyer = modBuyer)
        openDispute(modBuyer, order.id).assertStatus(HttpStatusCode.OK)
        resolveDispute(modBuyer, order.id, DisputeResolution.REFUND_BUYER).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        assertEquals(OrderStatus.DISPUTED, orderStatus(order.id))

        val mod = moderator()
        val plain = paidOrder()
        resolveDispute(mod, plain.id, DisputeResolution.REFUND_BUYER).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        resolveDispute(mod, UUID.randomUUID().toString(), DisputeResolution.REFUND_BUYER).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)

        // withdrawn: no longer open
        val buyer = buyer()
        val w = paidOrder(buyer = buyer)
        openDispute(buyer, w.id).assertStatus(HttpStatusCode.OK)
        withdrawDispute(buyer, w.id).assertStatus(HttpStatusCode.OK)
        resolveDispute(mod, w.id, DisputeResolution.REFUND_BUYER).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
        assertEquals(OrderStatus.PAID, orderStatus(w.id))
        assertTrue(modLog().isEmpty())
        assertTrue(gateway.calls("refund").isEmpty())
    }

    @Test
    fun aFailedRefundCanBeRetriedByTheModerator() = paymentsTest {
        val mod = moderator()
        val buyer = buyer()
        val order = paidOrder(buyer = buyer)
        openDispute(buyer, order.id).assertStatus(HttpStatusCode.OK)
        gateway.failNext("refund", retryable = true, afterPerforming = true)
        resolveDispute(mod, order.id, DisputeResolution.REFUND_BUYER).assertApiError(HttpStatusCode.BadGateway, ErrorCodes.PAYMENT_FAILED)
        assertEquals(OrderStatus.DISPUTED, orderStatus(order.id))
        assertTrue(modLog().isEmpty(), "nothing is logged until the outcome is applied")
        // while the refund's outcome is unknown nobody can release the money or withdraw
        confirm(buyer, order.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
        withdrawDispute(buyer, order.id).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
        resolveDispute(mod, order.id, DisputeResolution.RELEASE_TO_SELLER).assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)

        assertEquals(OrderStatus.REFUNDED, resolveDispute(mod, order.id, DisputeResolution.REFUND_BUYER).body<DisputeCase>().orderStatus)
        assertEquals(1, gateway.args<RefundRequest>("refund").map { it.idempotencyKey }.toSet().size)
        assertTrue(gateway.calls("createTransfer").isEmpty())
        assertEquals(1, modLog().size)
    }

    @Test
    fun concurrentModeratorRefundAndBuyerConfirmMoveMoneyOnce() = paymentsTest {
        val mod = moderator()
        repeat(3) {
            val buyer = buyer()
            val seller = seller()
            val order = paidOrder(buyer = buyer, seller = seller)
            openDispute(buyer, order.id).assertStatus(HttpStatusCode.OK)
            gateway.slow("createTransfer", 150)
            gateway.slow("refund", 150)
            val (r1, r2) = coroutineScope {
                listOf(
                    async { resolveDispute(mod, order.id, DisputeResolution.REFUND_BUYER) },
                    async { confirm(buyer, order.id) },
                ).awaitAll()
            }
            assertTrue(HttpStatusCode.OK in setOf(r1.status, r2.status), "${r1.status} ${r2.status}")
            val id = UUID.fromString(order.id)
            when (val final = orderStatus(order.id)) {
                OrderStatus.REFUNDED -> assertNull(fake.transferFor(id))
                OrderStatus.COMPLETED -> assertNull(fake.refundFor(id))
                else -> error("unexpected $final")
            }
            for (r in listOf(r1, r2)) if (r.status != HttpStatusCode.OK) r.assertApiError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)
        }
    }

    @Test
    fun ordersCantBeReportedThroughTheReportEndpoint() = paymentsTest {
        val buyer = buyer()
        val order = paidOrder(buyer = buyer)
        val res = client.post(ApiRoutes.REPORTS) { auth(buyer); json(CreateReportRequest(ReportTargetType.ORDER, order.id, ReportReason.SCAM)) }
        res.assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
    }

    private suspend fun io.ktor.client.statement.HttpResponse.assertStatus(status: HttpStatusCode) =
        assertEquals(status, this.status, bodyAsText())
}
