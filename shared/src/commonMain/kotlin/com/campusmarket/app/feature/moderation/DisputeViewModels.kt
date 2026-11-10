package com.campusmarket.app.feature.moderation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.ui.state.EventQueue
import com.campusmarket.app.ui.state.Paginator
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.app.ui.state.load
import com.campusmarket.app.ui.state.valueOrNull
import com.campusmarket.core.DisputeCase
import com.campusmarket.core.DisputeResolution
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Limits
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.ResolveDisputeRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ---------------------------------------------------------------------------------------------------- queue

/** Disputes by status: OPEN (the queue, oldest first) or CLOSED (most recently closed first). */
class DisputesViewModel(private val api: ApiClient, messages: UserMessages) : ViewModel() {
    private val _status = MutableStateFlow(DisputeStatus.OPEN)
    val status: StateFlow<DisputeStatus> = _status.asStateFlow()

    val disputes = Paginator<DisputeCase>(viewModelScope, key = { it.dispute.id }) { cursor, limit ->
        api.modDisputes(_status.value, cursor, limit)
    }

    init {
        disputes.onRefreshError = messages::showError
    }

    private var resumedOnce = false

    /**
     * Screen resumed: the first time is the initial load; later (back from a dispute that may have been resolved)
     * refresh. The flag lives here because the list leaves the composition while the detail is open.
     */
    fun onResume() {
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        disputes.refresh()
    }

    fun select(status: DisputeStatus) {
        if (_status.value == status) return
        _status.value = status
        disputes.reset()
    }
}

// ---------------------------------------------------------------------------------------------------- detail

/**
 * Whether [viewerId] can resolve [case] now: it's open, the order is still DISPUTED, and the viewer isn't the buyer
 * or the seller (the server refuses self-dealing with 403).
 */
fun canResolve(case: DisputeCase, viewerId: String): Boolean =
    case.dispute.status == DisputeStatus.OPEN && case.orderStatus == OrderStatus.DISPUTED &&
        viewerId != case.buyer.id && viewerId != case.seller.id

/** What the seller gets if the payment is released: amount − platform fee. */
val DisputeCase.sellerShareCents: Int get() = (amountCents - feeCents).coerceAtLeast(0)

class DisputeDetailViewModel(
    route: DisputeDetailRoute,
    private val api: ApiClient,
    private val messages: UserMessages,
) : ViewModel() {
    val orderId: String = route.orderId

    private val _case = MutableStateFlow<UiState<DisputeCase>>(UiState.Loading)
    val case: StateFlow<UiState<DisputeCase>> = _case.asStateFlow()

    /** Runs the resolution and exposes submitting / error for the confirmation dialog. */
    val runner = ActionRunner(viewModelScope)

    /** Fired when the resolution succeeded, so the screen closes its dialog. */
    val actionDone = EventQueue<Unit>()

    init {
        reload()
    }

    fun reload() {
        load(_case) { api.modDispute(orderId) }
    }

    fun refreshQuietly() {
        load(_case, keepContent = true) { api.modDispute(orderId) }
    }

    val refreshing = MutableStateFlow(false)

    /** Pull-to-refresh (the buyer, the seller or another moderator may have settled it meanwhile). */
    fun pullRefresh() {
        refreshing.value = true
        load(_case, keepContent = true) { api.modDispute(orderId) }.invokeOnCompletion { refreshing.value = false }
    }

    /** `POST /mod/disputes/{orderId}/resolve` with the (required, internal) [note]. */
    fun resolve(resolution: DisputeResolution, note: String, viewerId: String) {
        val current = _case.value.valueOrNull ?: return
        val trimmed = note.trim()
        val problem = when {
            !canResolve(current, viewerId) -> "This dispute can't be resolved from here."
            trimmed.isEmpty() -> "Add a note explaining the decision."
            trimmed.length > Limits.DISPUTE_NOTE_MAX -> "Use at most ${Limits.DISPUTE_NOTE_MAX} characters."
            else -> null
        }
        if (problem != null) {
            runner.run({ throw ApiError(ErrorCodes.VALIDATION, problem) })
            return
        }
        runner.run(
            block = {
                try {
                    _case.value = UiState.Content(api.resolveDispute(orderId, ResolveDisputeRequest(resolution, trimmed)))
                } catch (e: ApiError) {
                    // Someone (the buyer, the seller, another moderator) settled it meanwhile: show the real state.
                    if (e.code == ErrorCodes.INVALID_ORDER_STATE) refreshQuietly()
                    throw e
                }
            },
            onSuccess = {
                messages.show(
                    when (resolution) {
                        DisputeResolution.REFUND_BUYER -> "Buyer refunded"
                        DisputeResolution.RELEASE_TO_SELLER -> "Payment released to the seller"
                    },
                )
                actionDone.send(Unit)
            },
        )
    }
}
