package com.campusmarket.app.feature.browse

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.campusmarket.app.App
import com.campusmarket.app.data.HOUR
import com.campusmarket.app.data.InMemoryTokenStore
import com.campusmarket.app.data.StoredSession
import com.campusmarket.app.data.TokenStore
import com.campusmarket.app.data.installCampusMarketDefaults
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.data.testUser
import com.campusmarket.app.di.coreModule
import com.campusmarket.app.di.featureModules
import com.campusmarket.app.feature.listing.LISTING_GRID_TAG
import com.campusmarket.app.feature.listing.detail
import com.campusmarket.app.feature.listing.summary
import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.PlatformKind
import com.campusmarket.app.platform.PlatformUrlLauncher
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CategoryInfo
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.OkResponse
import com.campusmarket.core.Page
import com.campusmarket.core.UserPrivate
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.builtins.ListSerializer
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feature A flows through the real App() (navigation, DI, screens) against a fake server:
 * browse → search → filter → listing detail → favorite → back, plus the owner's view and the Sell form.
 */
@OptIn(ExperimentalTestApi::class)
class BrowseFlowSmokeTest {

    private val farFuture = System.currentTimeMillis() + HOUR
    private val requests: MutableList<HttpRequestData> = Collections.synchronizedList(mutableListOf())

    private val biology = summary("l1", "Campbell Biology", priceCents = 6500)
    private val lamp = summary("l2", "Desk lamp with USB port", priceCents = 1200)
    private val calculus = summary("l3", "Calculus textbook", priceCents = 4000)
    private val mine = summary("m1", "My old bike", priceCents = 9000).copy(sellerId = testUser.id, sellerName = testUser.displayName)

    private fun startApp(manyListings: Boolean = false) {
        val env = AppEnvironment(isDebug = true, defaultBaseUrl = "http://test.local", platform = PlatformKind.DESKTOP)
        val pages = Page.serializer(ListingSummary.serializer())
        val engine = MockEngine { req ->
            requests += req
            val path = req.url.encodedPath
            when {
                path == ApiRoutes.ME -> respondJson(UserPrivate.serializer(), testUser)
                path == ApiRoutes.CATEGORIES -> respondJson(
                    ListSerializer(CategoryInfo.serializer()),
                    ListingCategory.entries.map { CategoryInfo(it, it.label, if (it == ListingCategory.TEXTBOOKS) 2 else 0) },
                )
                path == ApiRoutes.LISTINGS && manyListings -> respondJson(pages, Page((1..24).map { summary("item$it") }))
                manyListings && path.startsWith(ApiRoutes.LISTINGS + "/") -> respondJson(ListingDetail.serializer(), detail(id = path.substringAfterLast('/')))
                path == ApiRoutes.LISTINGS -> respondJson(
                    pages,
                    when {
                        req.url.parameters["q"] != null -> Page(listOf(calculus))
                        req.url.parameters["condition"] != null -> Page(listOf(lamp))
                        else -> Page(listOf(biology, lamp, mine))
                    },
                )
                path == ApiRoutes.listing("l1") -> respondJson(ListingDetail.serializer(), detail(id = "l1", favoriteCount = 2))
                path == ApiRoutes.listing("m1") -> respondJson(
                    ListingDetail.serializer(),
                    detail(id = "m1", isMine = true).copy(title = "My old bike", priceCents = 9000),
                )
                path.endsWith("/favorite") -> respondJson(OkResponse.serializer(), OkResponse())
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        startKoin {
            modules(
                listOf(
                    module { single<PlatformUrlLauncher> { PlatformUrlLauncher { true } } },
                    coreModule(env),
                    module {
                        single<Settings> { PreferencesSettings(java.util.prefs.Preferences.userRoot().node("campusmarket-browse-test").also { it.clear() }) }
                        single<TokenStore> { InMemoryTokenStore(StoredSession("a1", "r1", farFuture, testUser)) }
                        single<HttpClient> { HttpClient(engine) { installCampusMarketDefaults(debugLogging = false) } }
                    },
                ) + featureModules,
            )
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    private fun ComposeUiTest.awaitText(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis = timeoutMillis) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.awaitRequest(timeoutMillis: Long = 5_000, predicate: (HttpRequestData) -> Boolean) {
        waitUntil(timeoutMillis = timeoutMillis) { synchronized(requests) { requests.any(predicate) } }
    }

    private fun ComposeUiTest.click(text: String) = clickNode(hasText(text) and hasClickAction())

    private fun ComposeUiTest.clickDescription(description: String) = clickNode(hasContentDescription(description) and hasClickAction())

    private fun ComposeUiTest.clickNode(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        waitUntil(timeoutMillis = 5_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        val node = onAllNodes(matcher).onFirst()
        try {
            node.performScrollTo()
        } catch (e: AssertionError) {
            // not in a scrollable container
        }
        // Navigation must run on the UI thread.
        runOnUiThread { node.performClick() }
        waitForIdle()
    }

    @Test
    fun browseSearchFilterThenOpenDetailAndFavorite() = runComposeUiTest {
        startApp()
        setContent { App() }

        // Grid + category chips with counts.
        awaitText("Campbell Biology")
        awaitText("Desk lamp with USB port")
        awaitText("Textbooks")

        // Debounced search replaces the grid.
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("calc")
        awaitText("Calculus textbook")
        awaitRequest { it.url.parameters["q"] == "calc" }
        clickDescription("Clear search")
        awaitText("Campbell Biology")

        // Filter sheet → condition chip → results; the active filter shows as a removable chip.
        clickDescription("Filters")
        awaitText("Show results")
        click("Good")
        click("Show results")
        awaitRequest { it.url.parameters["condition"] == "GOOD" }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Campbell Biology").fetchSemanticsNodes().isEmpty() }
        clickDescription("Remove condition filter")
        awaitText("Campbell Biology")

        // Detail: photos, price, description, pickup, seller, buyer actions.
        click("Campbell Biology")
        awaitText("12th edition, a few highlights.")
        awaitText("Library steps")
        awaitText("Maya Chen")
        awaitText("Message")
        awaitText("Buy now")
        awaitText("2 favorites")

        clickDescription("Add to favorites")
        awaitRequest { it.method == HttpMethod.Post && it.url.encodedPath == ApiRoutes.listingFavorite("l1") }
        awaitText("3 favorites")

        // Back to the grid: the heart there is filled too.
        clickDescription("Back")
        awaitText("Desk lamp with USB port")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasContentDescription("Remove from favorites")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun gridKeepsItsScrollPositionWhenComingBackFromAListing() = runComposeUiTest {
        startApp(manyListings = true)
        setContent { App() }
        awaitText("Listing item1")

        onNode(hasTestTag(LISTING_GRID_TAG)).performScrollToNode(hasText("Listing item20"))
        click("Listing item20")
        awaitText("Buy now")
        clickDescription("Back")
        awaitText("Listing item20")
        onNode(hasText("Listing item20") and hasClickAction()).assertIsDisplayed()
        assertTrue(onAllNodesWithText("Listing item1").fetchSemanticsNodes().isEmpty(), "still scrolled down, not back at the top")
    }

    @Test
    fun ownerSeesEditAndDeleteInsteadOfBuy() = runComposeUiTest {
        startApp()
        setContent { App() }

        awaitText("My old bike")
        // No heart on my own listing's tile: two hearts for the other two.
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasContentDescription("Add to favorites")).fetchSemanticsNodes().size == 2 }

        click("My old bike")
        awaitText("Edit listing")
        awaitText("Your listing")
        assertTrue(onAllNodesWithText("Message").fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithText("Buy now").fetchSemanticsNodes().isEmpty())

        // Delete lives in the overflow menu, behind a confirmation.
        clickDescription("More options")
        click("Delete listing")
        awaitText("Delete this listing?")
        click("Cancel")

        click("Edit listing")
        awaitText("Save changes")
        awaitText("My old bike")
    }

    @Test
    fun sellFormValidatesBeforePosting() = runComposeUiTest {
        startApp()
        setContent { App() }
        awaitText("Campbell Biology")

        click("Sell")
        awaitText("Post listing")
        click("Post listing")
        awaitText("Add at least one photo")
        awaitText("Choose a category")
        awaitText("Check the highlighted fields.")
        assertEquals(0, synchronized(requests) { requests.count { it.method == HttpMethod.Post } })
    }
}
