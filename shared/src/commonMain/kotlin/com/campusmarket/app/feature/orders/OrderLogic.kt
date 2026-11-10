package com.campusmarket.app.feature.orders

import com.campusmarket.app.ui.TimeFormat
import com.campusmarket.app.ui.Format
import com.campusmarket.core.DisputeOutcome
import com.campusmarket.core.DisputeReason
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.Order
import com.campusmarket.core.OrderRole
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.PayoutAccountStatus
import com.campusmarket.core.UserPublic
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process notifications about orders changed by this app (checkout created one, detail confirmed/cancelled one,
 * a rating was posted, a problem was reported), so the orders list stays current without reloading. Koin single in
 * [ordersModule].
 */
class OrderUpdates {
    private val _updates = MutableSharedFlow<Order>(extraBufferCapacity = 16)
    val updates: SharedFlow<Order> = _updates.asSharedFlow()

    fun publish(order: Order) {
        _updates.tryEmit(order)
    }
}

/**
 * The server's default hold (`AUTO_RELEASE_HOURS`), used only in copy shown before there is an order to read the
 * actual deadline from (checkout, withdrawing a report). Order screens use [Order.autoReleaseAt].
 */
internal const val DEFAULT_AUTO_RELEASE_HOURS = 72

/** Unpaid orders are cancelled when their Stripe Checkout session expires (the server asks for 31 minutes). */
internal const val PAYMENT_WINDOW_MINUTES = 30

// ---------------------------------------------------------------------------------------------------- roles

val Order.isBuyer: Boolean get() = myRole == OrderRole.BUYER
val Order.otherParty: UserPublic get() = if (isBuyer) seller else buyer

/** What the seller receives once the payment is released: amount − platform fee. */
val Order.payoutCents: Int get() = (amountCents - feeCents).coerceAtLeast(0)

internal fun firstName(user: UserPublic): String = user.displayName.trim().substringBefore(' ').ifBlank { user.displayName }

/** The open dispute on this order, if there is one. */
val Order.openDispute get() = dispute?.takeIf { it.status == DisputeStatus.OPEN }

/** The platform put the order on hold (the seller was suspended); the buyer can't withdraw that. */
val Order.heldByPlatform: Boolean get() = openDispute?.openedBySystem == true

/** When an unpaid order is cancelled automatically (approximate: the checkout session's lifetime). */
val Order.paymentDeadline: Long? get() = if (status == OrderStatus.PENDING_PAYMENT) createdAt + PAYMENT_WINDOW_MINUTES * 60_000L else null

/** Reasons a buyer can pick (SELLER_SUSPENDED is set by the platform only). */
val BuyerDisputeReasons: List<DisputeReason> = DisputeReason.entries.filter { it != DisputeReason.SELLER_SUSPENDED }

// ---------------------------------------------------------------------------------------------------- actions

/** Things the current user can do with an order. */
enum class OrderAction {
    /** Buyer, awaiting payment: reopen Stripe Checkout. */
    Pay,

    /** Buyer, paid or disputed: confirm the handoff (releases the funds to the seller). */
    ConfirmReceipt,

    /** Before payment: either party cancels. Paid or disputed: the seller cancels and refunds the buyer. */
    Cancel,

    /** Either party, completed, not rated yet. */
    Rate,

    /** Open the conversation with the other party. */
    Message,

    /** Buyer, paid: something went wrong (no-show, item not as described) → freeze the payment. */
    ReportProblem,

    /** Buyer, own open report: take it back (the payment goes back on hold with a fresh countdown). */
    WithdrawDispute,
}

/** In display order: the primary action, report a problem, Message, then withdraw / the (destructive) Cancel. */
fun Order.availableActions(): List<OrderAction> = buildList {
    when (status) {
        OrderStatus.PENDING_PAYMENT -> if (isBuyer) add(OrderAction.Pay)
        OrderStatus.PAID, OrderStatus.DISPUTED -> if (isBuyer) add(OrderAction.ConfirmReceipt)
        OrderStatus.COMPLETED -> if (!iHaveRated) add(OrderAction.Rate)
        OrderStatus.CANCELLED, OrderStatus.REFUNDED -> Unit
    }
    if (isBuyer && status == OrderStatus.PAID) add(OrderAction.ReportProblem)
    add(OrderAction.Message)
    if (isBuyer && status == OrderStatus.DISPUTED && openDispute != null && !heldByPlatform) add(OrderAction.WithdrawDispute)
    // The buyer can't refund a paid order (they may already have the item): they report a problem instead.
    val canCancel = status == OrderStatus.PENDING_PAYMENT || (!isBuyer && (status == OrderStatus.PAID || status == OrderStatus.DISPUTED))
    if (canCancel) add(OrderAction.Cancel)
}

/** Actions that need a confirmation dialog before running. */
val OrderAction.needsConfirmation: Boolean
    get() = this == OrderAction.ConfirmReceipt || this == OrderAction.Cancel || this == OrderAction.WithdrawDispute

fun Order.actionLabel(action: OrderAction): String = when (action) {
    OrderAction.Pay -> "Continue to payment"
    OrderAction.ConfirmReceipt -> "Confirm I received it"
    OrderAction.Cancel -> when {
        status == OrderStatus.PENDING_PAYMENT -> "Cancel order"
        isBuyer -> "Cancel order"
        else -> "Cancel and refund buyer"
    }
    OrderAction.Rate -> "Rate ${firstName(otherParty)}"
    OrderAction.Message -> "Message ${firstName(otherParty)}"
    OrderAction.ReportProblem -> "Report a problem"
    OrderAction.WithdrawDispute -> "Withdraw my report"
}

data class ConfirmCopy(val title: String, val message: String, val confirmLabel: String, val destructive: Boolean)

fun Order.confirmCopy(action: OrderAction): ConfirmCopy? {
    val price = Format.price(amountCents, currency)
    val item = listing.title
    return when (action) {
        OrderAction.ConfirmReceipt -> ConfirmCopy(
            title = "Confirm you received it?",
            message = (if (status == OrderStatus.DISPUTED) "This closes the problem report. " else "") +
                "Only confirm once you have “$item” in hand and you're happy with it. This releases your payment " +
                "of $price to ${seller.displayName}, and the order can no longer be refunded.",
            confirmLabel = "Yes, I received it",
            destructive = false,
        )
        OrderAction.Cancel -> when {
            status == OrderStatus.PAID || status == OrderStatus.DISPUTED -> ConfirmCopy(
                title = "Cancel and refund ${buyer.displayName}?",
                message = "${buyer.displayName} gets a full refund of $price, you won't be paid for this order, " +
                    "and “$item” goes back on sale. This can't be undone.",
                confirmLabel = "Refund $price",
                destructive = true,
            )
            isBuyer -> ConfirmCopy(
                title = "Cancel this order?",
                message = "You haven't been charged, and you won't be. “$item” goes back on sale for other students.",
                confirmLabel = "Cancel order",
                destructive = true,
            )
            else -> ConfirmCopy(
                title = "Cancel this order?",
                message = "${buyer.displayName} won't be able to finish paying, and “$item” goes back on sale.",
                confirmLabel = "Cancel order",
                destructive = true,
            )
        }
        OrderAction.WithdrawDispute -> ConfirmCopy(
            title = "Withdraw your report?",
            message = "Only do this if the problem is sorted out. Your payment stays on hold, and it's released to " +
                "${seller.displayName} automatically $DEFAULT_AUTO_RELEASE_HOURS hours from now unless you confirm receipt " +
                "sooner or report a problem again.",
            confirmLabel = "Withdraw report",
            destructive = false,
        )
        else -> null
    }
}

/** Snackbar after an action succeeded, based on the order the server returned. */
fun Order.actionResultMessage(action: OrderAction): String = when (action) {
    OrderAction.ConfirmReceipt -> "Thanks! Payment released to ${seller.displayName}."
    OrderAction.Cancel -> when (status) {
        OrderStatus.REFUNDED -> if (isBuyer) "Order cancelled. Your refund is on its way." else "Order cancelled and the buyer refunded."
        OrderStatus.PAID -> "The payment came through just now, so the order wasn't cancelled."
        else -> "Order cancelled."
    }
    OrderAction.WithdrawDispute -> "Report withdrawn. Your payment is back on hold."
    else -> ""
}

// ---------------------------------------------------------------------------------------------------- next step

/** Who has to do something next (the badge on the order's "what happens next" card). */
enum class NextActor { Me, Them, Quad, Nobody }

enum class InfoTone { Info, Warning, Neutral, Success }

/** A deadline that runs without anyone acting: "Releases to Maya automatically" + when. */
data class Deadline(val label: String, val at: Long)

/** Where the order stands from my side, in plain words: headline, who acts, what happens, and any clock running. */
data class NextStep(
    val title: String,
    val actor: NextActor,
    val body: String,
    val tone: InfoTone,
    val deadline: Deadline? = null,
)

/** The headline card of the order detail screen. */
fun Order.nextStep(): NextStep {
    val sellerName = seller.displayName
    val buyerName = buyer.displayName
    val outcome = dispute?.outcome
    return when (status) {
        OrderStatus.PENDING_PAYMENT -> if (isBuyer) {
            NextStep(
                "Finish paying to reserve it",
                NextActor.Me,
                "Checkout opens in your browser. Nothing is charged until you finish, and then your money is held " +
                    "by Quad until you've picked the item up.",
                InfoTone.Warning,
                paymentDeadline?.let { Deadline("Cancels automatically if unpaid", it) },
            )
        } else {
            NextStep(
                "Waiting for ${firstName(buyer)} to pay",
                NextActor.Them,
                "$buyerName is checking out. If they don't finish, the order is cancelled and your listing goes back on sale.",
                InfoTone.Info,
                paymentDeadline?.let { Deadline("Cancels automatically if unpaid", it) },
            )
        }
        OrderStatus.PAID -> if (isBuyer) {
            NextStep(
                "Meet ${firstName(seller)} to pick it up",
                NextActor.Me,
                "Your payment is held by Quad. Once you have the item and you're happy with it, confirm below so " +
                    "$sellerName gets paid. If something's wrong, report a problem before the payment is released.",
                InfoTone.Info,
                autoReleaseAt?.let { Deadline("Released to ${firstName(seller)} automatically", it) },
            )
        } else {
            NextStep(
                "Hand over the item",
                NextActor.Me,
                "$buyerName has paid, and Quad holds the money. Meet up and hand it over, then ask ${firstName(buyer)} " +
                    "to tap “Confirm I received it” in the app. That pays you straight away.",
                InfoTone.Info,
                autoReleaseAt?.let { Deadline("Paid to you automatically", it) },
            )
        }
        OrderStatus.DISPUTED -> when {
            heldByPlatform && isBuyer -> NextStep(
                "Your payment is on hold",
                NextActor.Quad,
                "$sellerName's account was suspended, so our team is reviewing this order. They'll either refund you " +
                    "or release the payment. If you already have the item and you're happy with it, you can confirm receipt.",
                InfoTone.Warning,
            )
            heldByPlatform -> NextStep(
                "Payment on hold",
                NextActor.Quad,
                "This payment is on hold while our team reviews the order. You'll see the decision here.",
                InfoTone.Warning,
            )
            isBuyer -> NextStep(
                "Your payment is on hold",
                NextActor.Quad,
                "You reported a problem, so $sellerName won't be paid while it's open. Try to sort it out in chat; " +
                    "if you can't, our team reviews the order and decides. You can withdraw your report anytime.",
                InfoTone.Warning,
            )
            else -> NextStep(
                "$buyerName reported a problem",
                NextActor.Me,
                "Your payout is on hold while the report is open. Message ${firstName(buyer)} to sort it out, or refund " +
                    "them; if you can't agree, our team reviews the order and decides.",
                InfoTone.Warning,
            )
        }
        OrderStatus.COMPLETED -> when {
            outcome == DisputeOutcome.RELEASED_BY_MODERATOR && isBuyer -> NextStep(
                "Order complete",
                NextActor.Nobody,
                "Our team reviewed your problem report and released the payment to $sellerName.",
                InfoTone.Success,
            )
            outcome == DisputeOutcome.RELEASED_BY_MODERATOR -> NextStep(
                "You've been paid",
                NextActor.Nobody,
                "Our team reviewed the problem report and released the payment to you. " +
                    "${Format.price(payoutCents, currency)} is on its way to your payout account.",
                InfoTone.Success,
            )
            isBuyer -> NextStep("Order complete", NextActor.Nobody, "You have the item and $sellerName has been paid. Enjoy!", InfoTone.Success)
            else -> NextStep(
                "You've been paid",
                NextActor.Nobody,
                "${Format.price(payoutCents, currency)} is on its way to your payout account.",
                InfoTone.Success,
            )
        }
        OrderStatus.CANCELLED -> NextStep(
            "Order cancelled",
            NextActor.Nobody,
            if (isBuyer) "It was cancelled before payment, so you weren't charged." else "It was cancelled before payment. No money changed hands.",
            InfoTone.Neutral,
        )
        OrderStatus.REFUNDED -> NextStep(
            "Refunded",
            NextActor.Nobody,
            when {
                outcome == DisputeOutcome.REFUNDED_BY_MODERATOR && isBuyer ->
                    "Our team reviewed your problem report and refunded ${Format.price(amountCents, currency)} to your " +
                        "original payment method. Refunds can take 5–10 business days to appear."
                outcome == DisputeOutcome.REFUNDED_BY_MODERATOR -> "Our team reviewed the problem report and refunded $buyerName in full, so there's no payout for this order."
                isBuyer -> "${Format.price(amountCents, currency)} was refunded to your original payment method. Refunds can take 5–10 business days to appear."
                else -> "$buyerName was refunded in full, so there's no payout for this order."
            },
            InfoTone.Neutral,
        )
    }
}

/** Short status line for the orders list ("Confirm when you have it"); [mine] = I'm the one who has to act. */
data class ListHint(val text: String, val mine: Boolean)

fun Order.listHint(times: TimeFormat): ListHint? = when (status) {
    OrderStatus.PENDING_PAYMENT -> if (isBuyer) ListHint("Finish paying to reserve it", mine = true) else ListHint("Waiting for payment", mine = false)
    OrderStatus.PAID -> if (isBuyer) {
        ListHint("Confirm when you have it" + autoReleaseAt?.let { " · releases ${times.countdownShort(it)}" }.orEmpty(), mine = true)
    } else {
        ListHint("Hand over the item" + autoReleaseAt?.let { " · paid out ${times.countdownShort(it)}" }.orEmpty(), mine = true)
    }
    OrderStatus.DISPUTED -> ListHint(if (heldByPlatform || isBuyer) "Payment on hold · under review" else "Problem reported · payout on hold", mine = !isBuyer && !heldByPlatform)
    OrderStatus.COMPLETED -> if (!iHaveRated) ListHint("Rate ${firstName(otherParty)}", mine = true) else null
    OrderStatus.CANCELLED, OrderStatus.REFUNDED -> null
}

// ---------------------------------------------------------------------------------------------------- money

/** One line of the payment breakdown. [emphasis] = the total; [negative] = money going back / taken off. */
data class AmountRow(val label: String, val cents: Int?, val text: String? = null, val emphasis: Boolean = false, val negative: Boolean = false)

/**
 * The money, by role and status. Buyer: item, fee, then what they paid (and the refund, netting to zero). Seller:
 * sale price, platform fee, then the payout and whether it's coming, on hold, sent or not happening.
 */
fun Order.amountRows(): List<AmountRow> = if (isBuyer) {
    val head = listOf(AmountRow("Item price", amountCents), AmountRow("Service fee", null, text = "Free"))
    head + when (status) {
        OrderStatus.PENDING_PAYMENT -> listOf(AmountRow("Total to pay", amountCents, emphasis = true))
        OrderStatus.CANCELLED -> listOf(AmountRow("Charged", 0, emphasis = true))
        OrderStatus.REFUNDED -> listOf(
            AmountRow("You paid", amountCents),
            AmountRow("Refunded", amountCents, negative = true),
            AmountRow("Net cost", 0, emphasis = true),
        )
        else -> listOf(AmountRow("You paid", amountCents, emphasis = true))
    }
} else {
    val sale = AmountRow("Sale price", amountCents)
    val fee = AmountRow("Platform fee", feeCents, negative = true)
    when (status) {
        OrderStatus.PENDING_PAYMENT, OrderStatus.PAID -> listOf(sale, fee, AmountRow("You'll receive", payoutCents, emphasis = true))
        OrderStatus.DISPUTED -> listOf(sale, fee, AmountRow("Your payout (on hold)", payoutCents, emphasis = true))
        OrderStatus.COMPLETED -> listOf(sale, fee, AmountRow("Paid out to you", payoutCents, emphasis = true))
        OrderStatus.CANCELLED -> listOf(sale, AmountRow("Your payout", 0, emphasis = true))
        OrderStatus.REFUNDED -> listOf(sale, AmountRow("Refunded to buyer", amountCents, negative = true), AmountRow("Your payout", 0, emphasis = true))
    }
}

/** Fine print under the payment breakdown, by role and status. */
fun Order.paymentNote(): String = if (isBuyer) {
    when (status) {
        OrderStatus.PENDING_PAYMENT -> "Nothing is charged until you finish Stripe Checkout."
        OrderStatus.PAID -> "Paid securely with Stripe. Quad holds your money until you confirm pickup or report a problem."
        OrderStatus.DISPUTED -> "Your payment stays on hold until the problem is sorted out. If it's refunded, it goes back to your original payment method."
        OrderStatus.COMPLETED -> "Paid with Stripe and released to ${seller.displayName}."
        OrderStatus.CANCELLED -> "Cancelled before payment: you weren't charged."
        OrderStatus.REFUNDED -> "Refunded in full to your original payment method. Refunds can take 5–10 business days to appear."
    }
} else {
    val fee = "The platform fee covers card processing and buyer protection."
    when (status) {
        OrderStatus.PENDING_PAYMENT -> "$fee You're paid when the buyer confirms pickup, or automatically $DEFAULT_AUTO_RELEASE_HOURS hours after they pay unless they report a problem."
        OrderStatus.PAID -> "$fee You're paid when the buyer confirms pickup, or automatically when the hold ends unless they report a problem."
        OrderStatus.DISPUTED -> "$fee Your payout is on hold until the reported problem is resolved."
        OrderStatus.COMPLETED -> "$fee Your payout was sent to the account you set up in Payouts."
        OrderStatus.CANCELLED, OrderStatus.REFUNDED -> "This order was cancelled, so there's no payout."
    }
}

// ---------------------------------------------------------------------------------------------------- disputes

/** How the dispute ended, for the dispute card ("Withdrawn by the buyer"). Null while open. */
fun Order.disputeOutcomeText(): String? {
    val outcome = dispute?.outcome ?: return null
    return when (outcome) {
        DisputeOutcome.WITHDRAWN -> if (isBuyer) "You withdrew your report" else "${buyer.displayName} withdrew the report"
        DisputeOutcome.BUYER_CONFIRMED -> if (isBuyer) "You confirmed receipt, so the payment was released" else "${buyer.displayName} confirmed receipt and you were paid"
        DisputeOutcome.SELLER_REFUNDED -> if (isBuyer) "${seller.displayName} cancelled and refunded you" else "You cancelled and refunded the buyer"
        DisputeOutcome.REFUNDED_BY_MODERATOR -> "Reviewed by our team: the buyer was refunded"
        DisputeOutcome.RELEASED_BY_MODERATOR -> "Reviewed by our team: the payment was released to the seller"
        DisputeOutcome.REFUNDED -> "The payment was refunded"
    }
}

/** While a report is open: the ways it can end, in the order people usually take them. */
fun Order.disputePaths(): List<String> {
    val d = openDispute ?: return emptyList()
    if (d.openedBySystem) return listOf("Our team refunds the buyer or releases the payment to the seller")
    val sellerFirst = firstName(seller)
    return if (isBuyer) {
        listOf(
            "Sorted out? Confirm receipt to pay $sellerFirst, or withdraw your report",
            "$sellerFirst can cancel the order and refund you",
            "If you can't agree, our team reviews it and refunds you or pays $sellerFirst",
        )
    } else {
        listOf(
            "${firstName(buyer)} can withdraw the report or confirm receipt, and you're paid",
            "You can cancel the order and refund them",
            "If you can't agree, our team reviews it and refunds the buyer or pays you",
        )
    }
}

// ---------------------------------------------------------------------------------------------------- timeline

enum class StepState { Done, Current, Upcoming, Stopped }

data class TimelineStep(val title: String, val at: Long?, val state: StepState, val detail: String? = null)

/**
 * Placed → Paid → Picked up → Paid out, with a "Problem reported" step when disputed and ending early in
 * Cancelled / Refunded. [times] words the automatic deadlines.
 */
fun Order.timeline(times: TimeFormat): List<TimelineStep> {
    val placed = TimelineStep("Order placed", createdAt, StepState.Done)
    val paid = TimelineStep("Paid", paidAt, StepState.Done, "Held by Quad until pickup")
    val reported = dispute?.takeIf { it.outcome != DisputeOutcome.WITHDRAWN }?.let {
        TimelineStep(if (it.openedBySystem) "Put on hold" else "Problem reported", it.openedAt, StepState.Done, it.reason.label)
    }
    val sellerFirst = firstName(seller)
    return when (status) {
        OrderStatus.PENDING_PAYMENT -> listOf(
            placed,
            TimelineStep("Payment", null, StepState.Current, if (isBuyer) "Waiting for you to pay" else "Waiting for ${firstName(buyer)} to pay"),
            TimelineStep("Picked up", null, StepState.Upcoming),
            TimelineStep(if (isBuyer) "$sellerFirst paid" else "You're paid", null, StepState.Upcoming),
        )
        OrderStatus.PAID -> listOf(
            placed,
            paid,
            TimelineStep("Picked up", null, StepState.Current, if (isBuyer) "Confirm once you have the item" else "Waiting for ${firstName(buyer)} to confirm"),
            TimelineStep(
                if (isBuyer) "$sellerFirst paid" else "You're paid",
                null,
                StepState.Upcoming,
                autoReleaseAt?.let { "Automatically ${times.dateTimeInline(it)} if not confirmed sooner" },
            ),
        )
        OrderStatus.DISPUTED -> listOf(
            placed,
            paid.copy(detail = null),
            (reported ?: TimelineStep("Problem reported", null, StepState.Done)).copy(state = StepState.Current, detail = "Payment on hold until it's resolved"),
            TimelineStep("Resolved", null, StepState.Upcoming, "Between you, or decided by our team"),
        )
        OrderStatus.COMPLETED -> listOfNotNull(
            placed,
            paid.copy(detail = null),
            reported,
            TimelineStep(if (isBuyer) "$sellerFirst paid" else "You were paid", completedAt, StepState.Done, "Order complete"),
        )
        OrderStatus.CANCELLED -> listOf(
            placed,
            TimelineStep("Cancelled", null, StepState.Stopped, "Before payment: nothing was charged"),
        )
        OrderStatus.REFUNDED -> listOfNotNull(
            placed,
            paid.copy(detail = null),
            reported,
            TimelineStep("Refunded", dispute?.closedAt?.takeIf { reported != null }, StepState.Stopped, "Refunded in full to the buyer"),
        )
    }
}

// ---------------------------------------------------------------------------------------------------- payouts

enum class PayoutStage { NotStarted, DetailsNeeded, InReview, Enabled }

val PayoutAccountStatus.stage: PayoutStage
    get() = when {
        payoutsEnabled -> PayoutStage.Enabled
        !hasAccount -> PayoutStage.NotStarted
        detailsSubmitted -> PayoutStage.InReview
        else -> PayoutStage.DetailsNeeded
    }
