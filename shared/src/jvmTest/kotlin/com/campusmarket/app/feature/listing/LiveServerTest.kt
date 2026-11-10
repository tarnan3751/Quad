package com.campusmarket.app.feature.listing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.campusmarket.app.App
import com.campusmarket.app.LiveServer
import com.campusmarket.app.awaitText
import com.campusmarket.app.click
import com.campusmarket.app.clickDescription
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.awaitCondition
import com.campusmarket.app.data.awaitValue
import com.campusmarket.app.env
import com.campusmarket.app.feature.browse.BrowseViewModel
import com.campusmarket.app.platform.PickedImage
import com.campusmarket.app.startTestApp
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingSort
import com.campusmarket.core.ListingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * View-model round trips of the storefront (browse paging and filters, sell → edit → delete, favorites) against a
 * running dev server with the seeded demo data. They change data (listings are deleted again, favorites restored), so
 * they opt in with `CAMPUS_MARKET_LIVE_API`, not the read-only galleries' `CAMPUS_MARKET_LIVE`:
 * ```
 * CAMPUS_MARKET_LIVE_API=http://127.0.0.1:8080 ./gradlew :shared:jvmTest --tests '*LiveServerTest*'
 * ```
 * The live screenshots of these screens are in `BrandScreenshotTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class LiveServerTest {
    private val base: String? = env("CAMPUS_MARKET_LIVE_API")?.trimEnd('/')

    private fun api(email: String): ApiClient = LiveServer.api(email, base!!)

    private fun live(block: () -> Unit) {
        if (base == null) {
            println("LiveServerTest skipped: set CAMPUS_MARKET_LIVE_API to run it")
            return
        }
        block()
    }

    private fun jpeg(seed: Int): PickedImage {
        val img = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.paint = GradientPaint(0f, 0f, Color(40 + seed * 50, 110, 80), 640f, 480f, Color(200, 90 + seed * 30, 60))
        g.fillRect(0, 0, 640, 480)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return PickedImage(out.toByteArray(), "live-$seed.jpg", "image/jpeg")
    }

    // ------------------------------------------------------------------------------------------------ view models

    @Test
    fun browsePagingFiltersAndSortAgainstServer() = live {
        Dispatchers.setMain(StandardTestDispatcher())
        try {
            runTest(timeout = 60.seconds) {
                val api = api("liam@state.edu")
                val messages = UserMessages()
                val vm = BrowseViewModel(api, ListingSync(api, messages, backgroundScope), messages)

                // Page through everything: no duplicates, only ACTIVE listings.
                var paged = vm.listings.state.awaitValue(15_000) { it.loaded }
                while (!paged.endReached) {
                    vm.listings.loadMore()
                    runCurrent()
                    paged = vm.listings.state.awaitValue(15_000) { !it.isLoadingMore && (it.endReached || it.loadMoreError != null || it.items.size > paged.items.size) }
                    assertEquals(null, paged.loadMoreError)
                }
                val all = paged.items
                assertTrue(all.isNotEmpty())
                assertEquals(all.size, all.map { it.id }.toSet().size, "no duplicates across pages")
                assertTrue(all.all { it.status == ListingStatus.ACTIVE })
                println("browse: ${all.size} listings")

                // Category chip: matches the server's count for that category.
                val counts = (vm.categories.awaitValue(15_000) { it is UiState.Content } as UiState.Content).value
                vm.selectCategory(ListingCategory.CLOTHING)
                runCurrent()
                val clothing = vm.listings.state.awaitValue(15_000) { it.loaded }.items
                assertTrue(clothing.all { it.category == ListingCategory.CLOTHING })
                assertEquals(counts.first { it.category == ListingCategory.CLOTHING }.activeCount, clothing.size)
                vm.selectCategory(null)

                // Filters + sort.
                vm.openFilters()
                vm.toggleDraftCondition(ListingCondition.LIKE_NEW)
                vm.toggleDraftCondition(ListingCondition.GOOD)
                vm.onDraftMinPrice("10")
                vm.selectDraftSort(ListingSort.PRICE_ASC)
                assertTrue(vm.applyFilters())
                runCurrent()
                val filtered = vm.listings.state.awaitValue(15_000) { it.loaded }.items
                assertTrue(filtered.isNotEmpty())
                assertTrue(filtered.all { it.condition in setOf(ListingCondition.LIKE_NEW, ListingCondition.GOOD) && it.priceCents >= 1000 })
                assertEquals(filtered.map { it.priceCents }.sorted(), filtered.map { it.priceCents })

                vm.openFilters()
                vm.selectDraftSort(ListingSort.PRICE_DESC)
                assertTrue(vm.applyFilters())
                runCurrent()
                val desc = vm.listings.state.awaitValue(15_000) { it.loaded }.items
                assertEquals(desc.map { it.priceCents }.sortedDescending(), desc.map { it.priceCents })

                // Search (with the filters cleared).
                vm.clearAll()
                vm.onSearchTextChange("biology")
                vm.submitSearch()
                runCurrent()
                val hits = vm.listings.state.awaitValue(15_000) { it.loaded }.items
                assertTrue(hits.any { it.title.contains("Biology", ignoreCase = true) }, "search finds the biology textbook: $hits")
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun sellEditDeleteRoundTrip() = live {
        Dispatchers.setMain(StandardTestDispatcher())
        val api = api("maya@state.edu")
        var createdId: String? = null
        try {
            runTest(timeout = 90.seconds) {
                val messages = UserMessages()
                val sync = ListingSync(api, messages, backgroundScope)

                // Create with two photos (real multipart upload with progress).
                val sell = SellViewModel(api, messages, sync)
                sell.addPhotos(listOf(jpeg(1), jpeg(2)))
                runCurrent()
                val uploaded = sell.form.awaitValue(30_000) { s -> s.photos.all { it.status != UploadStatus.Uploading } }
                assertTrue(uploaded.photos.all { it.status == UploadStatus.Uploaded }, "uploads: ${uploaded.photos.map { it.status to it.error }}")
                sell.makeCover(uploaded.photos[1].key)
                val photoIds = sell.form.value.photos.map { it.photoId!! }
                sell.onTitle("Live check ${System.currentTimeMillis() % 100_000}")
                sell.onPrice("12.50")
                sell.onCategory(ListingCategory.OTHER)
                sell.onCondition(ListingCondition.FAIR)
                sell.onDescription("Created by LiveServerTest; deleted again at the end.")
                sell.onPickupLocation("Student union")
                sell.submit()
                runCurrent()
                val created = sell.events.flow.awaitValue(15_000) as ListingEditorEvent.Created
                createdId = created.listingId

                val fresh = api.listing(created.listingId)
                assertEquals(photoIds, fresh.photos.map { it.id }, "photo order (cover first) is kept")
                assertEquals(1250, fresh.priceCents)
                assertEquals("Student union", fresh.pickupLocation)
                assertTrue(fresh.isMine)

                // Edit: new price and cover.
                val edit = EditListingViewModel(EditListingRoute(created.listingId), api, messages, sync)
                edit.original.awaitValue(15_000) { it is UiState.Content }
                edit.onPrice("15")
                edit.makeCover(edit.form.value.photos[1].key)
                edit.submit()
                runCurrent()
                assertEquals(ListingEditorEvent.Saved, edit.events.flow.awaitValue(15_000))
                val edited = api.listing(created.listingId)
                assertEquals(1500, edited.priceCents)
                assertEquals(photoIds.reversed(), edited.photos.map { it.id })

                // Delete from the detail screen.
                val detail = ListingDetailViewModel(ListingDetailRoute(created.listingId), api, messages, sync)
                detail.listing.awaitValue(15_000) { it is UiState.Content }
                detail.askDelete()
                detail.delete()
                runCurrent()
                assertEquals(ListingDetailEvent.Deleted, detail.events.flow.awaitValue(15_000))
                assertEquals(ListingStatus.DELETED, api.listing(created.listingId).status)
                createdId = null
            }
        } finally {
            Dispatchers.resetMain()
            createdId?.let { id -> runBlocking { runCatching { api.deleteListing(id) } } }
        }
    }

    @Test
    fun favoriteAndUnfavoriteAgainstServer() = live {
        Dispatchers.setMain(StandardTestDispatcher())
        try {
            runTest(timeout = 60.seconds) {
                val api = api("liam@state.edu")
                val messages = UserMessages()
                val sync = ListingSync(api, messages, backgroundScope)
                val target = api.listings().items.first { !it.favorited }

                sync.setFavorite(target.id, true)
                awaitCondition(10_000) { runBlocking { api.myFavorites().items.any { it.id == target.id } } }
                val favorites = FavoritesViewModel(api, sync, messages)
                val shown = favorites.favorites.state.awaitValue(15_000) { it.loaded }
                assertTrue(shown.items.any { it.id == target.id })
                assertTrue(api.listing(target.id).favorited)

                favorites.unfavorite(shown.items.first { it.id == target.id })
                favorites.favorites.state.awaitValue(5_000) { s -> s.items.none { it.id == target.id } }
                awaitCondition(10_000) { runBlocking { api.myFavorites().items.none { it.id == target.id } } }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * The Sell form end to end through the UI: photos (injected into the screen's view model, since the system picker
     * can't be driven from a test) upload to the server, fields are typed, posting opens the new listing, and
     * deleting it from the detail screen goes back.
     */
    @Test
    fun sellPostAndDeleteThroughTheUi() = live {
        var sellVm: SellViewModel? = null
        startTestApp(
            server = null,
            tokens = LiveServer.tokens("maya@state.edu", base!!),
            baseUrl = base,
            overrides = module { viewModel { SellViewModel(get(), get(), get()).also { sellVm = it } } },
        )
        val api = api("maya@state.edu")
        val title = "Live UI check ${System.currentTimeMillis() % 100_000}"
        try {
            runDesktopComposeUiTest(width = 430, height = 900) {
                setContent { App() }
                awaitText("Campbell Biology")
                click("Sell")
                awaitText("Post listing")
                runOnUiThread { sellVm!!.addPhotos(listOf(jpeg(1), jpeg(2), jpeg(3))) }
                waitUntil(timeoutMillis = 30_000) { sellVm!!.form.value.photos.all { it.status == UploadStatus.Uploaded } }

                onAllNodes(hasSetTextAction() and hasText("Title")).onFirst().performTextInput(title)
                onAllNodes(hasSetTextAction() and hasText("Price")).onFirst().performTextInput("25")
                click("Furniture")
                click("Like new")
                click("Post listing")
                awaitText("Edit listing")
                awaitText(title)

                clickDescription("More options")
                click("Delete listing")
                awaitText("Delete this listing?")
                click("Delete", last = true)
                awaitText("Post listing") // back on the (reset) Sell form
                assertTrue(onAllNodesWithText(title).fetchSemanticsNodes().isEmpty())
            }
        } finally {
            stopKoin()
            // Clean up whatever got created, whether or not the UI got as far as deleting it.
            runBlocking {
                api.myListings(status = ListingStatus.ACTIVE).items.filter { it.title == title }.forEach { runCatching { api.deleteListing(it.id) } }
            }
        }
        runBlocking {
            assertTrue(api.myListings().items.none { it.title == title }, "the listing was deleted")
        }
    }
}
