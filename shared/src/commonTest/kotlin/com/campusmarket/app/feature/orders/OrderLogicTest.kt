package com.campusmarket.app.feature.orders

import com.campusmarket.app.data.HOUR
import com.campusmarket.app.data.NOW
import com.campusmarket.app.feature.chat.TimeFormat
import com.campusmarket.core.DisputeOutcome
import com.campusmarket.core.DisputeReason
import com.campusmarket.core.OrderRole
import com.campusmarket.core.OrderRole.BUYER
import com.campusmarket.core.OrderRole.SELLER
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.PayoutAccountStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which actions each role gets in each state, and the copy around them. */
class OrderLogicTest {
    @Test
    fun actionsByRoleAndStatus() {
        fun actions(status: OrderStatus, role: OrderRole, rated: Boolean = false) = order(status, role, iHaveRated = rated).availableActions()

        assertEquals(listOf(OrderAction.Pay, OrderAction.Message, OrderAction.Cancel), actions(OrderStatus.PENDING_PAYMENT, BUYER))
        assertEquals(listOf(OrderAction.Message, OrderAction.Cancel), actions(OrderStatus.PENDING_PAYMENT, SELLER))
        // paid: the buyer confirms or reports a problem (no self-refund); only the seller can cancel and refund
        assertEquals(listOf(OrderAction.ConfirmReceipt, OrderAction.ReportProblem, OrderAction.Message), actions(OrderStatus.PAID, BUYER))
        assertEquals(listOf(OrderAction.Message, OrderAction.Cancel), actions(OrderStatus.PAID, SELLER), "only the buyer confirms")
        // disputed: the buyer can still confirm, or withdraw their own report; the seller can still refund
        assertEquals(listOf(OrderAction.ConfirmReceipt, OrderAction.Message, OrderAction.WithdrawDispute), actions(OrderStatus.DISPUTED, BUYER))
        assertEquals(listOf(OrderAction.Message, OrderAction.Cancel), actions(OrderStatus.DISPUTED, SELLER))
        assertEquals(
            listOf(OrderAction.ConfirmReceipt, OrderAction.Message),
            order(OrderStatus.DISPUTED, dispute = dispute(system = true)).availableActions(),
            "a hold placed by the platform (seller suspended) can't be withdrawn",
        )
        assertEquals(listOf(OrderAction.Rate, OrderAction.Message), actions(OrderStatus.COMPLETED, BUYER))
        assertEquals(listOf(OrderAction.Rate, OrderAction.Message), actions(OrderStatus.COMPLETED, SELLER))
        assertEquals(listOf(OrderAction.Message), actions(OrderStatus.COMPLETED, BUYER, rated = true))
        for (role in OrderRole.entries) {
            assertEquals(listOf(OrderAction.Message), actions(OrderStatus.CANCELLED, role))
            assertEquals(listOf(OrderAction.Message), actions(OrderStatus.REFUNDED, role))
        }
    }

    @Test
    fun labelsAndConfirmationsDependOnRoleAndState() {
        assertEquals("Cancel order", order(OrderStatus.PENDING_PAYMENT).actionLabel(OrderAction.Cancel))
        assertEquals("Cancel and refund buyer", order(OrderStatus.PAID, SELLER).actionLabel(OrderAction.Cancel))
        assertEquals("Cancel and refund buyer", order(OrderStatus.DISPUTED, SELLER).actionLabel(OrderAction.Cancel))
        assertEquals("Report a problem", order(OrderStatus.PAID).actionLabel(OrderAction.ReportProblem))
        assertEquals("Withdraw my report", order(OrderStatus.DISPUTED).actionLabel(OrderAction.WithdrawDispute))
        assertEquals("Message Maya", order(OrderStatus.PAID).actionLabel(OrderAction.Message))
        assertEquals("Rate Liam", order(OrderStatus.COMPLETED, SELLER).actionLabel(OrderAction.Rate))

        val confirm = assertNotNull(order(OrderStatus.PAID).confirmCopy(OrderAction.ConfirmReceipt))
        assertTrue("Maya Chen" in confirm.message && "$25" in confirm.message, confirm.message)
        assertTrue(!confirm.destructive)
        val confirmDisputed = assertNotNull(order(OrderStatus.DISPUTED).confirmCopy(OrderAction.ConfirmReceipt))
        assertTrue("closes the problem report" in confirmDisputed.message, confirmDisputed.message)
        val refund = assertNotNull(order(OrderStatus.PAID, SELLER).confirmCopy(OrderAction.Cancel))
        assertTrue(refund.destructive && "refund" in refund.message && "Liam Park" in refund.title && refund.confirmLabel == "Refund $25")
        val withdraw = assertNotNull(order(OrderStatus.DISPUTED).confirmCopy(OrderAction.WithdrawDispute))
        assertTrue(!withdraw.destructive && "72 hours" in withdraw.message, withdraw.message)
        assertNull(order(OrderStatus.PAID).confirmCopy(OrderAction.Pay))
        assertNull(order(OrderStatus.PAID).confirmCopy(OrderAction.ReportProblem), "the report form is its own screen")
        assertTrue(OrderAction.Cancel.needsConfirmation && OrderAction.ConfirmReceipt.needsConfirmation && OrderAction.WithdrawDispute.needsConfirmation)
        assertTrue(!OrderAction.Pay.needsConfirmation && !OrderAction.Message.needsConfirmation && !OrderAction.ReportProblem.needsConfirmation)
        assertEquals("Report withdrawn. Your payment is back on hold.", order(OrderStatus.PAID).actionResultMessage(OrderAction.WithdrawDispute))
        assertEquals("Order cancelled and the buyer refunded.", order(OrderStatus.REFUNDED, SELLER).actionResultMessage(OrderAction.Cancel))
    }

    private val times = TimeFormat(use24h = false, now = { NOW })

    @Test
    fun theNextStepSaysWhoActsAndWhatClockIsRunning() {
        val buyerPaid = order(OrderStatus.PAID).nextStep()
        assertEquals("Meet Maya to pick it up", buyerPaid.title)
        assertEquals(NextActor.Me, buyerPaid.actor)
        assertEquals(Deadline("Released to Maya automatically", NOW + 72 * HOUR), buyerPaid.deadline)
        val sellerPaid = order(OrderStatus.PAID, SELLER).nextStep()
        assertEquals("Hand over the item", sellerPaid.title)
        assertEquals("Paid to you automatically", sellerPaid.deadline?.label)

        val buyerPending = order(OrderStatus.PENDING_PAYMENT).nextStep()
        assertEquals(NextActor.Me, buyerPending.actor)
        assertEquals(NOW - 60_000 + 30 * 60_000, buyerPending.deadline?.at, "unpaid orders cancel with the checkout session")
        assertEquals(NextActor.Them, order(OrderStatus.PENDING_PAYMENT, SELLER).nextStep().actor)

        // no clock once disputed or settled
        assertNull(order(OrderStatus.DISPUTED).nextStep().deadline)
        assertNull(order(OrderStatus.COMPLETED).nextStep().deadline)
        assertEquals(NextActor.Nobody, order(OrderStatus.COMPLETED).nextStep().actor)
        assertTrue("weren't charged" in order(OrderStatus.CANCELLED).nextStep().body)

        // the timeline words the same deadline
        val last = order(OrderStatus.PAID).timeline(times).last()
        assertEquals("Maya paid" to StepState.Upcoming, last.title to last.state)
        assertTrue(last.detail!!.startsWith("Automatically on "), last.detail)
    }

    @Test
    fun disputedOrdersExplainTheHoldFromEachSide() {
        val buyer = order(OrderStatus.DISPUTED).nextStep()
        assertEquals(NextActor.Quad, buyer.actor)
        assertTrue("You reported a problem" in buyer.body && "withdraw" in buyer.body, buyer.body)
        val seller = order(OrderStatus.DISPUTED, SELLER).nextStep()
        assertEquals("Liam Park reported a problem", seller.title)
        assertTrue(seller.actor == NextActor.Me && "refund" in seller.body, seller.body)
        val held = order(OrderStatus.DISPUTED, dispute = dispute(system = true))
        assertTrue(held.heldByPlatform)
        assertTrue("account was suspended" in held.nextStep().body, held.nextStep().body)
        assertEquals(1, held.disputePaths().size, "only the team can settle a platform hold")
        assertEquals(3, order(OrderStatus.DISPUTED).disputePaths().size)
        assertTrue(order(OrderStatus.DISPUTED, SELLER).disputePaths()[1].startsWith("You can cancel"))
        assertTrue("on hold" in order(OrderStatus.DISPUTED).paymentNote() && "on hold" in order(OrderStatus.DISPUTED, SELLER).paymentNote())
        assertEquals(
            listOf("Order placed" to StepState.Done, "Paid" to StepState.Done, "Problem reported" to StepState.Current, "Resolved" to StepState.Upcoming),
            order(OrderStatus.DISPUTED).timeline(times).map { it.title to it.state },
        )
        assertNull(order(OrderStatus.DISPUTED).disputeOutcomeText(), "still open")
        assertEquals(BuyerDisputeReasons, listOf(DisputeReason.SELLER_NO_SHOW, DisputeReason.ITEM_NOT_AS_DESCRIBED, DisputeReason.OTHER))
    }

    @Test
    fun resolvedDisputesShowTheirOutcome() {
        val refunded = order(OrderStatus.REFUNDED, dispute = dispute(outcome = DisputeOutcome.REFUNDED_BY_MODERATOR))
        assertTrue("Our team reviewed your problem report and refunded" in refunded.nextStep().body, refunded.nextStep().body)
        assertEquals("Reviewed by our team: the buyer was refunded", refunded.disputeOutcomeText())
        val steps = refunded.timeline(times)
        assertEquals(listOf("Order placed", "Paid", "Problem reported", "Refunded"), steps.map { it.title })
        assertEquals(NOW - 5_000, steps.last().at, "refunded when the dispute closed")
        assertTrue(refunded.disputePaths().isEmpty())

        val released = order(OrderStatus.COMPLETED, SELLER, dispute = dispute(outcome = DisputeOutcome.RELEASED_BY_MODERATOR))
        assertTrue("released the payment to you" in released.nextStep().body && "$23.75" in released.nextStep().body)

        val confirmed = order(OrderStatus.COMPLETED, dispute = dispute(outcome = DisputeOutcome.BUYER_CONFIRMED))
        assertTrue(confirmed.disputeOutcomeText()!!.startsWith("You confirmed receipt"))
        val sellerRefund = order(OrderStatus.REFUNDED, dispute = dispute(outcome = DisputeOutcome.SELLER_REFUNDED))
        assertTrue(sellerRefund.disputeOutcomeText()!!.startsWith("Maya Chen cancelled and refunded you"))

        // a withdrawn report on a paid order: no "problem reported" step, but the card says it was withdrawn
        val withdrawn = order(OrderStatus.PAID, dispute = dispute(outcome = DisputeOutcome.WITHDRAWN))
        assertTrue(withdrawn.timeline(times).none { it.title == "Problem reported" })
        assertTrue(withdrawn.disputeOutcomeText()!!.startsWith("You withdrew your report"))
        assertEquals(listOf(OrderAction.ConfirmReceipt, OrderAction.ReportProblem, OrderAction.Message), withdrawn.availableActions())
    }

    @Test
    fun moneyIsUnambiguousForEachRoleAndOutcome() {
        fun rows(status: OrderStatus, role: OrderRole) = order(status, role).amountRows().map { Triple(it.label, it.cents, it.negative) }
        assertEquals(
            listOf(Triple("Item price", 2500, false), Triple("Service fee", null, false), Triple("You paid", 2500, false)),
            rows(OrderStatus.PAID, BUYER),
        )
        assertEquals(Triple("Total to pay", 2500, false), rows(OrderStatus.PENDING_PAYMENT, BUYER).last())
        assertEquals(Triple("Charged", 0, false), rows(OrderStatus.CANCELLED, BUYER).last())
        assertEquals(
            listOf(Triple("You paid", 2500, false), Triple("Refunded", 2500, true), Triple("Net cost", 0, false)),
            rows(OrderStatus.REFUNDED, BUYER).takeLast(3),
        )
        assertEquals(
            listOf(Triple("Sale price", 2500, false), Triple("Platform fee", 125, true), Triple("You'll receive", 2375, false)),
            rows(OrderStatus.PAID, SELLER),
        )
        assertEquals(Triple("Your payout (on hold)", 2375, false), rows(OrderStatus.DISPUTED, SELLER).last())
        assertEquals(Triple("Paid out to you", 2375, false), rows(OrderStatus.COMPLETED, SELLER).last())
        assertEquals(listOf(Triple("Refunded to buyer", 2500, true), Triple("Your payout", 0, false)), rows(OrderStatus.REFUNDED, SELLER).takeLast(2))
        assertEquals("You paid", order(OrderStatus.PAID).amountRows().single { it.emphasis }.label)
    }

    @Test
    fun sellerSeesPayoutAfterFee() {
        val o = order(OrderStatus.COMPLETED, SELLER)
        assertEquals(2375, o.payoutCents)
        assertTrue("$23.75" in o.nextStep().body, o.nextStep().body)
    }

    @Test
    fun paymentNoteFollowsTheStatus() {
        assertTrue("until you confirm pickup" in order(OrderStatus.PAID).paymentNote())
        assertTrue("released to Maya Chen" in order(OrderStatus.COMPLETED).paymentNote())
        assertTrue("weren't charged" in order(OrderStatus.CANCELLED).paymentNote())
        assertTrue("Refunded" in order(OrderStatus.REFUNDED).paymentNote())
        assertTrue("no payout" in order(OrderStatus.REFUNDED, SELLER).paymentNote())
    }

    @Test
    fun listHintsSayWhatsNext() {
        assertEquals(ListHint("Finish paying to reserve it", mine = true), order(OrderStatus.PENDING_PAYMENT).listHint(times))
        assertEquals(ListHint("Waiting for payment", mine = false), order(OrderStatus.PENDING_PAYMENT, SELLER).listHint(times))
        assertEquals(ListHint("Confirm when you have it · releases in 3 days", mine = true), order(OrderStatus.PAID).listHint(times))
        assertEquals(ListHint("Rate Liam", mine = true), order(OrderStatus.COMPLETED, SELLER).listHint(times))
        assertNull(order(OrderStatus.COMPLETED, iHaveRated = true).listHint(times))
        assertNull(order(OrderStatus.REFUNDED).listHint(times))
        assertEquals(true, order(OrderStatus.DISPUTED, SELLER).listHint(times)?.mine)
    }

    @Test
    fun timelineFollowsTheStateMachine() {
        fun states(status: OrderStatus) = order(status).timeline(times).map { it.title to it.state }
        assertEquals(
            listOf("Order placed" to StepState.Done, "Payment" to StepState.Current, "Picked up" to StepState.Upcoming, "Maya paid" to StepState.Upcoming),
            states(OrderStatus.PENDING_PAYMENT),
        )
        assertEquals("Picked up" to StepState.Current, states(OrderStatus.PAID)[2])
        assertTrue(states(OrderStatus.COMPLETED).all { it.second == StepState.Done })
        assertEquals("Cancelled" to StepState.Stopped, states(OrderStatus.CANCELLED).last())
        assertEquals(listOf("Order placed", "Paid", "Refunded"), states(OrderStatus.REFUNDED).map { it.first })
        assertNotNull(order(OrderStatus.PAID).timeline(times)[1].at, "paid step carries paidAt")
        assertEquals("You're paid", order(OrderStatus.PAID, SELLER).timeline(times).last().title)
    }

    @Test
    fun payoutSetupSteps() {
        val steps = payoutSteps(PayoutAccountStatus(hasAccount = true)).map { it.state }
        assertEquals(listOf(StepState.Done, StepState.Current, StepState.Upcoming), steps)
        assertEquals(
            listOf(StepState.Done, StepState.Done, StepState.Current),
            payoutSteps(PayoutAccountStatus(hasAccount = true, detailsSubmitted = true)).map { it.state },
        )
        assertEquals(StepState.Current, payoutSteps(PayoutAccountStatus(hasAccount = false)).first().state)
    }

    @Test
    fun payoutStages() {
        assertEquals(PayoutStage.NotStarted, PayoutAccountStatus(hasAccount = false).stage)
        assertEquals(PayoutStage.DetailsNeeded, PayoutAccountStatus(hasAccount = true).stage)
        assertEquals(PayoutStage.InReview, PayoutAccountStatus(hasAccount = true, detailsSubmitted = true).stage)
        assertEquals(PayoutStage.Enabled, PayoutAccountStatus(hasAccount = true, detailsSubmitted = true, payoutsEnabled = true).stage)
    }
}
