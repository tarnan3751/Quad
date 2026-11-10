package com.campusmarket.app.feature.listing

import com.campusmarket.app.data.Harness
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.app.ui.state.valueOrNull
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.OkResponse
import com.campusmarket.core.Page
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ListingDetailAndListsViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val pageSerializer = Page.serializer(ListingSummary.serializer())

    @Test
    fun favoriteOnDetailUpdatesCountAndOtherScreens() = runTest {
        val h = Harness { req ->
            when {
                req.url.encodedPath == ApiRoutes.listing("l1") -> respondJson(ListingDetail.serializer(), detail(favoriteCount = 4))
                req.url.encodedPath == ApiRoutes.LISTINGS -> respondJson(pageSerializer, Page(listOf(summary("l1"), summary("l2")), null))
                req.url.encodedPath == ApiRoutes.listingFavorite("l1") -> respondJson(OkResponse.serializer(), OkResponse())
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        val messages = UserMessages()
        val sync = ListingSync(h.api, messages, backgroundScope)
        val grid = com.campusmarket.app.feature.browse.BrowseViewModel(h.api, sync, messages)
        val vm = ListingDetailViewModel(ListingDetailRoute("l1"), h.api, messages, sync)

        vm.listing.awaitValue { it is UiState.Content }
        grid.listings.state.awaitValue { it.loaded }

        vm.toggleFavorite()
        val favorited = vm.listing.awaitValue { it.valueOrNull?.favorited == true }.valueOrNull!!
        assertEquals(5, favorited.favoriteCount)
        // The grid behind the detail screen shows the filled heart too.
        grid.listings.state.awaitValue { s -> s.items.first { it.id == "l1" }.favorited }
        awaitCondition { h.requests.any { it.url.encodedPath == ApiRoutes.listingFavorite("l1") && it.method == HttpMethod.Post } }
    }

    @Test
    fun ownersCannotFavoriteTheirOwnListing() = runTest {
        val h = Harness { respondJson(ListingDetail.serializer(), detail(isMine = true)) }
        val messages = UserMessages()
        val vm = ListingDetailViewModel(ListingDetailRoute("l1"), h.api, messages, ListingSync(h.api, messages, backgroundScope))
        vm.listing.awaitValue { it is UiState.Content }

        vm.toggleFavorite()
        runCurrent()
        assertFalse(vm.listing.value.valueOrNull!!.favorited)
        assertEquals(1, h.requests.size)
    }

    @Test
    fun deleteConfirmsThenLeavesAndRemovesTheListingEverywhere() = runTest {
        val h = Harness { req ->
            when {
                req.url.encodedPath == ApiRoutes.listing("l1") && req.method == HttpMethod.Get -> respondJson(ListingDetail.serializer(), detail(isMine = true))
                req.url.encodedPath == ApiRoutes.listing("l1") && req.method == HttpMethod.Delete -> respondJson(OkResponse.serializer(), OkResponse())
                req.url.encodedPath == ApiRoutes.MY_LISTINGS -> respondJson(pageSerializer, Page(listOf(summary("l1"), summary("l2")), null))
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        val messages = UserMessages()
        val sync = ListingSync(h.api, messages, backgroundScope)
        val mine = MyListingsViewModel(h.api, sync)
        val vm = ListingDetailViewModel(ListingDetailRoute("l1"), h.api, messages, sync)
        vm.listing.awaitValue { it is UiState.Content }
        mine.listings.state.awaitValue { it.loaded }

        vm.askDelete()
        assertTrue(vm.ui.value.confirmingDelete)
        vm.delete()
        runCurrent()
        assertEquals(ListingDetailEvent.Deleted, vm.events.flow.awaitValue())
        assertEquals("Listing deleted", messages.messages.awaitValue().text)
        mine.listings.state.awaitValue { s -> s.items.map { it.id } == listOf("l2") }
        assertFalse(vm.ui.value.deleting)
    }

    @Test
    fun deleteConflictKeepsTheScreenAndShowsTheNewStatus() = runTest {
        var status = ListingStatus.ACTIVE
        val h = Harness { req ->
            if (req.method == HttpMethod.Delete) {
                status = ListingStatus.RESERVED
                respondError(HttpStatusCode.Conflict, ErrorCodes.CONFLICT, "This listing has an order in progress and can't be deleted")
            } else {
                respondJson(ListingDetail.serializer(), detail(isMine = true, status = status))
            }
        }
        val messages = UserMessages()
        val vm = ListingDetailViewModel(ListingDetailRoute("l1"), h.api, messages, ListingSync(h.api, messages, backgroundScope))
        vm.listing.awaitValue { it is UiState.Content }

        vm.askDelete()
        vm.delete()
        assertEquals("This listing has an order in progress and can't be deleted", messages.messages.awaitValue().text)
        val refreshed = vm.listing.awaitValue { it.valueOrNull?.status == ListingStatus.RESERVED }.valueOrNull!!
        assertFalse(refreshed.canDelete)
        assertFalse(refreshed.canEdit)
        val ui = vm.ui.awaitValue { !it.deleting }
        assertFalse(ui.confirmingDelete)
    }

    @Test
    fun actionsFollowStatusAndOwnership() {
        val active = detail()
        assertTrue(active.canBuy && active.canMessage && !active.canEdit && !active.canDelete)
        val reserved = detail(status = ListingStatus.RESERVED)
        assertTrue(!reserved.canBuy && reserved.canMessage)
        val sold = detail(status = ListingStatus.SOLD)
        assertTrue(!sold.canBuy && !sold.canMessage)
        val mine = detail(isMine = true)
        assertTrue(mine.canEdit && mine.canDelete && !mine.canBuy && !mine.canMessage)
        val removed = detail(isMine = true, status = ListingStatus.REMOVED)
        assertTrue(!removed.canEdit && removed.canDelete)
    }

    @Test
    fun myListingsStatusFilterRestartsPaging() = runTest {
        val h = Harness { req ->
            val status = req.url.parameters["status"]
            val items = if (status == "SOLD") listOf(summary("s1", status = ListingStatus.SOLD)) else listOf(summary("a1"), summary("s1", status = ListingStatus.SOLD))
            respondJson(pageSerializer, Page(items, null))
        }
        val messages = UserMessages()
        val vm = MyListingsViewModel(h.api, ListingSync(h.api, messages, backgroundScope))
        vm.listings.state.awaitValue { it.loaded && it.items.size == 2 }
        assertNull(h.requests.last().url.parameters["status"])

        vm.setFilter(ListingStatus.SOLD)
        runCurrent()
        vm.listings.state.awaitValue { it.loaded && it.items.size == 1 }
        assertEquals("SOLD", h.requests.last().url.parameters["status"])
        assertEquals(ListingStatus.SOLD, vm.filter.value)
    }

    @Test
    fun unfavoritingFromFavoritesRemovesTheTileAndOffersUndo() = runTest {
        val h = Harness { req ->
            when (req.url.encodedPath) {
                ApiRoutes.MY_FAVORITES -> respondJson(pageSerializer, Page(listOf(summary("f1", favorited = true), summary("f2", favorited = true)), null))
                else -> respondJson(OkResponse.serializer(), OkResponse())
            }
        }
        val messages = UserMessages()
        val vm = FavoritesViewModel(h.api, ListingSync(h.api, messages, backgroundScope), messages)
        vm.favorites.state.awaitValue { it.loaded }

        vm.unfavorite(vm.favorites.state.value.items.first())
        vm.favorites.state.awaitValue { s -> s.items.map { it.id } == listOf("f2") }
        val message = messages.messages.awaitValue()
        assertEquals("Removed from favorites", message.text)
        assertEquals("Undo", message.actionLabel)
        awaitCondition { h.requests.any { it.url.encodedPath == ApiRoutes.listingFavorite("f1") && it.method == HttpMethod.Delete } }
    }
}
