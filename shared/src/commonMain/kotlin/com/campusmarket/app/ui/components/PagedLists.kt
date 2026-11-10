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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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

private const val LOAD_MORE_THRESHOLD = 5

/**
 * Infinite list for a [Paginator]: starts the first load if nobody has, full-screen loading / error / [empty]
 * states, pull-to-refresh, load-more near the end, and a retry footer.
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
    itemContent: @Composable LazyItemScope.(T) -> Unit,
) {
    val paged by paginator.state.collectAsStateWithLifecycle()
    AutoStart(paginator)

    LaunchedEffect(state, paginator) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) paginator.loadMore() }
    }
    // A page that doesn't fill the screen leaves us "near the end" without a scroll; ask again when items change.
    LaunchedEffect(paged.items.size, paged.endReached) {
        val info = state.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (paged.loaded && !paged.endReached && last >= info.totalItemsCount - LOAD_MORE_THRESHOLD) paginator.loadMore()
    }

    FirstLoadGate(paged, paginator, modifier) {
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
                    items(count = paged.items.size, key = key?.let { k -> { index: Int -> k(paged.items[index]) } }) { index ->
                        itemContent(paged.items[index])
                    }
                }
                item(key = "__footer") { PagingFooter(paged, paginator::retry) }
            }
        }
    }
}

/** Grid version of [PagedLazyColumn] for listing tiles (adaptive columns, ~160 dp min). */
@Composable
fun <T> PagedLazyGrid(
    paginator: Paginator<T>,
    key: ((T) -> Any)?,
    modifier: Modifier = Modifier,
    columns: GridCells = GridCells.Adaptive(minSize = 160.dp),
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(Spacing.md),
    empty: @Composable () -> Unit = { EmptyState("Nothing here yet") },
    header: LazyGridScope.() -> Unit = {},
    itemContent: @Composable LazyGridItemScope.(T) -> Unit,
) {
    val paged by paginator.state.collectAsStateWithLifecycle()
    AutoStart(paginator)

    LaunchedEffect(state, paginator) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) paginator.loadMore() }
    }
    LaunchedEffect(paged.items.size, paged.endReached) {
        val info = state.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (paged.loaded && !paged.endReached && last >= info.totalItemsCount - LOAD_MORE_THRESHOLD) paginator.loadMore()
    }

    FirstLoadGate(paged, paginator, modifier) {
        RefreshContainer(true, paged.isRefreshing, paginator::refresh, modifier) {
            LazyVerticalGrid(
                columns = columns,
                state = state,
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                modifier = Modifier.fillMaxSize(),
            ) {
                header()
                if (paged.isEmpty) {
                    item(key = "__empty", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(top = 48.dp)) { empty() } }
                } else {
                    items(count = paged.items.size, key = key?.let { k -> { index: Int -> k(paged.items[index]) } }) { index ->
                        itemContent(paged.items[index])
                    }
                }
                item(key = "__footer", span = { GridItemSpan(maxLineSpan) }) { PagingFooter(paged, paginator::retry) }
            }
        }
    }
}

@Composable
private fun <T> AutoStart(paginator: Paginator<T>) {
    LaunchedEffect(paginator) {
        val s = paginator.state.value
        if (!s.loaded && !s.isLoading && s.error == null) paginator.refresh()
    }
}

@Composable
private fun <T> FirstLoadGate(paged: PagedState<T>, paginator: Paginator<T>, modifier: Modifier, content: @Composable () -> Unit) {
    when {
        paged.loaded -> content()
        paged.error != null -> ErrorState(paged.error, modifier.fillMaxSize(), onRetry = paginator::retry)
        else -> LoadingState(modifier.fillMaxSize())
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
