package com.campusmarket.app.data

import com.campusmarket.core.LoginRequest
import com.campusmarket.core.RegisterRequest
import com.campusmarket.core.RegisterResponse
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserRole
import com.campusmarket.core.VerifyEmailRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SessionState {
    data object SignedOut : SessionState
    data class SignedIn(val user: UserPrivate) : SessionState
}

/**
 * Something that keeps private data of the signed-in user on the device (e.g. unsent chat messages) and must drop it
 * when they sign out or delete their account. Bind implementations to this type in Koin.
 */
fun interface SignOutListener {
    fun onSignedOut()
}

val UserPrivate.isModerator: Boolean get() = role == UserRole.MODERATOR || role == UserRole.ADMIN

/**
 * Who is signed in. [state] is derived from [TokenStore.session]: correct synchronously at launch (restored from
 * storage), updated before `login` / `verify` / `signOut` return, and flipped to [SessionState.SignedOut] when
 * [ApiClient] ends the session (refresh rejected, account suspended).
 *
 * All functions throw [ApiError] on failure.
 */
class SessionManager(
    private val api: ApiClient,
    private val tokens: TokenStore,
    private val scope: CoroutineScope,
    /** Told about a deliberate sign-out or an account deletion, not about a session the server ended. */
    private val signOutListeners: () -> List<SignOutListener> = { emptyList() },
) {
    private val _state = MutableStateFlow(tokens.current().toState())
    val state: StateFlow<SessionState> = _state.asStateFlow()
    private var restored = false

    init {
        // Mirror changes made outside this class (ApiClient ending the session after a failed refresh).
        scope.launch { tokens.session.collect { sync() } }
    }

    /** Re-derives [state] from the token store; called synchronously after every change made here. */
    private fun sync() {
        _state.value = tokens.current().toState()
    }

    /** The signed-in user, or null. */
    val currentUser: UserPrivate? get() = (state.value as? SessionState.SignedIn)?.user

    /** Why the app signed the user out on its own (expired / suspended); null after a deliberate sign-out. */
    val signOutReason: StateFlow<ApiError?> get() = api.sessionEndReason

    /** Called once at launch: refreshes the cached profile (and tokens) in the background. Network errors are ignored. */
    fun restore() {
        if (restored || tokens.current() == null) return
        restored = true
        scope.launch {
            try {
                refreshUser()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                // offline or session ended: state already reflects it
            }
        }
    }

    // ---- sign up / sign in

    /** Step 1 of sign-up. Returns the email to verify (and the dev code in dev mode). */
    suspend fun register(email: String, password: String, displayName: String): RegisterResponse =
        api.register(RegisterRequest(email.trim(), password, displayName.trim()))

    suspend fun resendCode(email: String): RegisterResponse = api.resendCode(email.trim())

    /** Step 2 of sign-up: signs the user in on success. The server requires the pending account's password as well as the code. */
    suspend fun verify(email: String, code: String, password: String): UserPrivate {
        val auth = api.verifyEmail(VerifyEmailRequest(email.trim(), code.trim(), password))
        api.clearSessionEndReason()
        tokens.save(auth.toStoredSession())
        sync()
        return auth.user
    }

    suspend fun login(email: String, password: String): UserPrivate {
        val auth = api.login(LoginRequest(email.trim(), password))
        api.clearSessionEndReason()
        tokens.save(auth.toStoredSession())
        sync()
        return auth.user
    }

    // ---- profile

    /** Re-fetches `GET /me` and updates [state]. */
    suspend fun refreshUser(): UserPrivate {
        val user = api.me()
        updateUser(user)
        return user
    }

    /** Store a fresher copy of the user (e.g. the result of `PATCH /me`). No-op when signed out. */
    fun updateUser(user: UserPrivate) {
        val current = tokens.current() ?: return
        // Usually unchanged (the profile tab refreshes on every visit): skip the encrypted, synchronous write.
        if (current.user == user) return
        tokens.save(current.copy(user = user))
        sync()
    }

    // ---- sign out / delete

    /** Revokes the refresh token (best effort; failures ignored) and clears the session. Never throws. */
    suspend fun signOut() {
        val current = tokens.current()
        api.clearSessionEndReason()
        tokens.clear()
        sync()
        forgetLocalData()
        if (current != null) {
            try {
                api.logout(current.refreshToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                // already signed out locally; the token expires server-side
            }
        }
    }

    /** `DELETE /me`, then clears the session. Throws [ApiError] (409 `CONFLICT` while orders are PAID). */
    suspend fun deleteAccount() {
        api.deleteMe()
        api.clearSessionEndReason()
        tokens.clear()
        sync()
        forgetLocalData()
    }

    /**
     * `POST /auth/delete-account` for an account that can't hold a session (suspended). Throws [ApiError] like
     * [ApiClient.deleteAccountWithCredentials].
     */
    suspend fun deleteAccountWithCredentials(email: String, password: String) {
        api.deleteAccountWithCredentials(email, password)
        forgetLocalData()
    }

    private fun forgetLocalData() {
        signOutListeners().forEach { runCatching { it.onSignedOut() } }
    }

    private fun StoredSession?.toState(): SessionState =
        if (this == null) SessionState.SignedOut else SessionState.SignedIn(user)
}
