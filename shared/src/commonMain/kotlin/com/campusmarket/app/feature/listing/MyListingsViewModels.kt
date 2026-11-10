package com.campusmarket.app.feature.listing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.ui.state.Paginator
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Favorites: newest-favorited first. Un-favoriting removes the tile (with Undo); favoriting elsewhere reloads. */
class FavoritesViewModel(
    private val api: ApiClient,
    private val sync: ListingSync,
    private val messages: UserMessages,
) : ViewModel() {
    val favorites = Paginator(viewModelScope, key = { it.id }) { cursor, limit -> api.myFavorites(cursor, limit) }

    init {
        favorites.onRefreshError = messages::showError // a failed pull-to-refresh (e.g. offline) isn't silent
        favorites.refresh()
        viewModelScope.launch {
            sync.changes.collect { change ->
                when {
                    change is ListingChange.Favorited && !change.favorited ->
                        favorites.update { list -> list.filterNot { it.id == change.listingId } }
                    // Newly favorited (or a failed un-favorite rolled back): reload to get it in the right place.
                    change is ListingChange.Favorited && favorites.state.value.items.none { it.id == change.listingId } ->
                        favorites.refresh()
                    else -> favorites.update { it.applyChange(change) }
                }
            }
        }
    }

    fun unfavorite(listing: ListingSummary) {
        if (sync.setFavorite(listing.id, false)) {
            messages.show("Removed from favorites", actionLabel = "Undo") { sync.setFavorite(listing.id, true) }
        }
    }
}

/** Status filter for my listings; null = everything except deleted. */
val MyListingsFilters: List<ListingStatus?> = listOf(null, ListingStatus.ACTIVE, ListingStatus.RESERVED, ListingStatus.SOLD, ListingStatus.REMOVED)

/** My listings (all statuses but DELETED), filterable by status. Stays in sync with creates, edits and deletes. */
class MyListingsViewModel(
    private val api: ApiClient,
    private val sync: ListingSync,
) : ViewModel() {
    private val _filter = MutableStateFlow<ListingStatus?>(null)
    val filter: StateFlow<ListingStatus?> = _filter.asStateFlow()

    val listings = Paginator(viewModelScope, key = { it.id }) { cursor, limit ->
        api.myListings(status = _filter.value, cursor = cursor, limit = limit)
    }

    init {
        listings.refresh()
        viewModelScope.launch {
            sync.changes.collect { change ->
                val filter = _filter.value
                when {
                    change is ListingChange.Saved && change.created -> listings.refresh()
                    // An edit can't change the status, but keep the filter honest if it ever does.
                    change is ListingChange.Saved && filter != null && change.listing.status != filter ->
                        listings.update { list -> list.filterNot { it.id == change.listingId } }
                    else -> listings.update { it.applyChange(change) }
                }
            }
        }
    }

    fun setFilter(status: ListingStatus?) {
        if (status == _filter.value) return
        _filter.value = status
        listings.reset()
    }
}
