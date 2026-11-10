package com.campusmarket.app.navigation

import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.core.Limits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * Unread messages for the Inbox tab badge (Koin single).
 *
 * The signed-in shell calls [refresh] when it starts, when the app comes back to the foreground and when the user
 * lands on a tab root: one `GET /conversations` (first page, most recent first, so it holds every unread
 * conversation in practice), throttled. It doesn't keep the chat socket open on its own. Chat screens that already
 * know the exact numbers can push them: [set] the total, or call `refresh(force = true)` after marking a conversation
 * read.
 */
class InboxBadge(private val api: ApiClient, private val scope: CoroutineScope) {
    private val _count = MutableStateFlow(0)

    /** Total unread messages across conversations. */
    val count: StateFlow<Int> = _count.asStateFlow()

    private var job: Job? = null
    private var lastFetch: TimeSource.Monotonic.ValueTimeMark? = null

    /** Re-counts from the server, at most every [MIN_INTERVAL_MS] unless [force]d. Failures keep the last count. */
    fun refresh(force: Boolean = false) {
        val last = lastFetch
        if (!force && last != null && last.elapsedNow().inWholeMilliseconds < MIN_INTERVAL_MS) return
        if (job?.isActive == true) {
            if (!force) return
            job?.cancel()
        }
        lastFetch = TimeSource.Monotonic.markNow()
        job = scope.launch {
            try {
                _count.value = api.conversations(limit = Limits.PAGE_MAX).items.sumOf { it.unreadCount }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                // offline or signed out: keep what we had
            }
        }
    }

    /** Sets the total directly (e.g. from the inbox list, which is live). */
    fun set(total: Int) {
        _count.value = total.coerceAtLeast(0)
    }

    /** Signed out: forget everything. */
    fun clear() {
        job?.cancel()
        job = null
        lastFetch = null
        _count.value = 0
    }

    private companion object {
        const val MIN_INTERVAL_MS = 15_000L
    }
}
