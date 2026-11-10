package com.campusmarket.app.feature.listing

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.campusmarket.app.App
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.InMemoryTokenStore
import com.campusmarket.app.data.ServerConfig
import com.campusmarket.app.data.TokenStore
import com.campusmarket.app.data.installCampusMarketDefaults
import com.campusmarket.app.data.toStoredSession
import com.campusmarket.app.di.coreModule
import com.campusmarket.app.di.featureModules
import com.campusmarket.app.feature.browse.BrowseViewModel
import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.PickedImage
import com.campusmarket.app.platform.PlatformKind
import com.campusmarket.app.platform.PlatformUrlLauncher
import com.campusmarket.app.platform.createPlatformHttpClient
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.core.AuthResponse
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingSort
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.LoginRequest
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end checks of feature A against a running dev server with the seeded demo data. Skipped (passes trivially)
 * unless `CAMPUS_MARKET_LIVE` is set:
 * ```
 * CAMPUS_MARKET_LIVE=http://localhost:8080 CAMPUS_MARKET_SCREENSHOTS=/tmp/shots \
 *   ./gradlew :shared:jvmTest --tests '*LiveServerTest*'
 * ```
 * Listings created here are deleted again; favorites are restored.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class LiveServerTest {
    private val base: String? = System.getenv("CAMPUS_MARKET_LIVE")?.trimEnd('/')
    private val shots: File = File(System.getenv("CAMPUS_MARKET_SCREENSHOTS") ?: "build/live-screenshots")

    private val env get() = AppEnvironment(isDebug = true, defaultBaseUrl = base!!, platform = PlatformKind.DESKTOP)
    private fun httpClient(): HttpClient = createPlatformHttpClient { installCampusMarketDefaults(debugLogging = false) }

    /** Logins are cached per JVM: the server rate-limits them, and access tokens last 15 minutes (no refresh needed). */
    private fun session(email: String): AuthResponse = sessions.getOrPut(email) {
        runBlocking { ApiClient(httpClient(), ServerConfig(env), InMemoryTokenStore()).login(LoginRequest(email, "password123")) }
    }

    /** A signed-in client for a demo account. */
    private fun api(email: String): ApiClient =
        ApiClient(httpClient(), ServerConfig(env), InMemoryTokenStore(session(email).toStoredSession()))

    private fun live(block: () -> Unit) {
        if (base == null) {
            println("LiveServerTest skipped: set CAMPUS_MARKET_LIVE to run it")
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

    // ------------------------------------------------------------------------------------------------ screenshots

    /** Real App() against the live server, signed in as [email]. [overrides] load last (e.g. to grab a view model). */
    private fun startApp(email: String, overrides: Module = module { }) {
        val auth = session(email)
        startKoin {
            modules(
                listOf(
                    module { single<PlatformUrlLauncher> { PlatformUrlLauncher { true } } },
                    coreModule(env),
                    module {
                        single<Settings> { PreferencesSettings(java.util.prefs.Preferences.userRoot().node("campusmarket-live-test").also { it.clear() }) }
                        single<TokenStore> { InMemoryTokenStore(auth.toStoredSession()) }
                    },
                ) + featureModules + overrides,
            )
        }
    }

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.clickNode(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        waitUntil(timeoutMillis = 15_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        val node = onAllNodes(matcher).onFirst()
        try {
            node.performScrollTo()
        } catch (e: AssertionError) {
        }
        runOnUiThread { node.performClick() }
        waitForIdle()
    }

    private fun ComposeUiTest.click(text: String) = clickNode(hasText(text) and hasClickAction())

    /** Lets images load (real network), then saves the window as a PNG. */
    private fun SkikoComposeUiTest.shot(name: String, settle: Boolean = true) {
        if (settle) {
            repeat(25) {
                Thread.sleep(100)
                waitForIdle()
            }
        }
        shots.mkdirs()
        // The whole scene, including popups (sheets, dialogs, menus).
        ImageIO.write(captureToImage().toAwtImage(), "png", File(shots, "$name.png"))
    }

    /** On failure, saves what was on screen as `<name>-failure.png`. */
    private fun SkikoComposeUiTest.withFailureShot(name: String, block: SkikoComposeUiTest.() -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            runCatching { shot("$name-failure", settle = false) }
            throw e
        }
    }

    @Test
    fun screenshotsAsBuyer() = live {
        startApp("liam@state.edu")
        try {
            runDesktopComposeUiTest(width = 430, height = 900) { withFailureShot("buyer") {
                setContent { App() }
                awaitText("Campbell Biology")
                shot("01-browse")

                clickNode(hasContentDescription("Filters") and hasClickAction())
                awaitText("Show results")
                click("Like new")
                shot("02-filters")
                click("Show results")
                waitForIdle()
                shot("03-browse-filtered")
                click("Clear all")

                awaitText("Campbell Biology")
                clickNode(hasText("Campbell Biology", substring = true) and hasClickAction())
                awaitText("Buy now")
                shot("04-detail")

                clickNode(hasContentDescription("Back") and hasClickAction())
                click("Sell")
                awaitText("Post listing")
                click("Post listing")
                awaitText("Add at least one photo")
                shot("05-sell-errors")

                click("Browse")
                clickNode(hasContentDescription("Favorites") and hasClickAction())
                awaitText("Favorites")
                shot("06-favorites")
            } }
        } finally {
            stopKoin()
        }
    }

    @Test
    fun screenshotsAsSeller() = live {
        startApp("maya@state.edu")
        try {
            runDesktopComposeUiTest(width = 430, height = 900) { withFailureShot("seller") {
                setContent { App() }
                awaitText("Campbell Biology")
                clickNode(hasText("Campbell Biology", substring = true) and hasClickAction())
                awaitText("Edit listing")
                shot("07-owner-detail")
                click("Edit listing")
                awaitText("Save changes")
                shot("08-edit")

                clickNode(hasContentDescription("Back") and hasClickAction())
                clickNode(hasContentDescription("Back") and hasClickAction())
                click("Sell")
                clickNode(hasContentDescription("My listings") and hasClickAction())
                awaitText("Reserved")
                shot("09-my-listings")
            } }
        } finally {
            stopKoin()
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
        startApp("maya@state.edu", module { viewModel { SellViewModel(get(), get(), get()).also { sellVm = it } } })
        val api = api("maya@state.edu")
        val title = "Live UI check ${System.currentTimeMillis() % 100_000}"
        try {
            runDesktopComposeUiTest(width = 430, height = 900) { withFailureShot("sell") {
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
                shot("10-sell-filled")

                click("Post listing")
                awaitText("Edit listing")
                awaitText(title)
                shot("11-created-detail")

                clickNode(hasContentDescription("More options") and hasClickAction())
                click("Delete listing")
                awaitText("Delete this listing?")
                val confirm = onAllNodes(hasText("Delete") and hasClickAction()).onLast()
                runOnUiThread { confirm.performClick() }
                awaitText("Post listing") // back on the (reset) Sell form
                assertTrue(onAllNodesWithText(title).fetchSemanticsNodes().isEmpty())
            } }
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

    @Test
    fun screenshotOwnerSoldListing() = live {
        startApp("maya@state.edu")
        try {
            runDesktopComposeUiTest(width = 430, height = 900) { withFailureShot("sold") {
                setContent { App() }
                awaitText("Campbell Biology")
                click("Sell")
                clickNode(hasContentDescription("My listings") and hasClickAction())
                click("Sold")
                clickNode(hasText("Mini fridge", substring = true) and hasClickAction())
                awaitText("You sold this item.")
                shot("12-owner-sold")
            } }
        } finally {
            stopKoin()
        }
    }

    companion object {
        private val sessions = mutableMapOf<String, AuthResponse>()
    }
}
