package com.campusmarket.app.feature.moderation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.data.isModerator
import com.campusmarket.app.navigation.LocalCurrentUser
import com.campusmarket.app.ui.components.AppTextField
import com.campusmarket.app.ui.components.FilterChip
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.InlineError
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.Limits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ActionState(val submitting: Boolean = false, val error: String? = null)

/** Runs one moderation call at a time and exposes `submitting` / `error` for the confirmation dialog. */
class ActionRunner(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(ActionState())
    val state: StateFlow<ActionState> = _state.asStateFlow()

    fun clearError() = _state.update { it.copy(error = null) }

    fun run(block: suspend () -> Unit, onSuccess: () -> Unit = {}) {
        if (_state.value.submitting) return
        _state.value = ActionState(submitting = true)
        scope.launch {
            try {
                block()
                _state.value = ActionState()
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                _state.value = ActionState(error = e.fields.values.firstOrNull() ?: e.message)
            }
        }
    }
}

/** Suspension lengths offered to moderators. `null` days = indefinite (`SuspendUserRequest.days`). */
internal val SuspendDurations: List<Pair<String, Int?>> = listOf(
    "1 day" to 1,
    "7 days" to 7,
    "30 days" to 30,
    "90 days" to 90,
    "Indefinitely" to null,
)

/**
 * Confirmation for a moderator action with a note field and, for suspensions, a duration picker.
 * [onConfirm] receives the trimmed note and the chosen days (null = indefinite / not applicable).
 */
@Composable
fun ModerationActionDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: (note: String, days: Int?) -> Unit,
    onDismiss: () -> Unit,
    submitting: Boolean,
    error: String?,
    noteLabel: String = "Note (optional)",
    noteRequired: Boolean = false,
    showDuration: Boolean = false,
    destructive: Boolean = true,
) {
    var note by remember { mutableStateOf("") }
    var days by remember { mutableStateOf<Int?>(7) }
    var noteError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (showDuration) {
                    Text("Duration", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SuspendDurations.forEach { (label, value) ->
                            FilterChip(
                                selected = days == value,
                                onClick = { days = value },
                                label = { Text(label) },
                                enabled = !submitting,
                            )
                        }
                    }
                }
                AppTextField(
                    value = note,
                    onValueChange = { note = it.take(Limits.REPORT_DETAILS_MAX); noteError = null },
                    label = noteLabel,
                    error = noteError,
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    maxLength = Limits.REPORT_DETAILS_MAX,
                    enabled = !submitting,
                )
                InlineError(error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    val trimmed = note.trim()
                    if (noteRequired && trimmed.isEmpty()) {
                        noteError = "Required"
                    } else {
                        onConfirm(trimmed, if (showDuration) days else null)
                    }
                },
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.textButtonColors(),
            ) {
                if (submitting) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") } },
    )
}

/**
 * Client-side guard for moderator-only routes (the server enforces it too). Renders [content] for moderators and a
 * "Moderators only" screen otherwise, so no moderation view model or request is created for other users.
 */
@Composable
fun ModeratorGate(title: String, nav: NavController, content: @Composable () -> Unit) {
    if (LocalCurrentUser.current.isModerator) {
        content()
    } else {
        Scaffold(topBar = { AppTopBar(title, onBack = { nav.popBackStack() }) }) { padding ->
            EmptyState(
                "Moderators only",
                message = "You don't have access to this area.",
                icon = Icons.Default.Lock,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}
