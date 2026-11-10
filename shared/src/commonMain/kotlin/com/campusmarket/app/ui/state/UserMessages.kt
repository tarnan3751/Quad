package com.campusmarket.app.ui.state

import com.campusmarket.app.data.ApiError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** A snackbar. [isError] adds an alert icon and keeps it up longer. */
data class UserMessage(
    val text: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val isError: Boolean = false,
)

/**
 * App-wide snackbar queue (Koin single). Inject into a view model and call [show] / [showError]; the signed-in
 * scaffold (and the auth shell) display them. Survives navigation, so "Listing deleted" can be shown after popping.
 * Keep the text short and plain ("Listing deleted", "Couldn't save your changes").
 */
class UserMessages {
    private val channel = Channel<UserMessage>(Channel.BUFFERED)
    val messages: Flow<UserMessage> = channel.receiveAsFlow()

    fun show(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        channel.trySend(UserMessage(text, actionLabel, onAction))
    }

    /** An error snackbar with [ApiError.message] (already user-presentable). */
    fun showError(error: ApiError) = showError(error.message)

    /** An error snackbar, optionally with an action such as "Retry". */
    fun showError(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        channel.trySend(UserMessage(text, actionLabel, onAction, isError = true))
    }
}
