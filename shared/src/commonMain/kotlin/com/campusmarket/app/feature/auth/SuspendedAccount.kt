package com.campusmarket.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.ui.Validate
import com.campusmarket.app.ui.components.DialogConfirmButton
import com.campusmarket.app.ui.components.DialogDismissButton
import com.campusmarket.app.ui.components.EmailField
import com.campusmarket.app.ui.components.InfoBanner
import com.campusmarket.app.ui.components.InlineError
import com.campusmarket.app.ui.components.PasswordField
import com.campusmarket.app.ui.fieldErrorsOf
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.app.ui.toFormErrors
import com.campusmarket.core.ErrorCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/*
 * A suspended account can't sign in (and so can't reach Settings → Delete account), but the app stores require
 * every user to be able to delete their account. When sign-in or a session ends with ACCOUNT_SUSPENDED, the app
 * says so plainly and offers deletion through `POST /auth/delete-account` (email + password, no session).
 */

enum class DeleteAccountStep { Info, Credentials, Confirm, Done }

data class DeleteAccountState(
    val step: DeleteAccountStep = DeleteAccountStep.Info,
    val email: String = "",
    val password: String = "",
    val fieldErrors: Map<String, String> = emptyMap(),
    val formError: String? = null,
    val submitting: Boolean = false,
)

/** Info → (Delete my account) → Credentials (email + password again) → Confirm (are you sure?) → Done. */
class DeleteSuspendedAccountViewModel(private val api: ApiClient) : ViewModel() {
    private val _state = MutableStateFlow(DeleteAccountState())
    val state: StateFlow<DeleteAccountState> = _state.asStateFlow()

    /** Fresh start on the info step, with the account's email prefilled when known. */
    fun prepare(email: String?) {
        if (_state.value.submitting) return
        _state.value = DeleteAccountState(email = email.orEmpty())
    }

    fun startDeletion() = _state.update {
        it.copy(step = DeleteAccountStep.Credentials, password = "", fieldErrors = emptyMap(), formError = null)
    }

    fun onEmail(v: String) = _state.update { it.copy(email = v, fieldErrors = it.fieldErrors - "email", formError = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, fieldErrors = it.fieldErrors - "password", formError = null) }

    /** Credentials → Confirm, after a local check. */
    fun continueToConfirm() {
        val s = _state.value
        if (s.step != DeleteAccountStep.Credentials) return
        val errors = fieldErrorsOf(
            "email" to Validate.email(s.email),
            "password" to if (s.password.isEmpty()) "Enter your password" else null,
        )
        _state.update { if (errors.isEmpty()) it.copy(step = DeleteAccountStep.Confirm, formError = null) else it.copy(fieldErrors = errors) }
    }

    fun back() {
        if (_state.value.submitting) return
        _state.update {
            when (it.step) {
                DeleteAccountStep.Confirm -> it.copy(step = DeleteAccountStep.Credentials, formError = null)
                DeleteAccountStep.Credentials -> it.copy(step = DeleteAccountStep.Info, password = "", fieldErrors = emptyMap(), formError = null)
                else -> it
            }
        }
    }

    /** Confirm → `POST /auth/delete-account`. */
    fun delete() {
        val s = _state.value
        if (s.step != DeleteAccountStep.Confirm || s.submitting) return
        _state.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            try {
                api.deleteAccountWithCredentials(s.email.trim(), s.password)
                _state.update { it.copy(step = DeleteAccountStep.Done, password = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                _state.update {
                    when (e.code) {
                        ErrorCodes.INVALID_CREDENTIALS -> it.copy(
                            step = DeleteAccountStep.Credentials,
                            fieldErrors = mapOf("password" to e.message),
                        )
                        ErrorCodes.VALIDATION, ErrorCodes.EMAIL_NOT_VERIFIED -> {
                            val (fields, form) = e.toFormErrors()
                            it.copy(step = DeleteAccountStep.Credentials, fieldErrors = fields, formError = form)
                        }
                        ErrorCodes.CONFLICT -> it.copy(
                            formError = e.message + " Paid orders settle on their own: the payment is released to the seller " +
                                "a few days after payment, or our team resolves a reported problem. Try again after that.",
                        )
                        else -> it.copy(formError = e.message)
                    }
                }
            } finally {
                _state.update { it.copy(submitting = false) }
            }
        }
    }

    /** Leaving the dialog after a deletion: the "you were signed out" notice has served its purpose. */
    fun finish() {
        if (_state.value.step == DeleteAccountStep.Done) api.clearSessionEndReason()
        _state.value = DeleteAccountState()
    }
}

/**
 * The account-suspended notice: [message] is the server's (e.g. "Your account is suspended: Violation of community
 * guidelines"), with a two-step "Delete my account" path. [initialEmail] prefills the email field. [onDismiss] says
 * whether the account was deleted.
 */
@Composable
fun SuspendedAccountDialog(message: String, initialEmail: String?, onDismiss: (deleted: Boolean) -> Unit) {
    val vm = koinViewModel<DeleteSuspendedAccountViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.prepare(initialEmail) }
    val close = {
        val deleted = vm.state.value.step == DeleteAccountStep.Done
        vm.finish()
        onDismiss(deleted)
    }

    AlertDialog(
        onDismissRequest = { if (!state.submitting && state.step != DeleteAccountStep.Done) close() },
        title = {
            Text(
                when (state.step) {
                    DeleteAccountStep.Info -> "Account suspended"
                    DeleteAccountStep.Credentials -> "Delete your account"
                    DeleteAccountStep.Confirm -> "Delete your account permanently?"
                    DeleteAccountStep.Done -> "Account deleted"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                when (state.step) {
                    DeleteAccountStep.Info -> {
                        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "You can't sign in or use your account while it's suspended. If you'd rather leave, you can delete your account.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DeleteAccountStep.Credentials -> {
                        Text("To confirm it's you, enter your email and password.", style = MaterialTheme.typography.bodyMedium)
                        EmailField(value = state.email, onValueChange = vm::onEmail, error = state.fieldErrors["email"])
                        PasswordField(
                            value = state.password,
                            onValueChange = vm::onPassword,
                            error = state.fieldErrors["password"],
                            onImeAction = vm::continueToConfirm,
                        )
                        InlineError(state.formError)
                    }
                    DeleteAccountStep.Confirm -> {
                        Text(
                            "This deletes ${state.email.trim()}: your profile, photos and listings are removed and you can't undo it. " +
                                "Records of completed orders, ratings and moderation are kept as described in the Privacy Policy.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        InlineError(state.formError)
                    }
                    DeleteAccountStep.Done -> Text("Your account has been deleted.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            when (state.step) {
                DeleteAccountStep.Info -> DialogConfirmButton("Delete my account", onClick = vm::startDeletion, destructive = true)
                DeleteAccountStep.Credentials -> TextButton(onClick = vm::continueToConfirm) { Text("Continue") }
                DeleteAccountStep.Confirm ->
                    DialogConfirmButton("Delete permanently", onClick = vm::delete, destructive = true, loading = state.submitting)
                DeleteAccountStep.Done -> TextButton(onClick = close) { Text("OK") }
            }
        },
        dismissButton = {
            when (state.step) {
                DeleteAccountStep.Info -> DialogDismissButton("Close", close)
                DeleteAccountStep.Credentials, DeleteAccountStep.Confirm -> DialogDismissButton("Back", vm::back, enabled = !state.submitting)
                DeleteAccountStep.Done -> Unit
            }
        },
    )
}

/**
 * Why the app signed the user out ([reason], from `SessionManager.signOutReason`), on the Welcome screen. For a
 * suspended account it also opens [SuspendedAccountDialog] (once by itself, then from "Delete my account").
 */
@Composable
fun SignOutNotice(reason: ApiError, modifier: Modifier = Modifier) {
    if (reason.code != ErrorCodes.ACCOUNT_SUSPENDED) {
        // An expired session isn't the user's fault: a calm notice, not a red error.
        InfoBanner(reason.message, modifier)
        return
    }
    InlineError(reason.message, modifier)
    val api: ApiClient = koinInject()
    var showDialog by rememberSaveable(reason.message) { mutableStateOf(true) }
    TextButton(onClick = { showDialog = true }) { Text("Delete my account") }
    if (showDialog) SuspendedAccountDialog(reason.message, api.sessionEndEmail, onDismiss = { showDialog = false })
}
