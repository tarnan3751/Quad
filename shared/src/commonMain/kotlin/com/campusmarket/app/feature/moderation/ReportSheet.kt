package com.campusmarket.app.feature.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.ui.components.AppTextField
import com.campusmarket.app.ui.components.InlineError
import com.campusmarket.app.ui.components.PrimaryButton
import com.campusmarket.app.ui.components.SecondaryButton
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.CreateReportRequest
import com.campusmarket.core.Limits
import com.campusmarket.core.ReportReason
import com.campusmarket.core.ReportTargetType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

data class ReportFormState(
    val reason: ReportReason? = null,
    val details: String = "",
    val reasonError: String? = null,
    val detailsError: String? = null,
    val formError: String? = null,
    val submitting: Boolean = false,
    val submitted: Boolean = false,
)

/**
 * Logic behind [ReportSheet]. A plain class (not a navigation-scoped ViewModel) so every sheet starts with a fresh form.
 */
class ReportFormController(
    private val api: ApiClient,
    private val targetType: ReportTargetType,
    private val targetId: String,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ReportFormState())
    val state: StateFlow<ReportFormState> = _state.asStateFlow()

    fun onReason(reason: ReportReason) = _state.update { it.copy(reason = reason, reasonError = null, formError = null) }

    fun onDetails(text: String) =
        _state.update { it.copy(details = text.take(Limits.REPORT_DETAILS_MAX), detailsError = null, formError = null) }

    fun submit() {
        val s = _state.value
        if (s.submitting || s.submitted) return
        val reason = s.reason
        if (reason == null) {
            _state.update { it.copy(reasonError = "Choose a reason") }
            return
        }
        _state.update { it.copy(submitting = true, formError = null, reasonError = null, detailsError = null) }
        scope.launch {
            try {
                api.report(CreateReportRequest(targetType, targetId, reason, s.details.trim()))
                _state.update { it.copy(submitted = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                _state.update {
                    it.copy(
                        reasonError = e.fields["reason"],
                        detailsError = e.fields["details"],
                        formError = if (e.fields.keys.all { k -> k in setOf("reason", "details") } && e.fields.isNotEmpty()) null else e.message,
                    )
                }
            } finally {
                _state.update { it.copy(submitting = false) }
            }
        }
    }
}

/**
 * Shared "Report" bottom sheet (owner: feature C; used by listing detail (A), conversation messages (B) and public
 * profiles (C)). Pick a `ReportReason`, add optional details (at most `Limits.REPORT_DETAILS_MAX`), submit with
 * `api.report(...)`. The sheet then shows a confirmation; its "Done" button (or swiping it away afterwards) calls
 * [onReported]. Cancelling or dismissing before a successful submit calls [onDismiss].
 */
@Composable
fun ReportSheet(
    targetType: ReportTargetType,
    targetId: String,
    onDismiss: () -> Unit,
    onReported: () -> Unit = onDismiss,
) {
    val api: ApiClient = koinInject()
    val scope = rememberCoroutineScope()
    val controller = remember(targetType, targetId) { ReportFormController(api, targetType, targetId, scope) }
    val state by controller.state.collectAsStateWithLifecycle()
    val noun = when (targetType) {
        ReportTargetType.LISTING -> "listing"
        ReportTargetType.USER -> "user"
        ReportTargetType.MESSAGE -> "message"
        ReportTargetType.ORDER -> "order" // log-only type: orders are reported as a dispute, never through this sheet
    }
    val finish = { if (state.submitted) onReported() else onDismiss() }

    ModalBottomSheet(
        onDismissRequest = { if (!state.submitting) finish() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen).padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (state.submitted) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                    Text("Thanks for letting us know", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(
                        "Campus moderators will review this $noun. We'll take action if it breaks the community guidelines. The person you reported isn't told who reported them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                PrimaryButton("Done", onClick = onReported, modifier = Modifier.fillMaxWidth())
            } else {
                Text("Report this $noun", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Reports go to campus moderators. The person you report isn't told who reported them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.padding(top = Spacing.xs)) {
                    ReportReason.entries.forEach { reason ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = state.reason == reason, role = Role.RadioButton, enabled = !state.submitting) {
                                    controller.onReason(reason)
                                }
                                .heightIn(min = 48.dp), // touch target: rows were 28 dp tall
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.reason == reason, onClick = null, enabled = !state.submitting, modifier = Modifier.padding(end = Spacing.md))
                            Text(reason.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    state.reasonError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                AppTextField(
                    value = state.details,
                    onValueChange = controller::onDetails,
                    label = "Details (optional)",
                    placeholder = "Anything that helps us understand what happened",
                    error = state.detailsError,
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6,
                    maxLength = Limits.REPORT_DETAILS_MAX,
                    enabled = !state.submitting,
                )
                InlineError(state.formError, Modifier.fillMaxWidth())
                PrimaryButton("Submit report", onClick = controller::submit, modifier = Modifier.fillMaxWidth(), loading = state.submitting)
                SecondaryButton("Cancel", onClick = onDismiss, modifier = Modifier.fillMaxWidth(), enabled = !state.submitting)
            }
        }
    }
}
