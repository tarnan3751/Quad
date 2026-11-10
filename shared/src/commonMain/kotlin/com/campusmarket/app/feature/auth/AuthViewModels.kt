package com.campusmarket.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.ui.Validate
import com.campusmarket.app.ui.fieldErrorsOf
import com.campusmarket.app.ui.state.EventQueue
import com.campusmarket.app.ui.toFormErrors
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Limits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Reference implementation of the form pattern (see docs/CLIENT.md → "Forms"):
 *  - one immutable state data class per screen, exposed as StateFlow
 *  - `fieldErrors` keyed by request property name (same keys the server uses in ErrorResponse.fields)
 *  - local validation first (Validate.*), then server errors mapped with ApiError.toFormErrors
 *  - navigation via an EventQueue, consumed by the screen with CollectEvents
 */

/**
 * The password the user just typed on register / login, kept in memory only so the verify step can prove
 * knowledge of the pending account's password (the server requires it). Deliberately not part of any
 * navigation route, saved state or persistent storage: after process death it is gone and the verify screen
 * sends the user back to login.
 */
class PendingVerification {
    private var email: String? = null
    private var password: String? = null

    fun remember(email: String, password: String) {
        this.email = normalize(email)
        this.password = password
    }

    fun passwordFor(email: String): String? = password.takeIf { this.email == normalize(email) }

    fun clear() {
        email = null
        password = null
    }

    private fun normalize(email: String) = email.trim().lowercase()
}

/** The verify screen can't continue (no pending password in memory): back to login with a message. */
data class GoToLogin(val email: String, val notice: String)

/** Login says "verify first" or register succeeded: go to the code screen. */
data class GoToVerify(val email: String, val devCode: String?)

// ---------------------------------------------------------------------------------------------------- register

data class RegisterState(
    val email: String = "",
    val displayName: String = "",
    val password: String = "",
    val fieldErrors: Map<String, String> = emptyMap(),
    val formError: String? = null,
    val submitting: Boolean = false,
)

class RegisterViewModel(private val session: SessionManager, private val pending: PendingVerification) : ViewModel() {
    private val _state = MutableStateFlow(RegisterState())
    val state: StateFlow<RegisterState> = _state.asStateFlow()
    val events = EventQueue<GoToVerify>()

    fun onEmail(v: String) = _state.update { it.copy(email = v, fieldErrors = it.fieldErrors - "email", formError = null) }
    fun onDisplayName(v: String) = _state.update { it.copy(displayName = v, fieldErrors = it.fieldErrors - "displayName", formError = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, fieldErrors = it.fieldErrors - "password", formError = null) }

    /** Focus left a field: flag a mistake straight away, but never nag about a field the user hasn't typed in. */
    fun checkEmail() = checkField("email", _state.value.email) { Validate.email(it) }
    fun checkDisplayName() = checkField("displayName", _state.value.displayName) { Validate.displayName(it) }
    fun checkPassword() = checkField("password", _state.value.password) { Validate.password(it) }

    private fun checkField(key: String, value: String, validate: (String) -> String?) {
        if (value.isBlank()) return
        val error = validate(value) ?: return
        _state.update { it.copy(fieldErrors = it.fieldErrors + (key to error)) }
    }

    fun submit() {
        val s = _state.value
        if (s.submitting) return
        val errors = fieldErrorsOf(
            "email" to Validate.email(s.email),
            "displayName" to Validate.displayName(s.displayName),
            "password" to Validate.password(s.password),
        )
        if (errors.isNotEmpty()) {
            _state.update { it.copy(fieldErrors = errors) }
            return
        }
        _state.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            try {
                val result = session.register(s.email, s.password, s.displayName)
                pending.remember(result.email, s.password)
                events.send(GoToVerify(result.email, result.devCode))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                val (fields, form) = e.toFormErrors(
                    mapOf(ErrorCodes.EMAIL_NOT_ALLOWED to "email", ErrorCodes.EMAIL_TAKEN to "email"),
                )
                _state.update { it.copy(fieldErrors = fields, formError = form) }
            } finally {
                _state.update { it.copy(submitting = false) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- verify

data class VerifyState(
    val email: String,
    val code: String = "",
    /** Dev-mode code from the server, shown as a hint with a "Use code" button. */
    val devCode: String? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val formError: String? = null,
    val info: String? = null,
    val submitting: Boolean = false,
    val resending: Boolean = false,
    /** Seconds until "Resend" is enabled again. */
    val resendCooldown: Int = 0,
)

class VerifyEmailViewModel(
    route: VerifyEmailRoute,
    private val session: SessionManager,
    private val pending: PendingVerification,
) : ViewModel() {
    private val _state = MutableStateFlow(VerifyState(email = route.email, devCode = route.devCode))
    val state: StateFlow<VerifyState> = _state.asStateFlow()
    val events = EventQueue<GoToLogin>()

    init {
        if (pending.passwordFor(route.email) == null) sendToLogin()
        startCooldown()
    }

    private fun sendToLogin() {
        events.send(GoToLogin(_state.value.email, "Please sign in again to verify your email. We can send you a new code."))
    }

    fun onCode(v: String) {
        val digits = v.filter { it.isDigit() }.take(Limits.VERIFICATION_CODE_LENGTH)
        _state.update { it.copy(code = digits, fieldErrors = it.fieldErrors - "code", formError = null) }
        if (digits.length == Limits.VERIFICATION_CODE_LENGTH) submit()
    }

    fun useDevCode() {
        _state.value.devCode?.let(::onCode)
    }

    fun submit() {
        val s = _state.value
        if (s.submitting) return
        Validate.verificationCode(s.code)?.let { error ->
            _state.update { it.copy(fieldErrors = mapOf("code" to error)) }
            return
        }
        val password = pending.passwordFor(s.email)
        if (password == null) {
            sendToLogin()
            return
        }
        _state.update { it.copy(submitting = true, formError = null, info = null) }
        viewModelScope.launch {
            try {
                session.verify(s.email, s.code, password)
                pending.clear()
                // Signed in: the app shell switches to the main tabs on its own.
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                val (fields, form) = e.toFormErrors(mapOf(ErrorCodes.INVALID_CODE to "code"))
                _state.update { it.copy(fieldErrors = fields, formError = form) }
            } finally {
                _state.update { it.copy(submitting = false) }
            }
        }
    }

    fun resend() {
        val s = _state.value
        if (s.resending || s.resendCooldown > 0) return
        _state.update { it.copy(resending = true, formError = null, info = null) }
        viewModelScope.launch {
            try {
                val result = session.resendCode(s.email)
                _state.update { it.copy(devCode = result.devCode ?: it.devCode, info = "We sent a new code to ${s.email}.") }
                startCooldown()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                _state.update { it.copy(formError = e.message) }
            } finally {
                _state.update { it.copy(resending = false) }
            }
        }
    }

    private fun startCooldown() {
        viewModelScope.launch {
            for (left in RESEND_COOLDOWN_S downTo 0) {
                _state.update { it.copy(resendCooldown = left) }
                if (left > 0) delay(1_000)
            }
        }
    }

    private companion object {
        const val RESEND_COOLDOWN_S = 30
    }
}

// ---------------------------------------------------------------------------------------------------- login

data class LoginState(
    val email: String = "",
    val password: String = "",
    val fieldErrors: Map<String, String> = emptyMap(),
    val formError: String? = null,
    val submitting: Boolean = false,
    /** The server's ACCOUNT_SUSPENDED message after signing in to a suspended account (offers account deletion). */
    val suspended: String? = null,
    /** Whether the suspended-account dialog is open. */
    val showSuspended: Boolean = false,
)

class LoginViewModel(route: LoginRoute, private val session: SessionManager, private val pending: PendingVerification) : ViewModel() {
    private val _state = MutableStateFlow(LoginState(email = route.email.orEmpty(), formError = route.notice))
    val state: StateFlow<LoginState> = _state.asStateFlow()
    val events = EventQueue<GoToVerify>()

    fun onEmail(v: String) =
        _state.update { it.copy(email = v, fieldErrors = it.fieldErrors - "email", formError = null, suspended = null, showSuspended = false) }

    fun showSuspendedDialog() = _state.update { if (it.suspended != null) it.copy(showSuspended = true) else it }

    /** The suspended-account dialog closed; after a deletion the form starts over. */
    fun dismissSuspended(deleted: Boolean) = _state.update {
        if (deleted) it.copy(password = "", formError = null, suspended = null, showSuspended = false) else it.copy(showSuspended = false)
    }
    fun onPassword(v: String) = _state.update { it.copy(password = v, fieldErrors = it.fieldErrors - "password", formError = null) }

    /** Focus left the email field: flag a typo straight away (an empty field waits for submit). */
    fun checkEmail() {
        val email = _state.value.email
        if (email.isBlank()) return
        val error = Validate.email(email) ?: return
        _state.update { it.copy(fieldErrors = it.fieldErrors + ("email" to error)) }
    }

    fun submit() {
        val s = _state.value
        if (s.submitting) return
        val errors = fieldErrorsOf(
            "email" to Validate.email(s.email),
            "password" to if (s.password.isEmpty()) "Enter your password" else null,
        )
        if (errors.isNotEmpty()) {
            _state.update { it.copy(fieldErrors = errors) }
            return
        }
        _state.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            try {
                session.login(s.email, s.password)
                // Signed in: the app shell switches to the main tabs on its own.
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e.code == ErrorCodes.EMAIL_NOT_VERIFIED) {
                    // Send a fresh code and continue on the verify screen.
                    val devCode = try {
                        session.resendCode(s.email).devCode
                    } catch (resendError: ApiError) {
                        null
                    }
                    pending.remember(s.email, s.password)
                    events.send(GoToVerify(s.email.trim(), devCode))
                } else if (e.code == ErrorCodes.ACCOUNT_SUSPENDED) {
                    _state.update { it.copy(formError = e.message, suspended = e.message, showSuspended = true) }
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
