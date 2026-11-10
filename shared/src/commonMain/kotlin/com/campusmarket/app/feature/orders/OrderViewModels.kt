package com.campusmarket.app.feature.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.platform.ExternalLinks
import com.campusmarket.app.ui.fieldErrorsOf
import com.campusmarket.app.ui.state.EventQueue
import com.campusmarket.app.ui.state.Paginator
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.app.ui.state.load
import com.campusmarket.app.ui.state.valueOrNull
import com.campusmarket.app.ui.toFormErrors
import com.campusmarket.core.Conversation
import com.campusmarket.core.CreateRatingRequest
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Limits
import com.campusmarket.core.Order
import com.campusmarket.core.OrderRole
import com.campusmarket.core.OrderStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// ---------------------------------------------------------------------------------------------------- list

/** Orders list with Buying / Selling tabs, each its own paged list (loaded when first shown). */
class OrdersViewModel(
    route: OrdersRoute,
    private val api: ApiClient,
    updates: OrderUpdates,
    userMessages: UserMessages,
) : ViewModel() {

    val buying = Paginator(viewModelScope, key = { it.id }) { cursor, limit -> api.orders(OrderRole.BUYER, cursor, limit) }
    val selling = Paginator(viewModelScope, key = { it.id }) { cursor, limit -> api.orders(OrderRole.SELLER, cursor, limit) }

    private val _tab = MutableStateFlow(if (route.role.equals("seller", ignoreCase = true)) OrderRole.SELLER else OrderRole.BUYER)
    val tab: StateFlow<OrderRole> = _tab.asStateFlow()

    private var resumedOnce = false

    init {
        buying.onRefreshError = userMessages::showError
        selling.onRefreshError = userMessages::showError
        viewModelScope.launch { updates.updates.collect(::apply) }
    }

    fun selectTab(role: OrderRole) {
        _tab.value = role
    }

    fun paginator(role: OrderRole) = if (role == OrderRole.BUYER) buying else selling

    /** Payment happens in the browser: when we come back, refresh a list that shows unpaid orders. */
    fun onResume() {
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        listOf(buying, selling).forEach { p ->
            val s = p.state.value
            if (s.loaded && s.items.any { it.status == OrderStatus.PENDING_PAYMENT }) p.refresh()
        }
    }

    private fun apply(order: Order) {
        val p = paginator(order.myRole)
        if (!p.state.value.loaded) return
        p.update { list ->
            if (list.any { it.id == order.id }) list.map { if (it.id == order.id) order else it } else listOf(order) + list
        }
    }
}

// ---------------------------------------------------------------------------------------------------- detail

data class OrderDetailUi(
    /** Action in flight (button spinner). */
    val running: OrderAction? = null,
    /** Action waiting for confirmation (dialog shown). */
    val confirming: OrderAction? = null,
    val error: String? = null,
    /** User-initiated refresh in progress (pull-to-refresh indicator). */
    val refreshing: Boolean = false,
    /** Back from Stripe Checkout with the order still unpaid: confirming the payment, or it didn't come through. */
    val paymentCheck: PaymentCheck? = null,
)

enum class PaymentCheck {
    /** Re-fetching the order a few times: Stripe tells the server a moment after the browser returns. */
    Checking,

    /** Still unpaid after that: the buyer probably closed checkout (or it's still open in the browser). */
    NotReceived,
}

/** Resuming with data older than this re-fetches it (it may have changed in the browser). */
internal val RESUME_STALE_AFTER = 2.seconds

sealed interface OrderDetailEvent {
    data class OpenConversation(val conversationId: String) : OrderDetailEvent
    data class OpenRating(val orderId: String) : OrderDetailEvent
    data class OpenReportProblem(val orderId: String) : OrderDetailEvent
}

/**
 * Order detail and its actions (see [availableActions]). Reloads silently whenever the screen resumes with data older
 * than [RESUME_STALE_AFTER], because payment and Stripe's confirmation happen in the browser. (Not "every resume but
 * the first": after "Continue to payment" this screen is created while the browser is in front, so its first resume
 * *is* the return from Stripe.) After checkout was opened ([OrderDetailRoute.fromCheckout] or Pay), a resume with
 * the order still unpaid checks a few times ([PaymentCheck]) before saying the payment didn't come through.
 */
class OrderDetailViewModel(
    route: OrderDetailRoute,
    private val api: ApiClient,
    private val links: ExternalLinks,
    private val updates: OrderUpdates,
    private val userMessages: UserMessages,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ViewModel() {

    val orderId: String = route.orderId

    /** Checkout was opened in the browser and we haven't seen the order leave PENDING_PAYMENT since. */
    private var awaitingCheckout = route.fromCheckout
    private var paymentCheckJob: Job? = null

    private val _order = MutableStateFlow<UiState<Order>>(UiState.Loading)
    val order: StateFlow<UiState<Order>> = _order.asStateFlow()

    private val _ui = MutableStateFlow(OrderDetailUi())
    val ui: StateFlow<OrderDetailUi> = _ui.asStateFlow()

    val events = EventQueue<OrderDetailEvent>()

    /** When the shown order was last fetched; null while the first load is in flight. */
    private var fetchedAt: TimeMark? = null
    private var refreshJob: Job? = null

    init {
        reload()
        // E.g. the rating screen posted a rating for this order.
        viewModelScope.launch {
            updates.updates.collect { o -> if (o.id == orderId && _order.value is UiState.Content) _order.value = UiState.Content(o) }
        }
    }

    fun reload() {
        load(_order) { api.order(orderId).also { fetchedAt = timeSource.markNow() } }
    }

    /**
     * Reload keeping the current content on screen (resume, after a state conflict). [userInitiated] shows the
     * pull-to-refresh indicator and reports failures.
     */
    fun refresh(userInitiated: Boolean = false) {
        if (refreshJob?.isActive == true) return
        val before = _order.value.valueOrNull
        if (userInitiated) _ui.update { it.copy(refreshing = true) }
        refreshJob = viewModelScope.launch {
            try {
                val fresh = api.order(orderId)
                fetchedAt = timeSource.markNow()
                _order.value = UiState.Content(fresh)
                if (before != null && before != fresh) updates.publish(fresh)
                if (awaitingCheckout && fresh.status != OrderStatus.PENDING_PAYMENT) {
                    awaitingCheckout = false
                    _ui.update { it.copy(paymentCheck = null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (_order.value !is UiState.Content) _order.value = UiState.Error(e) else if (userInitiated) userMessages.showError(e)
            } finally {
                _ui.update { it.copy(refreshing = false) }
            }
        }
    }

    fun onResume() {
        if (awaitingCheckout && _order.value.valueOrNull?.status != OrderStatus.CANCELLED) {
            checkPayment()
            return
        }
        val fetched = fetchedAt ?: return // first load still running (or failed: the error state has Retry)
        if (fetched.elapsedNow() >= RESUME_STALE_AFTER) refresh()
    }

    /** "I've paid, check again": another round of payment checks after checkout (a plain refresh otherwise). */
    fun recheckPayment() {
        if (awaitingCheckout) checkPayment() else refresh(userInitiated = true)
    }

    /** Re-fetches until the order leaves PENDING_PAYMENT (or gives up after [PAYMENT_CHECKS] tries). */
    private fun checkPayment() {
        if (paymentCheckJob?.isActive == true) return
        paymentCheckJob = viewModelScope.launch {
            _ui.update { it.copy(paymentCheck = PaymentCheck.Checking) }
            repeat(PAYMENT_CHECKS) { attempt ->
                if (attempt > 0) delay(PAYMENT_CHECK_INTERVAL)
                val before = _order.value.valueOrNull
                val fresh = try {
                    api.order(orderId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiError) {
                    null
                }
                if (fresh != null) {
                    fetchedAt = timeSource.markNow()
                    _order.value = UiState.Content(fresh)
                    if (before != null && before != fresh) updates.publish(fresh)
                    if (fresh.status != OrderStatus.PENDING_PAYMENT) {
                        awaitingCheckout = false
                        _ui.update { it.copy(paymentCheck = null) }
                        when (fresh.status) {
                            OrderStatus.PAID -> userMessages.show("Payment received. Quad holds it until you confirm pickup.")
                            OrderStatus.CANCELLED -> userMessages.show("Checkout ended without a payment, so this order was cancelled. You weren't charged.")
                            else -> Unit
                        }
                        return@launch
                    }
                }
            }
            _ui.update { it.copy(paymentCheck = PaymentCheck.NotReceived) }
        }
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    fun onAction(action: OrderAction) {
        val o = _order.value.valueOrNull ?: return
        if (_ui.value.running != null) return
        _ui.update { it.copy(error = null) }
        when (action) {
            OrderAction.Pay -> pay(o)
            OrderAction.Rate -> events.send(OrderDetailEvent.OpenRating(o.id))
            OrderAction.Message -> openConversation(o)
            OrderAction.ReportProblem -> events.send(OrderDetailEvent.OpenReportProblem(o.id))
            OrderAction.ConfirmReceipt, OrderAction.Cancel, OrderAction.WithdrawDispute -> _ui.update { it.copy(confirming = action) }
        }
    }

    fun dismissConfirm() {
        if (_ui.value.running == null) _ui.update { it.copy(confirming = null) }
    }

    /** Runs the action shown in the confirmation dialog. */
    fun confirm() {
        val action = _ui.value.confirming ?: return
        if (_ui.value.running != null) return
        _ui.update { it.copy(running = action, error = null) }
        viewModelScope.launch {
            try {
                val updated = when (action) {
                    OrderAction.ConfirmReceipt -> api.confirmOrder(orderId)
                    OrderAction.Cancel -> api.cancelOrder(orderId)
                    OrderAction.WithdrawDispute -> api.withdrawDispute(orderId)
                    else -> error("not a confirmable action: $action")
                }
                _order.value = UiState.Content(updated)
                fetchedAt = timeSource.markNow()
                updates.publish(updated)
                userMessages.show(updated.actionResultMessage(action))
                _ui.update { it.copy(running = null, confirming = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                _ui.update { it.copy(running = null, confirming = null, error = e.message) }
                // Someone else (the other party, Stripe, the auto-release, a moderator) changed the order: show the real
                // state. A 403 means the rules for this state changed under us (e.g. the order got paid): refresh too.
                if (e.code == ErrorCodes.INVALID_ORDER_STATE || e.code == ErrorCodes.CONFLICT || e.code == ErrorCodes.FORBIDDEN) refresh()
            }
        }
    }

    private fun pay(o: Order) {
        val url = o.checkoutUrl
        if (url == null) {
            _ui.update { it.copy(error = "This checkout link is no longer available. Refreshing the order…") }
            refresh()
            return
        }
        if (links.open(url)) {
            awaitingCheckout = true
            paymentCheckJob?.cancel()
            _ui.update { it.copy(paymentCheck = null) }
        } else {
            _ui.update { it.copy(error = "Couldn't open your browser for payment. Please try again.") }
        }
    }

    private fun openConversation(o: Order) {
        _ui.update { it.copy(running = OrderAction.Message) }
        viewModelScope.launch {
            try {
                val conversation = if (o.isBuyer) api.startConversation(o.listing.id) else findSellerConversation(o)
                if (conversation != null) {
                    events.send(OrderDetailEvent.OpenConversation(conversation.id))
                } else {
                    _ui.update {
                        it.copy(error = "${o.buyer.displayName} hasn't messaged you about this listing yet. They can start a chat from the listing.")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                _ui.update { it.copy(error = if (e.code == ErrorCodes.BLOCKED) "You can't message this user." else e.message) }
            } finally {
                _ui.update { it.copy(running = null) }
            }
        }
    }

    /** Sellers can't start conversations (the buyer does), so look for the buyer's conversation about this listing. */
    private suspend fun findSellerConversation(o: Order): Conversation? {
        var cursor: String? = null
        repeat(MAX_CONVERSATION_PAGES) {
            val page = api.conversations(cursor = cursor, limit = Limits.PAGE_MAX)
            page.items.firstOrNull { it.listing.id == o.listing.id && it.otherUser.id == o.buyer.id }?.let { return it }
            cursor = page.nextCursor ?: return null
        }
        return null
    }

    private companion object {
        const val MAX_CONVERSATION_PAGES = 5
        const val PAYMENT_CHECKS = 5
        val PAYMENT_CHECK_INTERVAL = 2.seconds
    }
}

// ---------------------------------------------------------------------------------------------------- rating

data class RateState(
    val stars: Int = 0,
    val comment: String = "",
    val fieldErrors: Map<String, String> = emptyMap(),
    val formError: String? = null,
    val submitting: Boolean = false,
)

/** Rate the other party of a COMPLETED order (once). Emits on [done] when posted (or already rated). */
class RateOrderViewModel(
    route: RateOrderRoute,
    private val api: ApiClient,
    private val updates: OrderUpdates,
    private val userMessages: UserMessages,
) : ViewModel() {
    val orderId = route.orderId

    private val _order = MutableStateFlow<UiState<Order>>(UiState.Loading)
    val order: StateFlow<UiState<Order>> = _order.asStateFlow()

    private val _state = MutableStateFlow(RateState(stars = route.stars.coerceIn(0, 5)))
    val state: StateFlow<RateState> = _state.asStateFlow()

    val done = EventQueue<Unit>()

    init {
        reload()
    }

    fun reload() {
        load(_order) { api.order(orderId) }
    }

    fun onStars(stars: Int) = _state.update { it.copy(stars = stars.coerceIn(1, 5), fieldErrors = it.fieldErrors - "stars", formError = null) }

    fun onComment(text: String) =
        _state.update { it.copy(comment = text.take(Limits.RATING_COMMENT_MAX), fieldErrors = it.fieldErrors - "comment", formError = null) }

    fun submit() {
        val s = _state.value
        val o = _order.value.valueOrNull ?: return
        if (s.submitting) return
        val errors = fieldErrorsOf(
            "stars" to if (s.stars !in 1..5) "Choose 1 to 5 stars" else null,
            "comment" to if (s.comment.trim().length > Limits.RATING_COMMENT_MAX) "Use at most ${Limits.RATING_COMMENT_MAX} characters" else null,
        )
        if (errors.isNotEmpty()) {
            _state.update { it.copy(fieldErrors = errors) }
            return
        }
        _state.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            try {
                api.rateOrder(orderId, CreateRatingRequest(s.stars, s.comment.trim()))
                updates.publish(o.copy(iHaveRated = true))
                userMessages.show("Thanks! Your rating of ${o.otherParty.displayName} was posted.")
                done.send(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e.code == ErrorCodes.CONFLICT) {
                    // Already rated (e.g. from another device): nothing left to do here.
                    updates.publish(o.copy(iHaveRated = true))
                    userMessages.show("You've already rated this order.")
                    done.send(Unit)
                } else {
                    val (fields, form) = e.toFormErrors()
                    _state.update { it.copy(fieldErrors = fields, formError = form) }
                }
            } finally {
                _state.update { it.copy(submitting = false) }
            }
        }
    }
}
