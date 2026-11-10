package com.campusmarket.app.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.ui.components.ConfirmDialog
import com.campusmarket.app.ui.components.InlineError
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.UserPublic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

data class BlockState(val loading: Boolean = false, val error: String? = null)

/** Logic behind [BlockUserDialog] (plain class so it is scoped to the dialog, not to a screen's back-stack entry). */
class BlockController(private val api: ApiClient, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(BlockState())
    val state: StateFlow<BlockState> = _state.asStateFlow()

    /** Blocks (or unblocks when [block] is false) [userId]; [onSuccess] runs after the server confirmed. */
    fun submit(userId: String, block: Boolean, onSuccess: () -> Unit) {
        if (_state.value.loading) return
        _state.value = BlockState(loading = true)
        scope.launch {
            try {
                if (block) api.blockUser(userId) else api.unblockUser(userId)
                _state.value = BlockState()
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                _state.update { BlockState(error = e.message) }
            }
        }
    }
}

/**
 * Shared block / unblock confirmation (owner: feature C; used from public profile and conversations).
 * Calls `api.blockUser` / `api.unblockUser` depending on `user.blockedByMe`, then [onDone] with the new state
 * (`true` = now blocked). A snackbar confirms the change. On failure the dialog stays open with the error.
 */
@Composable
fun BlockUserDialog(user: UserPublic, onDismiss: () -> Unit, onDone: (blocked: Boolean) -> Unit = { onDismiss() }) {
    val api: ApiClient = koinInject()
    val messages: UserMessages = koinInject()
    val scope = rememberCoroutineScope()
    val controller = remember(user.id) { BlockController(api, scope) }
    val state by controller.state.collectAsStateWithLifecycle()
    val blocking = !user.blockedByMe

    ConfirmDialog(
        title = if (blocking) "Block ${user.displayName}?" else "Unblock ${user.displayName}?",
        confirmLabel = if (blocking) "Block" else "Unblock",
        onConfirm = {
            controller.submit(user.id, blocking) {
                messages.show(if (blocking) "Blocked ${user.displayName}" else "Unblocked ${user.displayName}")
                onDone(blocking)
            }
        },
        onDismiss = onDismiss,
        destructive = blocking,
        loading = state.loading,
    ) {
        if (blocking) {
            Text("If you block ${user.displayName}:", style = MaterialTheme.typography.bodyMedium)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Bullet("You can't message each other or buy from each other.")
                Bullet("You won't see their listings in Browse or search.")
                Bullet("They aren't told that you blocked them.")
            }
            Text(
                "You can unblock them any time from Profile › Blocked users. If they broke the rules, report them too.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("You'll be able to message and trade with ${user.displayName} again, and their listings will show up in Browse. They aren't told.")
        }
        InlineError(state.error)
    }
}

@Composable
internal fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
