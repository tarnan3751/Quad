package com.campusmarket.app.feature.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ListingQuery
import com.campusmarket.app.feature.listing.ListingSync
import com.campusmarket.app.feature.listing.applyChange
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.fieldErrorsOf
import com.campusmarket.app.ui.state.Paginator
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.app.ui.state.load
import com.campusmarket.core.CategoryInfo
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingSort
import com.campusmarket.core.ListingSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything that narrows the grid apart from the search text. */
data class BrowseFilters(
    val category: ListingCategory? = null,
    val conditions: Set<ListingCondition> = emptySet(),
    val minPriceCents: Int? = null,
    val maxPriceCents: Int? = null,
    /** Null = the server default: best match while searching, newest otherwise. */
    val sort: ListingSort? = null,
) {
    val hasPrice: Boolean get() = minPriceCents != null || maxPriceCents != null

    /** Filters set in the sheet (shown as a badge on the filter button; the category has its own chip row). */
    val sheetCount: Int get() = listOf(conditions.isNotEmpty(), hasPrice, sort != null).count { it }
}

/** Editable copy of the sheet filters while the filter sheet is open. Prices stay as typed text until applied. */
data class FilterDraft(
    val conditions: Set<ListingCondition>,
    val minPrice: String,
    val maxPrice: String,
    val sort: ListingSort?,
    /** Searching enables the "Best match" sort. */
    val searching: Boolean,
    val fieldErrors: Map<String, String> = emptyMap(),
) {
    /** Sort choices offered in the sheet. */
    val sortOptions: List<ListingSort>
        get() = if (searching) listOf(ListingSort.RELEVANCE, ListingSort.NEWEST, ListingSort.PRICE_ASC, ListingSort.PRICE_DESC)
        else listOf(ListingSort.NEWEST, ListingSort.PRICE_ASC, ListingSort.PRICE_DESC)

    /** The option shown as selected (the default one when [sort] is null). */
    val selectedSort: ListingSort get() = sort ?: defaultSort(searching)
}

data class BrowseState(
    /** What's in the search box (applied to [query] after a short pause or on IME search). */
    val searchText: String = "",
    val filters: BrowseFilters = BrowseFilters(),
    /** The query behind the grid right now. Paging restarts whenever it changes. */
    val query: ListingQuery = ListingQuery(),
    /** Non-null while the filter sheet is open. */
    val filterDraft: FilterDraft? = null,
) {
    val isFiltered: Boolean get() = query.q != null || filters != BrowseFilters()
}

/** Default sort the server applies when none is sent. */
internal fun defaultSort(searching: Boolean): ListingSort = if (searching) ListingSort.RELEVANCE else ListingSort.NEWEST

/**
 * Browse tab: debounced search, category chips with counts, filter sheet, paged grid, optimistic favorites.
 * A cursor is only valid for the query that produced it, so every change to the query restarts paging.
 */
class BrowseViewModel(
    private val api: ApiClient,
    private val sync: ListingSync,
    messages: UserMessages,
) : ViewModel() {
    private val _state = MutableStateFlow(BrowseState())
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    private val _categories = MutableStateFlow<UiState<List<CategoryInfo>>>(UiState.Loading)

    /** Categories with ACTIVE counts for my campus. */
    val categories: StateFlow<UiState<List<CategoryInfo>>> = _categories.asStateFlow()

    val listings = Paginator(viewModelScope, key = { it.id }) { cursor, limit ->
        api.listings(_state.value.query, cursor, limit)
    }

    private var searchJob: Job? = null

    init {
        // A failed pull-to-refresh keeps the grid; say why instead of silently dropping the spinner (e.g. offline).
        listings.onRefreshError = messages::showError
        listings.refresh()
        load(_categories) { api.categories() }
        viewModelScope.launch {
            sync.changes.collect { change -> listings.update { it.applyChange(change) } }
        }
        // Pull-to-refresh on the grid also refreshes the category counts. So does any new first load (e.g. "Try again"
        // after starting offline) while the counts are still missing; otherwise the chips never get them back.
        viewModelScope.launch {
            listings.state.map { it.isRefreshing || (it.isLoading && _categories.value is UiState.Error) }
                .distinctUntilChanged()
                .collect { reload -> if (reload) reloadCategories() }
        }
    }

    fun reloadCategories() {
        load(_categories, keepContent = true) { api.categories() }
    }

    // ------------------------------------------------------------------------------------------------ search

    fun onSearchTextChange(text: String) {
        _state.update { it.copy(searchText = text) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            applyQuery()
        }
    }

    /** IME "search": apply now instead of waiting for the debounce. */
    fun submitSearch() = applyQuery()

    fun clearSearch() {
        _state.update { it.copy(searchText = "") }
        applyQuery()
    }

    // ------------------------------------------------------------------------------------------------ filters

    /** Category chip; tapping the selected one again clears it. */
    fun selectCategory(category: ListingCategory?) {
        val current = _state.value.filters.category
        applyQuery(_state.value.filters.copy(category = if (category == current) null else category))
    }

    fun removeConditions() = applyQuery(_state.value.filters.copy(conditions = emptySet()))
    fun removePrice() = applyQuery(_state.value.filters.copy(minPriceCents = null, maxPriceCents = null))
    fun removeSort() = applyQuery(_state.value.filters.copy(sort = null))

    /** Clears search text and every filter. */
    fun clearAll() {
        _state.update { it.copy(searchText = "") }
        applyQuery(BrowseFilters())
    }

    fun openFilters() {
        _state.update { s ->
            val f = s.filters
            s.copy(
                filterDraft = FilterDraft(
                    conditions = f.conditions,
                    minPrice = f.minPriceCents?.let(Format::priceInput).orEmpty(),
                    maxPrice = f.maxPriceCents?.let(Format::priceInput).orEmpty(),
                    sort = f.sort,
                    searching = s.searchText.isNotBlank(),
                ),
            )
        }
    }

    fun dismissFilters() = _state.update { it.copy(filterDraft = null) }

    fun toggleDraftCondition(condition: ListingCondition) = editDraft {
        copy(conditions = if (condition in conditions) conditions - condition else conditions + condition)
    }

    fun onDraftMinPrice(text: String) = editDraft { copy(minPrice = text, fieldErrors = fieldErrors - "minPrice") }
    fun onDraftMaxPrice(text: String) = editDraft { copy(maxPrice = text, fieldErrors = fieldErrors - "maxPrice") }

    /** Picking the default sort stores null so it keeps following the default (e.g. best match once you search). */
    fun selectDraftSort(sort: ListingSort) = editDraft { copy(sort = if (sort == defaultSort(searching)) null else sort) }

    fun resetDraft() = editDraft { copy(conditions = emptySet(), minPrice = "", maxPrice = "", sort = null, fieldErrors = emptyMap()) }

    /**
     * Validates the sheet and, if valid, applies it (paging restarts) and returns true. The sheet stays in state so
     * it can animate away; call [dismissFilters] when it's hidden. Returns false and shows field errors otherwise.
     */
    fun applyFilters(): Boolean {
        val draft = _state.value.filterDraft ?: return false
        val min = draft.minPrice.trim().takeIf { it.isNotEmpty() }?.let(Format::parsePriceCents)
        val max = draft.maxPrice.trim().takeIf { it.isNotEmpty() }?.let(Format::parsePriceCents)
        val errors = fieldErrorsOf(
            "minPrice" to if (draft.minPrice.isNotBlank() && min == null) INVALID_AMOUNT else null,
            "maxPrice" to when {
                draft.maxPrice.isNotBlank() && max == null -> INVALID_AMOUNT
                min != null && max != null && max < min -> "Must be at least the minimum"
                else -> null
            },
        )
        if (errors.isNotEmpty()) {
            editDraft { copy(fieldErrors = errors) }
            return false
        }
        applyQuery(
            _state.value.filters.copy(conditions = draft.conditions, minPriceCents = min, maxPriceCents = max, sort = draft.sort),
        )
        return true
    }

    // ------------------------------------------------------------------------------------------------ favorites

    fun toggleFavorite(listing: ListingSummary) {
        sync.setFavorite(listing.id, !listing.favorited)
    }

    // ------------------------------------------------------------------------------------------------ plumbing

    private inline fun editDraft(crossinline transform: FilterDraft.() -> FilterDraft) =
        _state.update { s -> s.copy(filterDraft = s.filterDraft?.transform()) }

    /** Builds the query from the search box + [filters]; restarts paging only if it actually changed. */
    private fun applyQuery(filters: BrowseFilters = _state.value.filters) {
        searchJob?.cancel()
        val previous = _state.value.query
        val text = _state.value.searchText.trim().replace(WHITESPACE, " ").ifEmpty { null }
        val query = ListingQuery(
            q = text,
            category = filters.category,
            conditions = filters.conditions,
            minPriceCents = filters.minPriceCents,
            maxPriceCents = filters.maxPriceCents,
            sort = filters.sort,
        )
        _state.update { it.copy(filters = filters, query = query) }
        if (query != previous) listings.reset()
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 400L
        private const val INVALID_AMOUNT = "Enter an amount like 20 or 20.50"
        private val WHITESPACE = Regex("\\s+")
    }
}
