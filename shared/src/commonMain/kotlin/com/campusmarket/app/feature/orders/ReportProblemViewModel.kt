package com.campusmarket.app.feature.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.ui.fieldErrorsOf
import com.campusmarket.app.ui.state.EventQueue
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.app.ui.state.load
import com.campusmarket.app.ui.state.valueOrNull
import com.campusmarket.app.ui.toFormErrors
import com.campusmarket.core.DisputeReason
import com.campusmarket.core.Limits
import com.campusmarket.core.OpenDisputeRequest
import com.campusmarket.core.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReportProblemState(
    val reason: DisputeReason? = null,
    val details: String = "",
    val fieldErrors: Map<String, String> = emptyMap(),
    val formError: String? = null,
    val submitting: Boolean = false,
) {
    /** "Something else" needs a description; the other reasons speak for themselves. */
    val detailsRequired: Boolean get() = reason == DisputeReason.OTHER
}

/**
 * The buyer's "Report a problem" form for a PAID order (`POST /orders/{id}/dispute`): a reason plus optional details.
 * On success the order (now DISPUTED, payment frozen) is published to [OrderUpdates] and [done] fires.
 */
class ReportProblemViewModel(
    route: ReportProblemRoute,
    private val api: ApiClient,
    private val updates: OrderUpdates,
    private val userMessages: UserMessages,
) : ViewModel() {
    val orderId = route.orderId

    private val _order = MutableStateFlow<UiState<Order>>(UiState.Loading)
    val order: StateFlow<UiState<Order>> = _order.asStateFlow()

    private val _state = MutableStateFlow(ReportProblemState())
    val state: StateFlow<ReportProblemState> = _state.asStateFlow()

    val done = EventQueue<Unit>()

    val reasons: List<DisputeReason> = BuyerDisputeReasons

    init {
        reload()
    }

    fun reload() {
        load(_order) { api.order(orderId) }
    }

    fun onReason(reason: DisputeReason) {
        if (reason !in reasons) return
        _state.update { it.copy(reason = reason, fieldErrors = it.fieldErrors - "reason" - "details", formError = null) }
    }

    fun onDetails(text: String) =
        _state.update { it.copy(details = text.take(Limits.DISPUTE_DETAILS_MAX), fieldErrors = it.fieldErrors - "details", formError = null) }

    fun submit() {
        val s = _state.value
        if (s.submitting || _order.value.valueOrNull == null) return
        val details = s.details.trim()
        val errors = fieldErrorsOf(
            "reason" to if (s.reason == null) "Choose what went wrong" else null,
            "details" to when {
                details.length > Limits.DISPUTE_DETAILS_MAX -> "Use at most ${Limits.DISPUTE_DETAILS_MAX} characters"
                s.detailsRequired && details.isEmpty() -> "Tell us what went wrong"
                else -> null
            },
        )
        if (errors.isNotEmpty()) {
            _state.update { it.copy(fieldErrors = errors) }
            return
        }
        val reason = s.reason ?: return
        _state.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            try {
                val updated = api.openDispute(orderId, OpenDisputeRequest(reason, details))
                updates.publish(updated)
                userMessages.show("Problem reported. Your payment is on hold while it's sorted out.")
                done.send(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                val (fields, form) = e.toFormErrors()
                _state.update { it.copy(fieldErrors = fields, formError = form) }
            } finally {
                _state.update { it.copy(submitting = false) }
            }
        }
    }
}
