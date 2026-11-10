package com.campusmarket.app.feature.moderation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.state.EventQueue
import com.campusmarket.app.ui.state.Paginator
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.app.ui.state.load
import com.campusmarket.app.ui.state.valueOrNull
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ModerationAction
import com.campusmarket.core.ModerationLogEntry
import com.campusmarket.core.Report
import com.campusmarket.core.ReportStatus
import com.campusmarket.core.ReportTargetType
import com.campusmarket.core.ResolveReportRequest
import com.campusmarket.core.SuspendUserRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ---------------------------------------------------------------------------------------------------- queue

class ModerationQueueViewModel(private val api: ApiClient, messages: UserMessages) : ViewModel() {
    private val _status = MutableStateFlow(ReportStatus.OPEN)
    val status: StateFlow<ReportStatus> = _status.asStateFlow()

    val reports = Paginator<Report>(viewModelScope, key = { it.id }) { cursor, limit ->
        api.modReports(_status.value, cursor, limit)
    }

    private var resumedOnce = false

    init {
        reports.onRefreshError = messages::showError
    }

    /** Screen resumed: the first time is the initial load; later (back from a report detail) refresh the list. */
    fun onResume() {
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        reports.refresh()
    }

    fun select(status: ReportStatus) {
        if (_status.value == status) return
        _status.value = status
        reports.reset()
    }
}

// ---------------------------------------------------------------------------------------------------- detail

/** Actions the resolve endpoint accepts for [report] (REMOVE_LISTING only for LISTING reports). Empty once resolved. */
fun resolveActionsFor(report: Report): List<ModerationAction> {
    if (report.status != ReportStatus.OPEN) return emptyList()
    return buildList {
        add(ModerationAction.DISMISS)
        if (report.targetType == ReportTargetType.LISTING && report.targetListing != null) add(ModerationAction.REMOVE_LISTING)
        if (report.targetUser != null) {
            add(ModerationAction.WARN_USER)
            add(ModerationAction.SUSPEND_USER)
        }
    }
}

class ReportDetailViewModel(
    route: ReportDetailRoute,
    private val api: ApiClient,
    private val messages: UserMessages,
) : ViewModel() {
    val reportId: String = route.reportId

    private val _report = MutableStateFlow<UiState<Report>>(UiState.Loading)
    val report: StateFlow<UiState<Report>> = _report.asStateFlow()

    /** Shared by every action dialog on the screen (only one is open at a time). */
    val runner = ActionRunner(viewModelScope)

    /** Fired when an action succeeded, so the screen closes its dialog. */
    val actionDone = EventQueue<Unit>()

    init {
        reload()
    }

    fun reload() {
        load(_report) { api.modReport(reportId) }
    }

    fun refreshQuietly() {
        load(_report, keepContent = true) { api.modReport(reportId) }
    }

    /** `POST /mod/reports/{id}/resolve`. The server also resolves other open reports on the same target. */
    fun resolve(action: ModerationAction, note: String, suspendDays: Int? = null) {
        val report = _report.value.valueOrNull ?: return
        if (action !in resolveActionsFor(report)) {
            runner.run({ throw com.campusmarket.app.data.ApiError("VALIDATION", "That action isn't available for this report.") })
            return
        }
        runner.run(
            block = {
                val updated = api.resolveReport(
                    reportId,
                    ResolveReportRequest(action, note, suspendDays.takeIf { action == ModerationAction.SUSPEND_USER }),
                )
                _report.value = UiState.Content(updated)
            },
            onSuccess = {
                messages.show(
                    when (action) {
                        ModerationAction.DISMISS -> "Report dismissed"
                        else -> "Report resolved: ${action.label.lowercase()}"
                    },
                )
                actionDone.send(Unit)
            },
        )
    }

    /** Direct tools, available regardless of the report's status. */
    fun suspendUser(userId: String, reason: String, days: Int?) =
        direct("User suspended") { api.suspendUser(userId, SuspendUserRequest(reason, days)) }

    fun unsuspendUser(userId: String, note: String) = direct("Suspension lifted") { api.unsuspendUser(userId, note) }

    fun removeListing(listingId: String, note: String) = direct("Listing removed") { api.removeListing(listingId, note) }

    fun restoreListing(listingId: String, note: String) = direct("Listing restored") { api.restoreListing(listingId, note) }

    private fun direct(successMessage: String, call: suspend () -> Unit) {
        runner.run(
            block = call,
            onSuccess = {
                messages.show(successMessage)
                refreshQuietly()
                actionDone.send(Unit)
            },
        )
    }
}

/** Whether a direct "remove listing" makes sense (the server only removes ACTIVE listings). */
fun canRemoveListing(status: ListingStatus) = status == ListingStatus.ACTIVE

fun canRestoreListing(status: ListingStatus) = status == ListingStatus.REMOVED

// ---------------------------------------------------------------------------------------------------- log

class ModerationLogViewModel(private val api: ApiClient, messages: UserMessages) : ViewModel() {
    val entries = Paginator<ModerationLogEntry>(viewModelScope, key = { it.id }) { cursor, limit ->
        api.moderationLog(cursor, limit)
    }

    init {
        entries.onRefreshError = messages::showError
    }
}
