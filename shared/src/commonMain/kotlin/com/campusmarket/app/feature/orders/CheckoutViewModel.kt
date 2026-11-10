package com.campusmarket.app.feature.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.platform.ExternalLinks
import com.campusmarket.app.ui.state.EventQueue
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.app.ui.state.load
import com.campusmarket.app.ui.state.valueOrNull
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Limits
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.OrderRole
import com.campusmarket.core.OrderStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the buyer can't (or couldn't) place the order. */
enum class CheckoutProblem {
    OwnListing,
    Unavailable,
    PayoutsNotEnabled,
    Blocked,
}

data class CheckoutUi(
    val placing: Boolean = false,
    val problem: CheckoutProblem? = null,
    /** Other errors (network, payment provider) shown inline; the buyer can try again. */
    val error: String? = null,
    /** My live order for this listing, if I already started buying it. */
    val existingOrderId: String? = null,
)

sealed interface CheckoutEvent {
    /** Order created (and checkout opened in the browser, if [checkoutOpened]): show it, replacing the review screen. */
    data class OrderPlaced(val orderId: String, val checkoutOpened: Boolean = true) : CheckoutEvent
}

/**
 * Buy flow: review → `POST /orders` → open Stripe Checkout in the browser → order detail (which refreshes when the
 * browser sends the user back via `campusmarket://orders/{id}` or the app resumes).
 */
class CheckoutViewModel(
    private val route: CheckoutRoute,
    private val api: ApiClient,
    private val links: ExternalLinks,
    private val updates: OrderUpdates,
    private val userMessages: UserMessages,
) : ViewModel() {

    private val _listing = MutableStateFlow<UiState<ListingDetail>>(UiState.Loading)
    val listing: StateFlow<UiState<ListingDetail>> = _listing.asStateFlow()

    private val _ui = MutableStateFlow(CheckoutUi())
    val ui: StateFlow<CheckoutUi> = _ui.asStateFlow()

    val events = EventQueue<CheckoutEvent>()

    init {
        reload()
    }

    fun reload() {
        _ui.value = CheckoutUi()
        load(_listing) {
            api.listing(route.listingId).also { l ->
                when {
                    l.isMine -> _ui.update { it.copy(problem = CheckoutProblem.OwnListing) }
                    l.status != ListingStatus.ACTIVE -> {
                        _ui.update { it.copy(problem = CheckoutProblem.Unavailable) }
                        findExistingOrder()
                    }
                }
            }
        }
    }

    fun placeOrder() {
        val s = _ui.value
        if (s.placing || s.problem != null || _listing.value.valueOrNull == null) return
        _ui.update { it.copy(placing = true, error = null) }
        viewModelScope.launch {
            try {
                val order = api.createOrder(route.listingId)
                updates.publish(order)
                val opened = order.checkoutUrl?.let(links::open) ?: false
                if (!opened) {
                    // The order exists and this screen is about to be replaced by it: say so where it survives navigation.
                    userMessages.show("Couldn't open your browser. Tap “Continue to payment” to try again.")
                }
                events.send(CheckoutEvent.OrderPlaced(order.id, checkoutOpened = opened))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                when (val problem = checkoutProblemFor(e)) {
                    null -> _ui.update { it.copy(error = e.message) }
                    else -> {
                        _ui.update { it.copy(problem = problem) }
                        if (problem == CheckoutProblem.Unavailable) findExistingOrder()
                    }
                }
            } finally {
                _ui.update { it.copy(placing = false) }
            }
        }
    }

    /** A RESERVED listing may be reserved by *my* unfinished order: offer to continue it instead of a dead end. */
    private fun findExistingOrder() {
        viewModelScope.launch {
            try {
                val mine = api.orders(OrderRole.BUYER, limit = Limits.PAGE_MAX).items.firstOrNull {
                    it.listing.id == route.listingId && it.status in setOf(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID)
                }
                if (mine != null) _ui.update { it.copy(existingOrderId = mine.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                // Best effort.
            }
        }
    }
}

internal fun checkoutProblemFor(e: ApiError): CheckoutProblem? = when {
    e.code == ErrorCodes.PAYOUTS_NOT_ENABLED -> CheckoutProblem.PayoutsNotEnabled
    e.code == ErrorCodes.LISTING_UNAVAILABLE || e.isNotFound -> CheckoutProblem.Unavailable
    e.code == ErrorCodes.BLOCKED -> CheckoutProblem.Blocked
    // "You can't buy your own listing" is a plain 400.
    e.isValidation && e.fields.isEmpty() -> CheckoutProblem.OwnListing
    else -> null
}
