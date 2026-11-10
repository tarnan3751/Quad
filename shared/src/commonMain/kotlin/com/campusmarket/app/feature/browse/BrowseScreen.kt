package com.campusmarket.app.feature.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.feature.listing.FavoritesRoute
import com.campusmarket.app.feature.listing.ListingDetailRoute
import com.campusmarket.app.feature.listing.ListingIcons
import com.campusmarket.app.feature.listing.ListingTile
import com.campusmarket.app.feature.listing.PagedListingGrid
import com.campusmarket.app.feature.listing.PriceInput
import com.campusmarket.app.feature.listing.icon
import com.campusmarket.app.navigation.LocalCurrentUser
import com.campusmarket.app.navigation.TopLevelTab
import com.campusmarket.app.navigation.navigateToTab
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.FilterChip
import com.campusmarket.app.ui.components.PrimaryButton
import com.campusmarket.app.ui.components.SecondaryButton
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.CategoryInfo
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingSort
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** Browse tab: search (with recent searches), category row, sort menu, filter sheet, paged listing grid. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BrowseScreen(nav: NavController) {
    val vm = koinViewModel<BrowseViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val recents by vm.recentSearches.collectAsStateWithLifecycle()
    val me = LocalCurrentUser.current
    // IME "search" hides the keyboard but leaves the field focused (like Back does). clearFocus() doesn't work for this
    // on Android: the view system hands focus straight back to the only touch-focusable node, the search field, and
    // the keyboard pops up again.
    val keyboard = LocalSoftwareKeyboardController.current
    var searchFocused by remember { mutableStateOf(false) }
    // Back (or the panel's close button) puts recent searches away even though the field keeps its focus (see above).
    var recentsDismissed by remember { mutableStateOf(false) }
    val showRecents = searchFocused && !recentsDismissed && state.searchText.isEmpty() && recents.isNotEmpty()
    BackHandler(enabled = showRecents) {
        recentsDismissed = true
        keyboard?.hide()
    }
    val searching = state.query.q != null
    // A fresh grid state per query: new results start at the top. Coming back from a listing (or from recent searches)
    // restores it.
    val gridState = rememberSaveable(state.query, saver = LazyGridState.Saver) { LazyGridState() }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Browse",
                actions = {
                    IconButton(onClick = { nav.navigate(FavoritesRoute) }) {
                        Icon(Icons.Filled.FavoriteBorder, contentDescription = "Favorites")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchBarRow(
                text = state.searchText,
                onTextChange = {
                    recentsDismissed = false
                    vm.onSearchTextChange(it)
                },
                onSearch = {
                    vm.submitSearch()
                    keyboard?.hide()
                },
                onClear = vm::clearSearch,
                onFocusChange = {
                    if (it && !searchFocused) recentsDismissed = false
                    searchFocused = it
                },
                filterCount = state.filters.filterCount,
                onOpenFilters = {
                    keyboard?.hide()
                    vm.openFilters()
                },
            )
            if (showRecents) {
                RecentSearchesPanel(
                    recents = recents,
                    onPick = {
                        vm.searchFor(it)
                        keyboard?.hide()
                    },
                    onRemove = vm::removeRecentSearch,
                    onClearAll = vm::clearRecentSearches,
                    onClose = {
                        recentsDismissed = true
                        keyboard?.hide()
                    },
                )
            } else {
                PagedListingGrid(
                    paginator = vm.listings,
                    state = gridState,
                    header = {
                        item(key = "__header", span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                CategoryRow(state.filters.category, onSelect = vm::selectCategory, modifier = Modifier.bleed(Spacing.screen))
                                if (state.filters.filterCount > 0) {
                                    ActiveFilters(
                                        filters = state.filters,
                                        onRemoveConditions = vm::removeConditions,
                                        onRemovePrice = vm::removePrice,
                                        onClearAll = vm::clearAll,
                                        modifier = Modifier.bleed(Spacing.screen),
                                    )
                                }
                                ResultsHeader(
                                    title = when {
                                        searching -> "Results for \u201C${state.query.q}\u201D"
                                        state.filters.category != null -> state.filters.category!!.label
                                        else -> "On your campus"
                                    },
                                    sort = state.filters.sort ?: defaultSort(searching),
                                    searching = searching,
                                    onSort = vm::selectSort,
                                )
                            }
                        }
                    },
                    empty = {
                        NoResults(
                            state = state,
                            categories = categories,
                            onClearSearch = vm::clearSearch,
                            onClearAll = vm::clearAll,
                            onBrowseCategory = vm::browseCategory,
                            onSell = { nav.navigateToTab(TopLevelTab.Sell) },
                        )
                    },
                ) { listing ->
                    ListingTile(
                        listing = listing,
                        onClick = {
                            vm.onListingOpened()
                            nav.navigate(ListingDetailRoute(listing.id))
                        },
                        onToggleFavorite = if (listing.sellerId != me.id) ({ vm.toggleFavorite(listing) }) else null,
                    )
                }
            }
        }
    }

    state.filterDraft?.let { draft ->
        FilterSheet(
            draft = draft,
            onDismiss = vm::dismissFilters,
            onToggleCondition = vm::toggleDraftCondition,
            onMinPrice = vm::onDraftMinPrice,
            onMaxPrice = vm::onDraftMaxPrice,
            onPriceCap = vm::onDraftPriceCap,
            onSort = vm::selectDraftSort,
            onReset = vm::resetDraft,
            onApply = vm::applyFilters,
        )
    }
}

/** Lets a full-width row inside the padded grid scroll edge to edge (chips slide under the screen edges). */
private fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = horizontal.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra),
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-horizontal.roundToPx(), 0) }
}

// ---------------------------------------------------------------------------------------------------- search

@Composable
private fun SearchBarRow(
    text: String,
    onTextChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    filterCount: Int,
    onOpenFilters: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.screen, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f).onFocusChanged { onFocusChange(it.isFocused) },
            placeholder = { Text("Search textbooks, bikes…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = onClear) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )
        IconButton(onClick = onOpenFilters) {
            BadgedBox(
                badge = {
                    if (filterCount > 0) {
                        Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                            Text("$filterCount")
                        }
                    }
                },
            ) {
                Icon(TuneIcon, contentDescription = if (filterCount > 0) "Filters, $filterCount active" else "Filters")
            }
        }
    }
}

@Composable
private fun RecentSearchesPanel(
    recents: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Spacing.lg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = Spacing.screen, end = Spacing.xs, top = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Recent searches", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClearAll) { Text("Clear") }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close recent searches") }
        }
        recents.forEach { query ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(onClickLabel = "Search for $query") { onPick(query) }
                    .padding(start = Spacing.screen, end = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Icon(ListingIcons.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text(query, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { onRemove(query) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove “$query” from recent searches",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- header

@Composable
private fun CategoryRow(selected: ListingCategory?, onSelect: (ListingCategory?) -> Unit, modifier: Modifier = Modifier) {
    val rowState = rememberLazyListState()
    // Keep the selected chip in view (it may be picked from a "no results" suggestion).
    LaunchedEffect(selected) {
        val index = selected?.let { ListingCategory.entries.indexOf(it) + 1 } ?: 0
        val visible = rowState.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty() && visible.none { it.index == index && it.offset >= 0 }) rowState.animateScrollToItem(index)
    }
    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = Spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = modifier.fillMaxWidth(),
    ) {
        item(key = "all") {
            CategoryChip("All", ListingIcons.Grid, selected = selected == null, onClick = { onSelect(null) })
        }
        items(ListingCategory.entries, key = { it.name }) { category ->
            CategoryChip(category.label, category.icon, selected = category == selected, onClick = { onSelect(category) })
        }
    }
}

@Composable
private fun CategoryChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
    )
}

@Composable
private fun ActiveFilters(
    filters: BrowseFilters,
    onRemoveConditions: () -> Unit,
    onRemovePrice: () -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        if (filters.conditions.isNotEmpty()) {
            item(key = "conditions") { RemovableChip(conditionsLabel(filters.conditions), "Remove condition filter", onRemoveConditions) }
        }
        if (filters.hasPrice) {
            item(key = "price") { RemovableChip(priceLabel(filters.minPriceCents, filters.maxPriceCents), "Remove price filter", onRemovePrice) }
        }
        item(key = "clear") { TextButton(onClick = onClearAll) { Text("Clear all") } }
    }
}

@Composable
private fun RemovableChip(label: String, removeDescription: String, onRemove: () -> Unit) {
    FilterChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = removeDescription, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
    )
}

/** What the grid shows, and the sort menu. */
@Composable
private fun ResultsHeader(title: String, sort: ListingSort, searching: Boolean, onSort: (ListingSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.foundation.layout.Box {
            val label = sort.label(searching)
            TextButton(
                onClick = { open = true },
                modifier = Modifier.padding(start = Spacing.sm).clearAndSetSemantics {
                    contentDescription = "Sort by: $label"
                    role = Role.DropdownList
                    onClick { open = true; true }
                },
            ) {
                Icon(ListingIcons.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(label, modifier = Modifier.padding(start = Spacing.xs))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Text(
                    "Sort by",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
                sortOptions(searching).forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label(searching)) },
                        trailingIcon = if (option == sort) {
                            { Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                        onClick = {
                            open = false
                            onSort(option)
                        },
                    )
                }
            }
        }
    }
}

private fun sortOptions(searching: Boolean): List<ListingSort> =
    if (searching) listOf(ListingSort.RELEVANCE, ListingSort.NEWEST, ListingSort.PRICE_ASC, ListingSort.PRICE_DESC)
    else listOf(ListingSort.NEWEST, ListingSort.PRICE_ASC, ListingSort.PRICE_DESC)

private fun conditionsLabel(conditions: Set<ListingCondition>): String =
    ListingCondition.entries.filter { it in conditions }.joinToString(", ") { it.label }

internal fun priceLabel(min: Int?, max: Int?): String = when {
    min != null && max != null -> "${Format.price(min)} – ${Format.price(max)}"
    min != null -> "${Format.price(min)}+"
    max != null -> "Under ${Format.price(max)}"
    else -> "Any price"
}

internal fun ListingSort.label(searching: Boolean = true): String = when (this) {
    ListingSort.RELEVANCE -> if (searching) "Best match" else "Newest"
    ListingSort.NEWEST -> "Newest"
    ListingSort.PRICE_ASC -> "Price: low to high"
    ListingSort.PRICE_DESC -> "Price: high to low"
}

// ---------------------------------------------------------------------------------------------------- empty

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoResults(
    state: BrowseState,
    categories: UiState<List<CategoryInfo>>,
    onClearSearch: () -> Unit,
    onClearAll: () -> Unit,
    onBrowseCategory: (ListingCategory) -> Unit,
    onSell: () -> Unit,
) {
    val q = state.query.q
    val filtered = state.filters.filterCount > 0
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            q != null -> EmptyState(
                "No results for “$q”",
                modifier = Modifier.fillMaxWidth(),
                message = if (filtered || state.filters.category != null) {
                    "Check the spelling, try a more general word, or remove some filters."
                } else {
                    "Check the spelling or try a more general word, like “lamp” instead of “desk lamp”."
                },
                icon = Icons.Filled.Search,
                actionLabel = if (filtered || state.filters.category != null) "Clear search and filters" else "Clear search",
                onAction = if (filtered || state.filters.category != null) onClearAll else onClearSearch,
            )
            filtered -> EmptyState(
                "Nothing matches these filters",
                modifier = Modifier.fillMaxWidth(),
                message = "Try a wider price range or more conditions.",
                icon = TuneIcon,
                actionLabel = "Clear filters",
                onAction = onClearAll,
            )
            state.filters.category != null -> EmptyState(
                "Nothing in ${state.filters.category!!.label} yet",
                modifier = Modifier.fillMaxWidth(),
                message = "Got something that fits? It'd be the first one here.",
                icon = state.filters.category!!.icon,
                actionLabel = "Sell something",
                onAction = onSell,
            )
            else -> EmptyState(
                "Nothing for sale yet",
                modifier = Modifier.fillMaxWidth(),
                message = "Be the first to list something on your campus.",
                actionLabel = "Sell something",
                onAction = onSell,
            )
        }
        // Somewhere to go next: the busiest categories (other than the one already shown).
        val suggestions = (categories as? UiState.Content)?.value.orEmpty()
            .filter { it.activeCount > 0 && it.category != state.filters.category }
            .sortedByDescending { it.activeCount }
            .take(4)
        if (q != null || filtered) {
            if (suggestions.isNotEmpty()) {
                Text(
                    "Or browse a popular category",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Spacing.sm),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
                ) {
                    suggestions.forEach { info ->
                        CategoryChip(info.label, info.category.icon, selected = false, onClick = { onBrowseCategory(info.category) })
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- filter sheet

private val PriceCaps = listOf(1000, 2500, 5000, 10000)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    draft: FilterDraft,
    onDismiss: () -> Unit,
    onToggleCondition: (ListingCondition) -> Unit,
    onMinPrice: (String) -> Unit,
    onMaxPrice: (String) -> Unit,
    onPriceCap: (Int) -> Unit,
    onSort: (ListingSort) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Boolean,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val maxSheetHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.9f
    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    // The sheet's own insets would pad the top by the status bar height (a gap above the title); the bottom bar below
    // handles the navigation bar and keyboard itself.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle(Modifier.padding(top = Spacing.sm, bottom = 0.dp)) },
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxSheetHeight)) {
            Row(Modifier.fillMaxWidth().padding(start = Spacing.screen, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Text("Sort & filter", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = ::close) { Icon(Icons.Filled.Close, contentDescription = "Close filters") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screen, vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                SheetSection("Sort by") {
                    draft.sortOptions.forEach { option ->
                        val selected = option == draft.selectedSort
                        FilterChip(
                            selected = selected,
                            onClick = { onSort(option) },
                            label = { Text(option.label(draft.searching)) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                            } else null,
                        )
                    }
                }
                SheetSection("Condition", hint = if (draft.conditions.isEmpty()) "Any" else null) {
                    ListingCondition.entries.forEach { condition ->
                        val selected = condition in draft.conditions
                        FilterChip(
                            selected = selected,
                            onClick = { onToggleCondition(condition) },
                            label = { Text(condition.label) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                            } else null,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("Price", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.Top) {
                        PriceInput(
                            value = draft.minPrice,
                            onValueChange = onMinPrice,
                            label = "Min",
                            placeholder = "Any",
                            error = draft.fieldErrors["minPrice"],
                            modifier = Modifier.weight(1f),
                        )
                        PriceInput(
                            value = draft.maxPrice,
                            onValueChange = onMaxPrice,
                            label = "Max",
                            placeholder = "Any",
                            error = draft.fieldErrors["maxPrice"],
                            imeAction = ImeAction.Done,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        PriceCaps.forEach { cap ->
                            val selected = draft.minPrice.isBlank() && draft.maxPrice == Format.priceInput(cap)
                            FilterChip(selected = selected, onClick = { onPriceCap(cap) }, label = { Text("Under ${Format.price(cap)}") })
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = Spacing.screen, vertical = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                SecondaryButton("Reset", onClick = onReset, enabled = !draft.isDefault, modifier = Modifier.weight(1f))
                PrimaryButton(
                    "Show results",
                    onClick = { if (onApply()) close() },
                    modifier = Modifier.weight(2f),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetSection(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) { content() }
    }
}
