package com.campusmarket.app.feature.listing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.ui.state.EventQueue
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.app.ui.state.load
import com.campusmarket.app.ui.state.updateContent
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.ListingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Transient UI flags of the detail screen (the listing itself is in [ListingDetailViewModel.listing]). */
data class ListingDetailUi(
    val refreshing: Boolean = false,
    val confirmingDelete: Boolean = false,
    val deleting: Boolean = false,
    val unblocking: Boolean = false,
)

sealed interface ListingDetailEvent {
    /** The listing was deleted: leave the screen. */
    data object Deleted : ListingDetailEvent
}

/** A block (by either side) stands between the viewer and the seller: the server refuses messages and orders. */
val ListingDetail.isBlocked: Boolean get() = !isMine && (blocked || seller.blockedByMe)

/** What the viewer may do with a listing, from its status, ownership and blocks (mirrors the server's rules). */
val ListingDetail.canBuy: Boolean get() = !isMine && !isBlocked && status == ListingStatus.ACTIVE
val ListingDetail.canMessage: Boolean get() = !isMine && !isBlocked && (status == ListingStatus.ACTIVE || status == ListingStatus.RESERVED)
val ListingDetail.canEdit: Boolean get() = isMine && status == ListingStatus.ACTIVE
val ListingDetail.canDelete: Boolean get() = isMine && (status == ListingStatus.ACTIVE || status == ListingStatus.REMOVED)

class ListingDetailViewModel(
    route: ListingDetailRoute,
    private val api: ApiClient,
    private val messages: UserMessages,
    private val sync: ListingSync,
) : ViewModel() {
    val listingId: String = route.listingId

    private val _listing = MutableStateFlow<UiState<ListingDetail>>(UiState.Loading)
    val listing: StateFlow<UiState<ListingDetail>> = _listing.asStateFlow()

    private val _ui = MutableStateFlow(ListingDetailUi())
    val ui: StateFlow<ListingDetailUi> = _ui.asStateFlow()

    val events = EventQueue<ListingDetailEvent>()

    private var resumedBefore = false

    init {
        reload()
        viewModelScope.launch {
            sync.changes.collect { change ->
                if (change.listingId != listingId) return@collect
                when (change) {
                    is ListingChange.Favorited -> _listing.updateContent { it.withFavorite(change.favorited) }
                    is ListingChange.Saved -> _listing.updateContent { change.listing }
                    is ListingChange.Deleted -> Unit
                }
            }
        }
    }

    fun reload() {
        load(_listing) { api.listing(listingId) }
    }

    /** Pull-to-refresh / back on screen: keeps the listing visible while reloading. */
    fun refresh() {
        if (_ui.value.refreshing) return
        _ui.update { it.copy(refreshing = true) }
        load(_listing, keepContent = true) { api.listing(listingId) }
            .invokeOnCompletion { _ui.update { it.copy(refreshing = false) } }
    }

    /** Called on every resume; refreshes on returning (e.g. from checkout or chat), not on the first show. */
    fun onResume() {
        if (resumedBefore) refresh() else resumedBefore = true
    }

    fun toggleFavorite() {
        val current = (_listing.value as? UiState.Content)?.value ?: return
        if (current.isMine) return
        sync.setFavorite(current.id, !current.favorited)
    }

    fun askDelete() = _ui.update { it.copy(confirmingDelete = true) }

    fun cancelDelete() {
        if (!_ui.value.deleting) _ui.update { it.copy(confirmingDelete = false) }
    }

    fun delete() {
        if (_ui.value.deleting) return
        _ui.update { it.copy(deleting = true) }
        viewModelScope.launch {
            try {
                api.deleteListing(listingId)
                sync.publish(ListingChange.Deleted(listingId))
                messages.show("Listing deleted")
                events.send(ListingDetailEvent.Deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                messages.showError(e)
                // 409: an order started (or it sold) meanwhile; show the current status.
                if (e.status == 409) refresh()
            } finally {
                _ui.update { it.copy(deleting = false, confirmingDelete = false) }
            }
        }
    }

    /** "Unblock" on a listing whose seller I blocked: lifts the block and reloads, so Buy / Message come back. */
    fun unblockSeller() {
        val current = (_listing.value as? UiState.Content)?.value ?: return
        if (!current.seller.blockedByMe || _ui.value.unblocking) return
        _ui.update { it.copy(unblocking = true) }
        viewModelScope.launch {
            try {
                api.unblockUser(current.seller.id)
                messages.show("Unblocked ${current.seller.displayName}")
                load(_listing, keepContent = true) { api.listing(listingId) }.join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                messages.showError(e)
            } finally {
                _ui.update { it.copy(unblocking = false) }
            }
        }
    }
}

private fun ListingDetail.withFavorite(value: Boolean): ListingDetail =
    if (favorited == value) this
    else copy(favorited = value, favoriteCount = (favoriteCount + if (value) 1 else -1).coerceAtLeast(0))
