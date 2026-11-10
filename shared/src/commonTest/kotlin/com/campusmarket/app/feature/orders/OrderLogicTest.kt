package com.campusmarket.app.feature.orders

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
        assertTrue(refund.destructive && "refund" in refund.message && "Liam Park" in refund.title)
        val withdraw = assertNotNull(order(OrderStatus.DISPUTED).confirmCopy(OrderAction.WithdrawDispute))
        assertTrue(!withdraw.destructive && "72 hours" in withdraw.message, withdraw.message)
        assertNull(order(OrderStatus.PAID).confirmCopy(OrderAction.Pay))
        assertNull(order(OrderStatus.PAID).confirmCopy(OrderAction.ReportProblem), "the report form is its own screen")
        assertTrue(OrderAction.Cancel.needsConfirmation && OrderAction.ConfirmReceipt.needsConfirmation && OrderAction.WithdrawDispute.needsConfirmation)
        assertTrue(!OrderAction.Pay.needsConfirmation && !OrderAction.Message.needsConfirmation && !OrderAction.ReportProblem.needsConfirmation)
        assertEquals("Report withdrawn. Your payment is back on hold.", order(OrderStatus.PAID).actionResultMessage(OrderAction.WithdrawDispute))
        assertEquals("Order cancelled and the buyer refunded.", order(OrderStatus.REFUNDED, SELLER).actionResultMessage(OrderAction.Cancel))
    }

    @Test
    fun theAutoReleaseDeadlineIsExplainedToBothSides() {
        val buyerView = order(OrderStatus.PAID)
        val deadline = assertNotNull(buyerView.autoReleaseText())
        assertTrue("Funds release to the seller on $deadline unless you report a problem" in buyerView.statusExplanation(), buyerView.statusExplanation())
        val sellerView = order(OrderStatus.PAID, SELLER)
        assertTrue("Funds release to you on $deadline unless Liam Park reports a problem" in sellerView.statusExplanation(), sellerView.statusExplanation())
        assertTrue(deadline in buyerView.paymentNote() && deadline in sellerView.paymentNote())
        assertTrue(buyerView.timeline().last().detail!!.contains("released automatically on $deadline"))
        // no deadline once disputed or settled
        assertNull(order(OrderStatus.DISPUTED).autoReleaseText())
        assertNull(order(OrderStatus.COMPLETED).autoReleaseText())
    }

    @Test
    fun disputedOrdersExplainTheHoldFromEachSide() {
        val buyer = order(OrderStatus.DISPUTED)
        assertTrue("You reported a problem" in buyer.statusExplanation() && "withdraw" in buyer.statusExplanation())
        val seller = order(OrderStatus.DISPUTED, SELLER)
        assertTrue("Liam Park reported a problem" in seller.statusExplanation() && "refund" in seller.statusExplanation())
        val held = order(OrderStatus.DISPUTED, dispute = dispute(system = true))
        assertTrue(held.heldByPlatform)
        assertTrue("account was suspended" in held.statusExplanation(), held.statusExplanation())
        assertTrue("on hold" in buyer.paymentNote() && "on hold" in seller.paymentNote())
        assertEquals("Problem reported" to StepState.Current, buyer.timeline().last().let { it.title to it.state })
        assertNull(buyer.disputeOutcomeText(), "still open")
        assertEquals(BuyerDisputeReasons, listOf(DisputeReason.SELLER_NO_SHOW, DisputeReason.ITEM_NOT_AS_DESCRIBED, DisputeReason.OTHER))
    }

    @Test
    fun resolvedDisputesShowTheirOutcome() {
        val refunded = order(OrderStatus.REFUNDED, dispute = dispute(outcome = DisputeOutcome.REFUNDED_BY_MODERATOR))
        assertTrue("Our team reviewed your problem report and refunded" in refunded.statusExplanation(), refunded.statusExplanation())
        assertTrue(refunded.disputeOutcomeText()!!.startsWith("Reviewed by our team: the buyer was refunded"))
        assertEquals(listOf("Order placed", "Paid", "Problem reported", "Refunded"), refunded.timeline().map { it.title })

        val released = order(OrderStatus.COMPLETED, SELLER, dispute = dispute(outcome = DisputeOutcome.RELEASED_BY_MODERATOR))
        assertTrue("released the payment to you" in released.statusExplanation() && "$23.75" in released.statusExplanation())

        val confirmed = order(OrderStatus.COMPLETED, dispute = dispute(outcome = DisputeOutcome.BUYER_CONFIRMED))
        assertTrue(confirmed.disputeOutcomeText()!!.startsWith("You confirmed receipt"))
        val sellerRefund = order(OrderStatus.REFUNDED, dispute = dispute(outcome = DisputeOutcome.SELLER_REFUNDED))
        assertTrue(sellerRefund.disputeOutcomeText()!!.startsWith("Maya Chen cancelled and refunded you"))

        // a withdrawn report on a paid order: no "problem reported" step, but the card says it was withdrawn
        val withdrawn = order(OrderStatus.PAID, dispute = dispute(outcome = DisputeOutcome.WITHDRAWN))
        assertTrue(withdrawn.timeline().none { it.title == "Problem reported" })
        assertTrue(withdrawn.disputeOutcomeText()!!.startsWith("You withdrew your report"))
        assertEquals(listOf(OrderAction.ConfirmReceipt, OrderAction.ReportProblem, OrderAction.Message), withdrawn.availableActions())
    }

    @Test
    fun sellerSeesPayoutAfterFee() {
        val o = order(OrderStatus.COMPLETED, SELLER)
        assertEquals(2375, o.payoutCents)
        assertTrue("$23.75" in o.statusExplanation(), o.statusExplanation())
    }

    @Test
    fun paymentNoteFollowsTheStatus() {
        assertTrue("held until you confirm" in order(OrderStatus.PAID).paymentNote())
        assertTrue("released to Maya Chen" in order(OrderStatus.COMPLETED).paymentNote())
        assertTrue("weren't charged" in order(OrderStatus.CANCELLED).paymentNote())
        assertTrue("Refunded" in order(OrderStatus.REFUNDED).paymentNote())
        assertTrue("no payout" in order(OrderStatus.REFUNDED, SELLER).paymentNote())
    }

    @Test
    fun timelineFollowsTheStateMachine() {
        fun states(status: OrderStatus) = order(status).timeline().map { it.title to it.state }
        assertEquals(
            listOf("Order placed" to StepState.Done, "Payment" to StepState.Current, "Pickup confirmed" to StepState.Upcoming),
            states(OrderStatus.PENDING_PAYMENT),
        )
        assertEquals(StepState.Current, states(OrderStatus.PAID).last().second)
        assertTrue(states(OrderStatus.COMPLETED).all { it.second == StepState.Done })
        assertEquals("Cancelled" to StepState.Stopped, states(OrderStatus.CANCELLED).last())
        assertEquals(listOf("Order placed", "Paid", "Refunded"), states(OrderStatus.REFUNDED).map { it.first })
        assertNotNull(order(OrderStatus.PAID).timeline()[1].at, "paid step carries paidAt")
    }

    @Test
    fun payoutStages() {
        assertEquals(PayoutStage.NotStarted, PayoutAccountStatus(hasAccount = false).stage)
        assertEquals(PayoutStage.DetailsNeeded, PayoutAccountStatus(hasAccount = true).stage)
        assertEquals(PayoutStage.InReview, PayoutAccountStatus(hasAccount = true, detailsSubmitted = true).stage)
        assertEquals(PayoutStage.Enabled, PayoutAccountStatus(hasAccount = true, detailsSubmitted = true, payoutsEnabled = true).stage)
    }
}
