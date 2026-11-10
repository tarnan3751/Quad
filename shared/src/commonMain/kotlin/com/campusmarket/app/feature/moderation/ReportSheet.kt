package com.campusmarket.app.feature.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.feature.profile.Glyphs
import com.campusmarket.app.ui.components.AppTextField
import com.campusmarket.app.ui.components.InlineError
import com.campusmarket.app.ui.components.PrimaryButton
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

/**
 * What this device reported during this run of the app. The server ignores a repeat report while the first is still
 * open (a silent 200), so the sheet uses this to say "you already reported this" instead of pretending to send it again.
 */
class ReportedTargets {
    private val keys = mutableSetOf<String>()

    fun contains(type: ReportTargetType, id: String): Boolean = "$type:$id" in keys

    fun add(type: ReportTargetType, id: String) {
        keys += "$type:$id"
    }
}

data class ReportFormState(
    val reason: ReportReason? = null,
    val details: String = "",
    val reasonError: String? = null,
    val detailsError: String? = null,
    val formError: String? = null,
    val submitting: Boolean = false,
    val submitted: Boolean = false,
    /** Reported earlier in this session: the sheet opens on a reminder instead of the form. */
    val alreadyReported: Boolean = false,
)

/**
 * Logic behind [ReportSheet]. A plain class (not a navigation-scoped ViewModel) so every sheet starts with a fresh form.
 */
class ReportFormController(
    private val api: ApiClient,
    private val targetType: ReportTargetType,
    private val targetId: String,
    private val scope: CoroutineScope,
    private val reported: ReportedTargets? = null,
) {
    private val _state = MutableStateFlow(ReportFormState(alreadyReported = reported?.contains(targetType, targetId) == true))
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
                reported?.add(targetType, targetId)
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

/** One line under each reason, so people can pick quickly without guessing what fits. */
val ReportReason.hint: String
    get() = when (this) {
        ReportReason.PROHIBITED_ITEM -> "Weapons, drugs, alcohol, stolen or recalled goods"
        ReportReason.SCAM -> "Paying outside Quad, fake payment proof, suspicious links"
        ReportReason.HARASSMENT -> "Threats, insults, hate speech or unwanted contact"
        ReportReason.SPAM -> "Repeated posts, ads, or messages that aren't about a sale"
        ReportReason.MISLEADING -> "Fake brands, someone else's photos, not as described"
        ReportReason.OTHER -> "Anything else that breaks the community guidelines"
    }

/**
 * Shared "Report" bottom sheet (owner: feature C; used by listing detail (A), conversation messages (B) and public
 * profiles (C)). Pick a `ReportReason`, add optional details (at most `Limits.REPORT_DETAILS_MAX`), submit with
 * `api.report(...)`. The sheet then says what happens next; its "Done" button (or swiping it away afterwards) calls
 * [onReported]. Cancelling or dismissing before a successful submit calls [onDismiss]. Something already reported
 * from this device opens on a "you already reported this" note instead of the form.
 */
@Composable
fun ReportSheet(
    targetType: ReportTargetType,
    targetId: String,
    onDismiss: () -> Unit,
    onReported: () -> Unit = onDismiss,
) {
    val api: ApiClient = koinInject()
    val reported: ReportedTargets = koinInject()
    val scope = rememberCoroutineScope()
    val controller = remember(targetType, targetId) { ReportFormController(api, targetType, targetId, scope, reported) }
    val state by controller.state.collectAsStateWithLifecycle()
    val noun = when (targetType) {
        ReportTargetType.LISTING -> "listing"
        ReportTargetType.USER -> "account"
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
            when {
                state.submitted -> {
                    Outcome(
                        "Report sent",
                        "Thanks for helping keep Quad safe. A campus moderator will review this $noun against the community guidelines.",
                    )
                    NextStep(Glyphs.Shield, "A moderator reviews it", "Reports are checked by Quad staff, oldest first.")
                    NextStep(Glyphs.Gavel, "They act if it breaks the rules", "That can mean removing a listing, a warning, or suspending the account.")
                    NextStep(Glyphs.Visibility, "You stay anonymous", "Nobody is told who reported them. You won't get a message about the outcome.")
                    if (targetType != ReportTargetType.LISTING) {
                        Text(
                            "Don't want to hear from them? You can also block them from their profile.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.xs),
                        )
                    }
                    PrimaryButton("Done", onClick = onReported, modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm))
                }
                state.alreadyReported -> {
                    Outcome(
                        "You already reported this $noun",
                        "Moderators have your report and will review it. There's no need to send it again.",
                    )
                    PrimaryButton("Done", onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm))
                }
                else -> {
                    Text("Report this $noun", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Pick the closest reason. Reports are private: nobody is told who reported them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.padding(top = Spacing.xs).selectableGroup()) {
                        ReportReason.entries.forEach { reason ->
                            ReasonRow(reason, selected = state.reason == reason, enabled = !state.submitting) { controller.onReason(reason) }
                        }
                        state.reasonError?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = Spacing.sm))
                        }
                    }
                    AppTextField(
                        value = state.details,
                        onValueChange = controller::onDetails,
                        label = if (state.reason == ReportReason.OTHER) "What happened? (optional)" else "Details (optional)",
                        placeholder = "Anything that helps a moderator understand, like what was said or asked",
                        error = state.detailsError,
                        singleLine = false,
                        minLines = 3,
                        maxLines = 6,
                        maxLength = Limits.REPORT_DETAILS_MAX,
                        enabled = !state.submitting,
                    )
                    InlineError(state.formError, Modifier.fillMaxWidth())
                    PrimaryButton("Send report", onClick = controller::submit, modifier = Modifier.fillMaxWidth(), loading = state.submitting)
                    TextButton(onClick = onDismiss, enabled = !state.submitting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun ReasonRow(reason: ReportReason, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, enabled = enabled, onClick = onSelect)
            .heightIn(min = 56.dp)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(horizontal = Spacing.sm))
        Column(Modifier.weight(1f).padding(start = Spacing.xs)) {
            Text(reason.label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium))
            Text(reason.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Outcome(title: String, message: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = Spacing.sm, bottom = Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun NextStep(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
