package com.campusmarket.app.feature.listing

import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.ListingSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A change to a listing made on one screen that other open screens (grid, favorites, my listings, detail) mirror. */
sealed interface ListingChange {
    val listingId: String

    /**
     * [confirmed]: the server's state (sent again once the call succeeds, or with the old value on a rollback). Lists
     * that reload from the server wait for it; an earlier reload would race the request.
     */
    data class Favorited(override val listingId: String, val favorited: Boolean, val confirmed: Boolean = false) : ListingChange

    /** Created or edited: the server's fresh copy. */
    data class Saved(val listing: ListingDetail, val created: Boolean) : ListingChange {
        override val listingId: String get() = listing.id
    }

    data class Deleted(override val listingId: String) : ListingChange
}

/**
 * Feature-wide (Koin single) hub for listing changes, so a heart tapped on the detail screen is already filled in
 * the grid behind it, and a deleted listing disappears from every list.
 *
 * Favorite toggles run here, in the app scope, so leaving a screen mid-request doesn't cancel the call (or its
 * rollback). They're optimistic: [changes] gets the new value at once and the old one again if the server refuses.
 */
class ListingSync(
    private val api: ApiClient,
    private val messages: UserMessages,
    private val scope: CoroutineScope,
) {
    private val _changes = MutableSharedFlow<ListingChange>(extraBufferCapacity = 64)
    val changes: SharedFlow<ListingChange> = _changes.asSharedFlow()

    /** Listing ids with a favorite call in flight (touched from the UI thread and the app scope, hence atomic). */
    private val inFlight = MutableStateFlow<Set<String>>(emptySet())

    fun publish(change: ListingChange) {
        _changes.tryEmit(change)
    }

    /**
     * Optimistically sets the favorite flag and calls the API. Returns false (and does nothing) while a previous
     * toggle of the same listing is still in flight, so rapid taps can't race each other.
     */
    fun setFavorite(listingId: String, favorited: Boolean): Boolean {
        var claimed = false
        inFlight.update { ids ->
            claimed = listingId !in ids
            if (claimed) ids + listingId else ids
        }
        if (!claimed) return false
        publish(ListingChange.Favorited(listingId, favorited))
        scope.launch {
            try {
                api.setFavorite(listingId, favorited)
                publish(ListingChange.Favorited(listingId, favorited, confirmed = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                publish(ListingChange.Favorited(listingId, !favorited, confirmed = true))
                messages.show(if (favorited) "Couldn't save to favorites. ${e.message}" else "Couldn't remove from favorites. ${e.message}")
            } finally {
                inFlight.update { it - listingId }
            }
        }
        return true
    }
}

/** The grid/list shape of a listing (used to mirror detail edits into lists). */
fun ListingDetail.toSummary(): ListingSummary = ListingSummary(
    id = id,
    title = title,
    priceCents = priceCents,
    currency = currency,
    category = category,
    condition = condition,
    status = status,
    thumbnailUrl = photos.firstOrNull()?.url,
    sellerId = seller.id,
    sellerName = seller.displayName,
    favorited = favorited,
    createdAt = createdAt,
)

/** Applies [change] to a loaded list in place: favorite flags and edits update matching rows, deletions remove them. */
fun List<ListingSummary>.applyChange(change: ListingChange): List<ListingSummary> = when (change) {
    is ListingChange.Favorited -> map { if (it.id == change.listingId) it.copy(favorited = change.favorited) else it }
    is ListingChange.Saved -> {
        val fresh = change.listing.toSummary()
        map { if (it.id == fresh.id) fresh.copy(favorited = it.favorited) else it }
    }
    is ListingChange.Deleted -> filterNot { it.id == change.listingId }
}
