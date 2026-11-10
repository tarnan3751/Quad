package com.campusmarket.app.feature.orders

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

/** "Mar 4, 2025 · 14:05", when a PAID order's payment is released to the seller automatically. */
fun Order.autoReleaseText(): String? = autoReleaseAt?.let(Format::dateTime)

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

/** Fine print under the payment breakdown, by role and status. */
fun Order.paymentNote(): String = if (isBuyer) {
    when (status) {
        OrderStatus.PENDING_PAYMENT -> "Nothing is charged until you finish Stripe Checkout."
        OrderStatus.PAID -> {
            val release = autoReleaseText()?.let { " It's released to ${seller.displayName} automatically on $it unless you report a problem first." }.orEmpty()
            "Paid securely with Stripe. Your money is held until you confirm pickup.$release"
        }
        OrderStatus.DISPUTED -> "Your payment stays on hold until the problem is sorted out. If it's refunded, it goes back to your original payment method."
        OrderStatus.COMPLETED -> "Paid with Stripe and released to ${seller.displayName}."
        OrderStatus.CANCELLED -> "Cancelled before payment: you weren't charged."
        OrderStatus.REFUNDED -> "Refunded in full to your original payment method. Refunds can take 5–10 business days to appear."
    }
} else {
    val fee = "The platform fee covers card processing and buyer protection."
    when (status) {
        OrderStatus.PENDING_PAYMENT -> "$fee You're paid when the buyer confirms pickup, or automatically $DEFAULT_AUTO_RELEASE_HOURS hours after they pay unless they report a problem."
        OrderStatus.PAID -> {
            val release = autoReleaseText()?.let { "or automatically on $it unless they report a problem" } ?: "or automatically a few days after payment"
            "$fee You're paid when the buyer confirms pickup, $release."
        }
        OrderStatus.DISPUTED -> "$fee Your payout is on hold until the reported problem is resolved."
        OrderStatus.COMPLETED -> "$fee Your payout was sent to the account you set up in Payouts."
        OrderStatus.CANCELLED, OrderStatus.REFUNDED -> "This order was cancelled, so there's no payout."
    }
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
                    "and “$item” goes back on sale.",
                confirmLabel = "Refund buyer",
                destructive = true,
            )
            isBuyer -> ConfirmCopy(
                title = "Cancel this order?",
                message = "You haven't been charged. “$item” goes back on sale for other students.",
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

// ---------------------------------------------------------------------------------------------------- status copy

/** One-paragraph explanation of where the order stands, from my point of view. */
fun Order.statusExplanation(): String {
    val seller = seller.displayName
    val buyer = buyer.displayName
    val outcome = dispute?.outcome
    return when (status) {
        OrderStatus.PENDING_PAYMENT -> if (isBuyer) {
            "Finish paying in Stripe Checkout to reserve this item. Unpaid orders are cancelled automatically after 30 minutes."
        } else {
            "Waiting for $buyer to pay. Unpaid orders are cancelled automatically after 30 minutes, and your listing goes back on sale."
        }
        OrderStatus.PAID -> {
            val release = autoReleaseText()
            if (isBuyer) {
                "Your payment is held securely. Meet $seller to pick up the item, then tap “Confirm I received it” to release it. " +
                    (release?.let { "Funds release to the seller on $it unless you report a problem." } ?: "If something goes wrong, report a problem.")
            } else {
                "$buyer has paid. The money is held until they confirm pickup. Hand over the item and ask them to confirm in the app. " +
                    (release?.let { "Funds release to you on $it unless $buyer reports a problem." }.orEmpty())
            }
        }
        OrderStatus.DISPUTED -> when {
            heldByPlatform && isBuyer ->
                "$seller's account was suspended, so your payment is on hold. Our team will review the order and either refund you " +
                    "or release the payment. If you already have the item and you're happy with it, you can confirm receipt."
            heldByPlatform -> "This payment is on hold while our team reviews the order."
            isBuyer ->
                "You reported a problem, so your payment is on hold and won't go to $seller for now. If it gets sorted out, withdraw " +
                    "your report or confirm receipt; $seller can also cancel and refund you. Otherwise our team will review the order " +
                    "and refund you or release the payment."
            else ->
                "$buyer reported a problem, so the payment is on hold. Talk to $buyer to sort it out: they can withdraw the report or " +
                    "confirm receipt, or you can cancel and refund them. Otherwise our team will review the order and refund the buyer " +
                    "or release the payment to you."
        }
        OrderStatus.COMPLETED -> when {
            outcome == DisputeOutcome.RELEASED_BY_MODERATOR && isBuyer ->
                "Our team reviewed your problem report and released the payment to $seller."
            outcome == DisputeOutcome.RELEASED_BY_MODERATOR ->
                "Our team reviewed the problem report and released the payment to you. ${Format.price(payoutCents, currency)} is on its way to your payout account."
            isBuyer -> "This order is complete: the payment was released to $seller."
            else -> "This order is complete. ${Format.price(payoutCents, currency)} is on its way to your payout account."
        }
        OrderStatus.CANCELLED -> "This order was cancelled before payment. No money changed hands."
        OrderStatus.REFUNDED -> when {
            outcome == DisputeOutcome.REFUNDED_BY_MODERATOR && isBuyer ->
                "Our team reviewed your problem report and refunded ${Format.price(amountCents, currency)} to your original payment method."
            outcome == DisputeOutcome.REFUNDED_BY_MODERATOR -> "Our team reviewed the problem report and refunded $buyer in full."
            isBuyer -> "This order was cancelled and ${Format.price(amountCents, currency)} was refunded to your original payment method."
            else -> "This order was cancelled after payment and $buyer was refunded in full."
        }
    }
}

/** What happened to the dispute, for the dispute card ("Withdrawn by the buyer · Mar 4"). Null while open. */
fun Order.disputeOutcomeText(): String? {
    val d = dispute ?: return null
    val outcome = d.outcome ?: return null
    val text = when (outcome) {
        DisputeOutcome.WITHDRAWN -> if (isBuyer) "You withdrew your report" else "${buyer.displayName} withdrew the report"
        DisputeOutcome.BUYER_CONFIRMED -> if (isBuyer) "You confirmed receipt, so the payment was released" else "${buyer.displayName} confirmed receipt and you were paid"
        DisputeOutcome.SELLER_REFUNDED -> if (isBuyer) "${seller.displayName} cancelled and refunded you" else "You cancelled and refunded the buyer"
        DisputeOutcome.REFUNDED_BY_MODERATOR -> "Reviewed by our team: the buyer was refunded"
        DisputeOutcome.RELEASED_BY_MODERATOR -> "Reviewed by our team: the payment was released to the seller"
        DisputeOutcome.REFUNDED -> "The payment was refunded"
    }
    return d.closedAt?.let { "$text · ${Format.dateTime(it)}" } ?: text
}

// ---------------------------------------------------------------------------------------------------- timeline

enum class StepState { Done, Current, Upcoming, Stopped }

data class TimelineStep(val title: String, val at: Long?, val state: StepState, val detail: String? = null)

/** PENDING_PAYMENT → PAID → COMPLETED, ending early in CANCELLED / REFUNDED, with a "problem reported" step if disputed. */
fun Order.timeline(): List<TimelineStep> {
    val placed = TimelineStep("Order placed", createdAt, StepState.Done)
    val reported = dispute?.takeIf { it.outcome != DisputeOutcome.WITHDRAWN }?.let {
        TimelineStep("Problem reported", it.openedAt, StepState.Done, it.reason.label)
    }
    return when (status) {
        OrderStatus.PENDING_PAYMENT -> listOf(
            placed,
            TimelineStep("Payment", null, StepState.Current, if (isBuyer) "Waiting for you to pay" else "Waiting for the buyer to pay"),
            TimelineStep("Pickup confirmed", null, StepState.Upcoming),
        )
        OrderStatus.PAID -> listOf(
            placed,
            TimelineStep("Paid", paidAt, StepState.Done, "Funds held until pickup"),
            TimelineStep(
                "Pickup confirmed",
                null,
                StepState.Current,
                listOfNotNull(
                    if (isBuyer) "Confirm once you have the item" else "Waiting for the buyer to confirm",
                    autoReleaseText()?.let { "released automatically on $it" },
                ).joinToString(", or "),
            ),
        )
        OrderStatus.DISPUTED -> listOf(
            placed,
            TimelineStep("Paid", paidAt, StepState.Done),
            TimelineStep("Problem reported", dispute?.openedAt, StepState.Current, "Payment on hold until it's resolved"),
        )
        OrderStatus.COMPLETED -> listOfNotNull(
            placed,
            TimelineStep("Paid", paidAt, StepState.Done),
            reported,
            TimelineStep("Completed", completedAt, StepState.Done, "Payment released to the seller"),
        )
        OrderStatus.CANCELLED -> listOf(
            placed,
            TimelineStep("Cancelled", null, StepState.Stopped, "Cancelled before payment"),
        )
        OrderStatus.REFUNDED -> listOfNotNull(
            placed,
            TimelineStep("Paid", paidAt, StepState.Done),
            reported,
            TimelineStep("Refunded", null, StepState.Stopped, "Refunded in full to the buyer"),
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
