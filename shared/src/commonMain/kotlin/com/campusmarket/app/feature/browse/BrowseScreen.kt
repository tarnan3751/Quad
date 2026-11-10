package com.campusmarket.app.feature.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import com.campusmarket.app.ui.components.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.feature.listing.FavoritesRoute
import com.campusmarket.app.feature.listing.ListingDetailRoute
import com.campusmarket.app.navigation.LocalCurrentUser
import com.campusmarket.app.navigation.TopLevelTab
import com.campusmarket.app.navigation.navigateToTab
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.ListingCard
import com.campusmarket.app.ui.components.PagedLazyGrid
import com.campusmarket.app.ui.components.PriceField
import com.campusmarket.app.ui.components.PrimaryButton
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.CategoryInfo
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingSort
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** Browse tab: search, category chips, filter sheet, paged listing grid. */
@Composable
fun BrowseScreen(nav: NavController) {
    val vm = koinViewModel<BrowseViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val me = LocalCurrentUser.current
    val focus = LocalFocusManager.current
    // IME "search" hides the keyboard but leaves the field focused (like Back does). clearFocus() doesn't work for this
    // on Android: the view system hands focus straight back to the only touch-focusable node, the search field, and
    // the keyboard pops up again.
    val keyboard = LocalSoftwareKeyboardController.current

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
                onTextChange = vm::onSearchTextChange,
                onSearch = {
                    vm.submitSearch()
                    keyboard?.hide()
                },
                onClear = vm::clearSearch,
                filterCount = state.filters.sheetCount,
                onOpenFilters = {
                    focus.clearFocus()
                    vm.openFilters()
                },
            )
            CategoryChips(categories, selected = state.filters.category, onSelect = vm::selectCategory)
            ActiveFilters(
                filters = state.filters,
                searching = state.query.q != null,
                onRemoveConditions = vm::removeConditions,
                onRemovePrice = vm::removePrice,
                onRemoveSort = vm::removeSort,
                onClearAll = vm::clearAll,
            )
            // A fresh grid state per query: new results start at the top.
            val gridState = rememberSaveable(state.query, saver = LazyGridState.Saver) { LazyGridState() }
            PagedLazyGrid(
                paginator = vm.listings,
                key = { it.id },
                state = gridState,
                contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.sm, bottom = Spacing.lg),
                empty = {
                    if (state.isFiltered) {
                        EmptyState(
                            "No matches",
                            message = "Try a different search or fewer filters.",
                            icon = Icons.Filled.Search,
                            actionLabel = "Clear search and filters",
                            onAction = vm::clearAll,
                        )
                    } else {
                        EmptyState(
                            "Nothing for sale yet",
                            message = "Be the first to list something on your campus.",
                            actionLabel = "Sell something",
                            onAction = { nav.navigateToTab(TopLevelTab.Sell) },
                        )
                    }
                },
            ) { listing ->
                ListingCard(
                    listing = listing,
                    onClick = { nav.navigate(ListingDetailRoute(listing.id)) },
                    onToggleFavorite = if (listing.sellerId != me.id) ({ vm.toggleFavorite(listing) }) else null,
                )
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
            onSort = vm::selectDraftSort,
            onReset = vm::resetDraft,
            onApply = vm::applyFilters,
        )
    }
}

// ---------------------------------------------------------------------------------------------------- search row

@Composable
private fun SearchBarRow(
    text: String,
    onTextChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    filterCount: Int,
    onOpenFilters: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.screen, end = Spacing.sm, top = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Search textbooks, bikes…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = onClear) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(50),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )
        Spacer(Modifier.width(Spacing.xs))
        IconButton(onClick = onOpenFilters) {
            BadgedBox(badge = { if (filterCount > 0) Badge { Text("$filterCount") } }) {
                Icon(TuneIcon, contentDescription = if (filterCount > 0) "Filters, $filterCount active" else "Filters")
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- categories

@Composable
private fun CategoryChips(
    categories: UiState<List<CategoryInfo>>,
    selected: ListingCategory?,
    onSelect: (ListingCategory?) -> Unit,
) {
    // Counts come from the server; until they arrive (or if they fail) the chips still work without them.
    val infos = (categories as? UiState.Content)?.value
        ?: ListingCategory.entries.map { CategoryInfo(it, it.label, activeCount = -1) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
    ) {
        item(key = "all") {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All") })
        }
        items(infos, key = { it.category.name }) { info ->
            val isSelected = info.category == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(info.category) },
                label = {
                    Text(info.label)
                    if (info.activeCount >= 0) {
                        Text(
                            " ${info.activeCount}",
                            color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------- active filters

@Composable
private fun ActiveFilters(
    filters: BrowseFilters,
    searching: Boolean,
    onRemoveConditions: () -> Unit,
    onRemovePrice: () -> Unit,
    onRemoveSort: () -> Unit,
    onClearAll: () -> Unit,
) {
    if (filters.sheetCount == 0) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (filters.conditions.isNotEmpty()) {
            item(key = "conditions") {
                RemovableChip(conditionsLabel(filters.conditions), "Remove condition filter", onRemoveConditions)
            }
        }
        if (filters.hasPrice) {
            item(key = "price") { RemovableChip(priceLabel(filters.minPriceCents, filters.maxPriceCents), "Remove price filter", onRemovePrice) }
        }
        filters.sort?.let { sort ->
            item(key = "sort") { RemovableChip(sort.label(searching), "Remove sort", onRemoveSort) }
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

// ---------------------------------------------------------------------------------------------------- filter sheet

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    draft: FilterDraft,
    onDismiss: () -> Unit,
    onToggleCondition: (ListingCondition) -> Unit,
    onMinPrice: (String) -> Unit,
    onMaxPrice: (String) -> Unit,
    onSort: (ListingSort) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Boolean,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onReset) { Text("Reset") }
            }

            SheetSection("Sort by") {
                draft.sortOptions.forEach { option ->
                    FilterChip(
                        selected = option == draft.selectedSort,
                        onClick = { onSort(option) },
                        label = { Text(option.label(draft.searching)) },
                    )
                }
            }

            SheetSection("Condition") {
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

            Text("Price", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                PriceField(
                    value = draft.minPrice,
                    onValueChange = onMinPrice,
                    label = "Min",
                    error = draft.fieldErrors["minPrice"],
                    modifier = Modifier.weight(1f),
                )
                PriceField(
                    value = draft.maxPrice,
                    onValueChange = onMaxPrice,
                    label = "Max",
                    error = draft.fieldErrors["maxPrice"],
                    imeAction = ImeAction.Done,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(Spacing.xs))
            PrimaryButton(
                "Show results",
                onClick = {
                    if (onApply()) scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) { content() }
    }
}
