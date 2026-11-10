package com.campusmarket.app.feature.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.platform.ExternalLinks
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.app.ui.state.load
import com.campusmarket.app.ui.state.valueOrNull
import com.campusmarket.core.PayoutAccountStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Seller payouts: status from `GET /payments/account`; "Set up payouts" opens Stripe onboarding in the browser.
 * The browser returns via `campusmarket://payouts` (or the user just switches back), so the status reloads on every
 * resume and the cached user is refreshed so `payoutsEnabled` is current app-wide.
 */
class PayoutsViewModel(
    private val api: ApiClient,
    private val session: SessionManager,
    private val links: ExternalLinks,
    private val userMessages: UserMessages,
) : ViewModel() {

    private val _account = MutableStateFlow<UiState<PayoutAccountStatus>>(UiState.Loading)
    val account: StateFlow<UiState<PayoutAccountStatus>> = _account.asStateFlow()

    private val _starting = MutableStateFlow(false)
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var resumedOnce = false

    /** Set once the user went to Stripe from here, so we can celebrate when they come back enabled. */
    private var awaitingReturn = false

    init {
        reload()
    }

    fun reload() {
        load(_account) { fetch() }
    }

    /** Reload keeping the current status on screen. */
    fun refresh() {
        if (_account.value is UiState.Content) load(_account, keepContent = true) { fetch() } else reload()
    }

    fun onResume() {
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        refresh()
    }

    fun startOnboarding() {
        if (_starting.value) return
        _starting.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val url = api.startPayoutOnboarding().url
                if (links.open(url)) {
                    awaitingReturn = true
                } else {
                    _error.value = "Couldn't open your browser. Please try again."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                _error.value = e.message
            } finally {
                _starting.value = false
            }
        }
    }

    private suspend fun fetch(): PayoutAccountStatus {
        val before = _account.value.valueOrNull
        val status = api.payoutAccount()
        if (awaitingReturn && status.payoutsEnabled && before?.payoutsEnabled != true) {
            awaitingReturn = false
            userMessages.show("Payouts are set up. Buyers can now pay you in the app.")
        }
        if (session.currentUser?.payoutsEnabled != status.payoutsEnabled) {
            try {
                session.refreshUser()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                // The status on this screen is still right; the cached profile catches up on the next launch.
            }
        }
        return status
    }
}
