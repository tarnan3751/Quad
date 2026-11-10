package com.campusmarket.app.ui.state

import com.campusmarket.app.data.ApiError
import com.campusmarket.core.Limits
import com.campusmarket.core.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Snapshot of a cursor-paged list. */
data class PagedState<T>(
    val items: List<T> = emptyList(),
    /** First load in progress (nothing to show yet). */
    val isLoading: Boolean = false,
    /** Pull-to-refresh in progress (items stay visible). */
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** Failure of the first load (show a full-screen error). */
    val error: ApiError? = null,
    /** Failure of a later page (show a retry footer). */
    val loadMoreError: ApiError? = null,
    val endReached: Boolean = false,
    /** True once the first page has arrived (distinguishes "empty" from "not loaded yet"). */
    val loaded: Boolean = false,
) {
    val isEmpty: Boolean get() = loaded && items.isEmpty()
}

/**
 * Drives a `Page<T>` endpoint: first load, pull-to-refresh, infinite scroll, retry, and local edits.
 *
 * ```
 * val listings = Paginator(viewModelScope, key = { it.id }) { cursor, limit -> api.listings(query, cursor, limit) }
 * init { listings.refresh() }
 * ```
 * Render it with [com.campusmarket.app.ui.components.PagedLazyColumn] / `PagedLazyGrid`, which call [loadMore] near
 * the end and [refresh] on pull. To change the query, update what the lambda reads and call [reset].
 *
 * @param key identity used to drop duplicates across pages (keyset pagination can repeat items when data shifts).
 */
class Paginator<T>(
    private val scope: CoroutineScope,
    private val pageSize: Int = Limits.PAGE_DEFAULT,
    private val key: ((T) -> Any)? = null,
    private val fetch: suspend (cursor: String?, limit: Int) -> Page<T>,
) {
    private val _state = MutableStateFlow(PagedState<T>())
    val state: StateFlow<PagedState<T>> = _state.asStateFlow()

    /** Optional hook for refresh failures while content is visible (e.g. `userMessages::showError`). */
    var onRefreshError: ((ApiError) -> Unit)? = null

    private var nextCursor: String? = null
    private var job: Job? = null

    /** Reloads from the first page. Keeps current items on screen while loading (pull-to-refresh). */
    fun refresh() {
        job?.cancel()
        _state.update {
            if (it.loaded) it.copy(isRefreshing = true, loadMoreError = null)
            else it.copy(isLoading = true, error = null)
        }
        job = scope.launch { loadPage(cursor = null, replace = true) }
    }

    /** Clears everything and loads the first page with a full-screen spinner (e.g. new search query). */
    fun reset() {
        job?.cancel()
        nextCursor = null
        _state.value = PagedState(isLoading = true)
        job = scope.launch { loadPage(cursor = null, replace = true) }
    }

    /** Loads the next page if there is one and nothing else is in flight. */
    fun loadMore() {
        val s = _state.value
        if (!s.loaded || s.endReached || s.isLoading || s.isRefreshing || s.isLoadingMore || s.loadMoreError != null) return
        if (job?.isActive == true) return
        _state.update { it.copy(isLoadingMore = true) }
        job = scope.launch { loadPage(cursor = nextCursor, replace = false) }
    }

    /** Retries whatever failed last (first page or next page). */
    fun retry() {
        val s = _state.value
        if (!s.loaded) {
            reset()
        } else if (s.loadMoreError != null) {
            _state.update { it.copy(loadMoreError = null) }
            loadMore()
        }
    }

    /** Local edit of the loaded items (optimistic favorite toggle, remove a deleted item, prepend a new message). */
    fun update(transform: (List<T>) -> List<T>) {
        _state.update { it.copy(items = transform(it.items)) }
    }

    private suspend fun loadPage(cursor: String?, replace: Boolean) {
        try {
            val page = fetch(cursor, pageSize)
            nextCursor = page.nextCursor
            _state.update { s ->
                val merged = if (replace) page.items else s.items + page.items
                PagedState(
                    items = if (key != null) merged.distinctBy(key) else merged,
                    endReached = page.nextCursor == null,
                    loaded = true,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _state.update { s ->
                when {
                    !s.loaded -> s.copy(isLoading = false, error = e)
                    replace -> s.copy(isRefreshing = false) // keep the old items visible
                    else -> s.copy(isLoadingMore = false, loadMoreError = e)
                }
            }
            if (replace && _state.value.loaded) onRefreshError?.invoke(e)
        }
    }
}
