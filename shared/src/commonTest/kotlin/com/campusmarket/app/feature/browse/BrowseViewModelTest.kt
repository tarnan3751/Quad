package com.campusmarket.app.feature.browse

import com.campusmarket.app.data.Harness
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.feature.listing.ListingSync
import com.campusmarket.app.feature.listing.awaitCondition
import com.campusmarket.app.feature.listing.awaitValue
import com.campusmarket.app.feature.listing.summary
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CategoryInfo
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingSort
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.OkResponse
import com.campusmarket.core.Page
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val pageSerializer = Page.serializer(ListingSummary.serializer())
    private val categories = ListingCategory.entries.map { CategoryInfo(it, it.label, activeCount = if (it == ListingCategory.TEXTBOOKS) 3 else 0) }

    /** Fake `/listings`: page 1 = a, b (cursor c1), page 2 = c; any search returns only "match". */
    private fun TestScope.browse(favoriteStatus: HttpStatusCode = HttpStatusCode.OK): Triple<Harness, BrowseViewModel, UserMessages> {
        val h = Harness { req ->
            when {
                req.url.encodedPath == ApiRoutes.CATEGORIES ->
                    respondJson(ListSerializer(CategoryInfo.serializer()), categories)
                req.url.encodedPath == ApiRoutes.LISTINGS -> {
                    val p = req.url.parameters
                    val page = when {
                        p["q"] != null -> Page(listOf(summary("match", "Calculus textbook")), null)
                        p["cursor"] == "c1" -> Page(listOf(summary("c")), null)
                        else -> Page(listOf(summary("a"), summary("b")), "c1")
                    }
                    respondJson(pageSerializer, page)
                }
                req.url.encodedPath.endsWith("/favorite") ->
                    if (favoriteStatus.value < 300) respondJson(OkResponse.serializer(), OkResponse())
                    else respondError(favoriteStatus, ErrorCodes.INTERNAL, "Server exploded")
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        val messages = UserMessages()
        val vm = BrowseViewModel(h.api, ListingSync(h.api, messages, backgroundScope), messages)
        return Triple(h, vm, messages)
    }

    private fun Harness.listingRequests() = requestsTo(ApiRoutes.LISTINGS)

    @Test
    fun loadsFirstPageAndCategoryCounts() = runTest {
        val (h, vm, _) = browse()

        val paged = vm.listings.state.awaitValue { it.loaded }
        assertEquals(listOf("a", "b"), paged.items.map { it.id })
        assertFalse(paged.endReached)

        val cats = vm.categories.awaitValue { it is UiState.Content }
        assertEquals(3, (cats as UiState.Content).value.first { it.category == ListingCategory.TEXTBOOKS }.activeCount)

        // Default query: no filters, server-default sort.
        val first = h.listingRequests().single().url.parameters
        assertNull(first["q"])
        assertNull(first["sort"])
        assertNull(first["cursor"])
    }

    @Test
    fun retryAfterStartingOfflineAlsoBringsBackCategoryCounts() = runTest {
        var offline = true
        val h = Harness { req ->
            when {
                offline -> respondError(HttpStatusCode.ServiceUnavailable, ErrorCodes.INTERNAL, "Down")
                req.url.encodedPath == ApiRoutes.CATEGORIES -> respondJson(ListSerializer(CategoryInfo.serializer()), categories)
                else -> respondJson(pageSerializer, Page(listOf(summary("a")), null))
            }
        }
        val vm = BrowseViewModel(h.api, ListingSync(h.api, UserMessages(), backgroundScope), UserMessages())
        vm.listings.state.awaitValue { it.error != null }
        vm.categories.awaitValue { it is UiState.Error }

        offline = false
        vm.listings.retry() // "Try again" on the full-screen error
        vm.listings.state.awaitValue { it.loaded }
        val cats = vm.categories.awaitValue { it is UiState.Content }
        assertEquals(3, (cats as UiState.Content).value.first { it.category == ListingCategory.TEXTBOOKS }.activeCount)
    }

    @Test
    fun failedPullToRefreshKeepsTheGridAndSaysWhy() = runTest {
        var offline = false
        val h = Harness { req ->
            when {
                offline -> respondError(HttpStatusCode.ServiceUnavailable, ErrorCodes.INTERNAL, "Server is down")
                req.url.encodedPath == ApiRoutes.CATEGORIES -> respondJson(ListSerializer(CategoryInfo.serializer()), categories)
                else -> respondJson(pageSerializer, Page(listOf(summary("a")), null))
            }
        }
        val messages = UserMessages()
        val vm = BrowseViewModel(h.api, ListingSync(h.api, messages, backgroundScope), messages)
        vm.listings.state.awaitValue { it.loaded }

        offline = true
        vm.listings.refresh()
        assertEquals("Server is down", messages.messages.awaitValue().text)
        assertEquals(listOf("a"), vm.listings.state.value.items.map { it.id })
    }

    @Test
    fun searchIsDebouncedAndRestartsPagingFromPageOne() = runTest {
        val (h, vm, _) = browse()
        vm.listings.state.awaitValue { it.loaded }
        vm.listings.loadMore()
        vm.listings.state.awaitValue { it.items.size == 3 }
        assertEquals("c1", h.listingRequests().last().url.parameters["cursor"])
        val before = h.listingRequests().size

        // Typing quickly: nothing is sent until the pause.
        vm.onSearchTextChange("c")
        vm.onSearchTextChange("ca")
        vm.onSearchTextChange("calc ")
        advanceTimeBy(BrowseViewModel.SEARCH_DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals(before, h.listingRequests().size)
        assertNull(vm.state.value.query.q)

        advanceTimeBy(2)
        runCurrent()
        val results = vm.listings.state.awaitValue { it.loaded && it.items.map { s -> s.id } == listOf("match") }
        assertTrue(results.endReached)

        val searches = h.listingRequests().drop(before)
        assertEquals(1, searches.size, "only the final text is searched")
        assertEquals("calc", searches.single().url.parameters["q"])
        assertNull(searches.single().url.parameters["cursor"], "new query starts from the first page")
        assertTrue(vm.state.value.isFiltered)
    }

    @Test
    fun submitSearchSkipsTheDebounceAndClearingRestoresTheGrid() = runTest {
        val (h, vm, _) = browse()
        vm.listings.state.awaitValue { it.loaded }

        vm.onSearchTextChange("calc")
        vm.submitSearch()
        runCurrent()
        vm.listings.state.awaitValue { it.loaded && it.items.size == 1 }
        assertEquals("calc", h.listingRequests().last().url.parameters["q"])

        vm.clearSearch()
        runCurrent()
        vm.listings.state.awaitValue { it.loaded && it.items.size == 2 }
        assertNull(h.listingRequests().last().url.parameters["q"])
        assertFalse(vm.state.value.isFiltered)
    }

    @Test
    fun unchangedQueryDoesNotReload() = runTest {
        val (h, vm, _) = browse()
        vm.listings.state.awaitValue { it.loaded }
        val before = h.listingRequests().size

        vm.onSearchTextChange("   ")
        advanceTimeBy(BrowseViewModel.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        vm.selectCategory(null) // already "All"
        runCurrent()

        assertEquals(before, h.listingRequests().size)
    }

    @Test
    fun categoryChipFiltersAndTogglesOff() = runTest {
        val (h, vm, _) = browse()
        vm.listings.state.awaitValue { it.loaded }

        vm.selectCategory(ListingCategory.TEXTBOOKS)
        runCurrent()
        vm.listings.state.awaitValue { it.loaded }
        assertEquals("TEXTBOOKS", h.listingRequests().last().url.parameters["category"])
        assertEquals(ListingCategory.TEXTBOOKS, vm.state.value.filters.category)

        vm.selectCategory(ListingCategory.TEXTBOOKS)
        runCurrent()
        vm.listings.state.awaitValue { it.loaded }
        assertNull(h.listingRequests().last().url.parameters["category"])
        assertNull(vm.state.value.filters.category)
    }

    @Test
    fun filterSheetAppliesConditionsPriceAndSort() = runTest {
        val (h, vm, _) = browse()
        vm.listings.state.awaitValue { it.loaded }
        vm.listings.loadMore()
        vm.listings.state.awaitValue { it.endReached }

        vm.openFilters()
        assertEquals(listOf(ListingSort.NEWEST, ListingSort.PRICE_ASC, ListingSort.PRICE_DESC), vm.state.value.filterDraft!!.sortOptions)
        vm.toggleDraftCondition(ListingCondition.GOOD)
        vm.toggleDraftCondition(ListingCondition.LIKE_NEW)
        vm.toggleDraftCondition(ListingCondition.FAIR)
        vm.toggleDraftCondition(ListingCondition.FAIR) // toggled back off
        vm.onDraftMinPrice("10")
        vm.onDraftMaxPrice("49.99")
        vm.selectDraftSort(ListingSort.PRICE_ASC)
        assertTrue(vm.applyFilters())
        vm.dismissFilters()
        runCurrent()
        vm.listings.state.awaitValue { it.loaded && it.items.size == 2 }

        val params = h.listingRequests().last().url.parameters
        assertEquals(setOf("GOOD", "LIKE_NEW"), params["condition"]!!.split(",").toSet())
        assertEquals("1000", params["minPrice"])
        assertEquals("4999", params["maxPrice"])
        assertEquals("PRICE_ASC", params["sort"])
        assertNull(params["cursor"], "paging restarts when filters change")
        // Conditions and price are filters (the badge count); the sort has its own menu and doesn't count.
        assertEquals(2, vm.state.value.filters.filterCount)
        assertNull(vm.state.value.filterDraft)

        // Removing a single filter from its chip.
        vm.removePrice()
        runCurrent()
        vm.listings.state.awaitValue { it.loaded }
        assertNull(h.listingRequests().last().url.parameters["minPrice"])
        assertEquals(1, vm.state.value.filters.filterCount)
    }

    @Test
    fun invalidPriceRangeKeepsTheSheetOpenWithFieldErrors() = runTest {
        val (h, vm, _) = browse()
        vm.listings.state.awaitValue { it.loaded }
        val before = h.listingRequests().size

        vm.openFilters()
        vm.onDraftMinPrice("50")
        vm.onDraftMaxPrice("10")
        assertFalse(vm.applyFilters())
        assertEquals("Must be at least the minimum", vm.state.value.filterDraft!!.fieldErrors["maxPrice"])

        vm.onDraftMinPrice("12.345")
        vm.onDraftMaxPrice("")
        assertFalse(vm.applyFilters())
        assertTrue("minPrice" in vm.state.value.filterDraft!!.fieldErrors)

        runCurrent()
        assertEquals(before, h.listingRequests().size)
        assertEquals(BrowseFilters(), vm.state.value.filters)
    }

    @Test
    fun bestMatchSortIsOfferedOnlyWhileSearchingAndIsTheDefault() = runTest {
        val (_, vm, _) = browse()
        vm.onSearchTextChange("lamp")
        vm.openFilters()
        val draft = vm.state.value.filterDraft!!
        assertEquals(ListingSort.RELEVANCE, draft.sortOptions.first())
        assertEquals(ListingSort.RELEVANCE, draft.selectedSort)

        vm.selectDraftSort(ListingSort.PRICE_DESC)
        assertEquals(ListingSort.PRICE_DESC, vm.state.value.filterDraft!!.sort)
        vm.selectDraftSort(ListingSort.RELEVANCE) // the default → stored as "no sort"
        assertNull(vm.state.value.filterDraft!!.sort)
    }

    @Test
    fun favoriteToggleIsOptimisticAndIgnoresRepeatTapsWhileInFlight() = runTest {
        val (h, vm, _) = browse()
        vm.listings.state.awaitValue { it.loaded }
        val a = vm.listings.state.value.items.first { it.id == "a" }

        vm.toggleFavorite(a)
        vm.toggleFavorite(a) // second tap before the first call finished: ignored
        vm.listings.state.awaitValue { s -> s.items.first { it.id == "a" }.favorited }
        awaitCondition { h.requests.any { it.url.encodedPath == ApiRoutes.listingFavorite("a") } }
        runCurrent()

        val calls = h.requests.toList().filter { it.url.encodedPath == ApiRoutes.listingFavorite("a") }
        assertEquals(listOf(HttpMethod.Post), calls.map { it.method })
        assertTrue(vm.listings.state.value.items.first { it.id == "a" }.favorited)
    }

    @Test
    fun favoriteRollsBackAndExplainsWhenTheServerRefuses() = runTest {
        val (_, vm, messages) = browse(favoriteStatus = HttpStatusCode.InternalServerError)
        vm.listings.state.awaitValue { it.loaded }

        vm.toggleFavorite(vm.listings.state.value.items.first { it.id == "b" })
        vm.listings.state.awaitValue { s -> s.items.first { it.id == "b" }.favorited } // optimistic
        val message = messages.messages.awaitValue()
        assertTrue(message.text.startsWith("Couldn't save to favorites"), message.text)
        vm.listings.state.awaitValue { s -> !s.items.first { it.id == "b" }.favorited } // rolled back
    }
}
