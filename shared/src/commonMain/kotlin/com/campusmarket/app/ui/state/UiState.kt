package com.campusmarket.app.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Loading / error / content for a screen that shows one thing (a listing, an order, a profile).
 * Render with [com.campusmarket.app.ui.components.UiStateContent]. For lists use [Paginator].
 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Error(val error: ApiError) : UiState<Nothing>
    data class Content<T>(val value: T) : UiState<T>
}

val <T> UiState<T>.valueOrNull: T? get() = (this as? UiState.Content<T>)?.value

/**
 * Runs [block] in [ViewModel.viewModelScope] and publishes the result into [state].
 * With [keepContent] = true (pull-to-refresh) existing content stays visible while reloading, and a failure keeps it too.
 */
fun <T> ViewModel.load(
    state: MutableStateFlow<UiState<T>>,
    keepContent: Boolean = false,
    block: suspend () -> T,
): Job = viewModelScope.launch {
    val previous = state.value
    if (!keepContent || previous !is UiState.Content) state.value = UiState.Loading
    state.value = try {
        UiState.Content(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiError) {
        if (keepContent && previous is UiState.Content) previous else UiState.Error(e)
    }
}

/** Replaces the content in place (optimistic updates). No-op unless the state is [UiState.Content]. */
inline fun <T> MutableStateFlow<UiState<T>>.updateContent(transform: (T) -> T) {
    val current = value
    if (current is UiState.Content) value = UiState.Content(transform(current.value))
}

/**
 * One-off events from a view model to its screen (navigate away, open a URL, show a dialog).
 * Expose `val events = EventQueue<MyEvent>()`, call `events.send(...)`, and collect with
 * [com.campusmarket.app.ui.components.CollectEvents] in the screen.
 */
class EventQueue<E> {
    private val channel = Channel<E>(Channel.BUFFERED)
    val flow: Flow<E> = channel.receiveAsFlow()
    fun send(event: E) {
        channel.trySend(event)
    }
}

/**
 * For a view model's `onResume`: false the first time (the screen's first load is already under way), true on every
 * later resume, i.e. coming back from another screen or the browser. It lives in the view model because the screen
 * leaves the composition while another one is on top.
 */
class ResumeGate {
    private var resumed = false

    fun isReturn(): Boolean = resumed.also { resumed = true }
}
