package com.campusmarket.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmarket.app.ui.state.PagedState
import com.campusmarket.app.ui.state.Paginator
import com.campusmarket.app.ui.theme.Spacing
import kotlinx.coroutines.flow.distinctUntilChanged

private const val PAGED_ITEM = "__item"

/**
 * Infinite list for a [Paginator]: starts the first load if nobody has, full-screen [loading] (skeleton rows by
 * default) / error / [empty] states, pull-to-refresh, load-more near the end, and a retry footer.
 *
 * ```
 * PagedLazyColumn(vm.conversations, key = { it.id }, empty = { EmptyState("No messages yet") }) { conversation ->
 *     ConversationRow(conversation, onClick = { ... })
 * }
 * ```
 * For chat use `reverseLayout = true` with a newest-first endpoint.
 */
@Composable
fun <T> PagedLazyColumn(
    paginator: Paginator<T>,
    key: ((T) -> Any)?,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(vertical = Spacing.sm),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = if (reverseLayout) Arrangement.Bottom else Arrangement.Top,
    pullToRefresh: Boolean = !reverseLayout,
    empty: @Composable () -> Unit = { EmptyState("Nothing here yet") },
    header: LazyListScope.() -> Unit = {},
    loading: @Composable () -> Unit = { if (reverseLayout) LoadingState() else ListSkeleton(contentPadding = contentPadding) },
    itemContent: @Composable LazyItemScope.(T) -> Unit,
) {
    val paged by paginator.state.collectAsStateWithLifecycle()
    AutoStart(paginator)

    LoadMoreNearEnd(paginator, paged, threshold = 5) { state.layoutInfo.let { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount } }

    FirstLoadGate(paged, paginator, modifier, loading) {
        RefreshContainer(pullToRefresh, paged.isRefreshing, paginator::refresh, modifier) {
            LazyColumn(
                state = state,
                contentPadding = contentPadding,
                reverseLayout = reverseLayout,
                verticalArrangement = verticalArrangement,
                modifier = Modifier.fillMaxSize(),
            ) {
                header()
                if (paged.isEmpty) {
                    item(key = "__empty") { Box(Modifier.fillParentMaxSize()) { empty() } }
                } else {
                    items(
                        count = paged.items.size,
                        key = key?.let { k -> { index: Int -> k(paged.items[index]) } },
                        // Every row is the same kind of item, so any row's nodes can be reused for another.
                        contentType = { PAGED_ITEM },
                    ) { index ->
                        itemContent(paged.items[index])
                    }
                }
                item(key = "__footer", contentType = "__footer") { PagingFooter(paged, paginator::retry) }
            }
        }
    }
}

/** Starts the first load unless it has started already. */
@Composable
internal fun <T> AutoStart(paginator: Paginator<T>) {
    LaunchedEffect(paginator) {
        val s = paginator.state.value
        if (!s.loaded && !s.isLoading && s.error == null) paginator.refresh()
    }
}

/**
 * Loads the next page when the last visible item comes within [threshold] of the end, including when a page doesn't
 * fill the screen (no scroll ever happens then). [layout] gives (last visible index, total items).
 */
@Composable
internal fun <T> LoadMoreNearEnd(paginator: Paginator<T>, paged: PagedState<T>, threshold: Int, layout: () -> Pair<Int?, Int>) {
    fun nearEnd(): Boolean = layout().let { (last, total) -> total > 0 && (last ?: -1) >= total - threshold }
    LaunchedEffect(paginator) {
        snapshotFlow { nearEnd() }.distinctUntilChanged().collect { if (it) paginator.loadMore() }
    }
    LaunchedEffect(paged.items.size, paged.endReached) {
        if (paged.loaded && !paged.endReached && nearEnd()) paginator.loadMore()
    }
}

@Composable
private fun <T> FirstLoadGate(
    paged: PagedState<T>,
    paginator: Paginator<T>,
    modifier: Modifier,
    loading: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    when {
        paged.loaded -> content()
        paged.error != null -> ErrorState(paged.error, modifier.fillMaxSize(), onRetry = paginator::retry)
        else -> Box(modifier.fillMaxSize()) { loading() }
    }
}

@Composable
private fun RefreshContainer(
    enabled: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    if (enabled) {
        PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) { content() }
    } else {
        Box(modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun <T> PagingFooter(paged: PagedState<T>, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) {
        when {
            paged.isLoadingMore -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            paged.loadMoreError != null -> TextButton(onClick = onRetry) {
                Text("Couldn't load more. Tap to retry", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
