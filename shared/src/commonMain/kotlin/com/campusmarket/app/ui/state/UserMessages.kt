package com.campusmarket.app.ui.state

import com.campusmarket.app.data.ApiError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

data class UserMessage(val text: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)

/**
 * App-wide snackbar queue (Koin single). Inject into a view model and call [show] / [showError]; the signed-in
 * scaffold (and the auth shell) display them. Survives navigation, so "Listing deleted" can be shown after popping.
 */
class UserMessages {
    private val channel = Channel<UserMessage>(Channel.BUFFERED)
    val messages: Flow<UserMessage> = channel.receiveAsFlow()

    fun show(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        channel.trySend(UserMessage(text, actionLabel, onAction))
    }

    fun showError(error: ApiError) = show(error.message)
}
