package com.campusmarket.app.feature.listing

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.campusmarket.app.App
import com.campusmarket.app.data.InMemoryTokenStore
import com.campusmarket.app.data.StoredSession
import com.campusmarket.app.data.TokenStore
import com.campusmarket.app.data.installCampusMarketDefaults
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.data.testUser
import com.campusmarket.app.di.coreModule
import com.campusmarket.app.di.featureModules
import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.PlatformKind
import com.campusmarket.app.platform.PickedImage
import com.campusmarket.app.platform.PlatformUrlLauncher
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CategoryInfo
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.OkResponse
import com.campusmarket.core.Page
import com.campusmarket.core.Photo
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserPublic
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.builtins.ListSerializer
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.awt.BasicStroke
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import kotlin.test.Test
import java.awt.Color as AwtColor

/**
 * Screenshots of every browse / listing screen and state (light and dark, phone and landscape, 1.3x font) against a
 * fake server with controlled data: long titles, $5 and $1,250 prices, sold / reserved / removed listings, blocked
 * sellers, missing and broken photos, photos of mixed aspect ratios, loading / empty / error states. Opt-in:
 * ```
 * CAMPUS_MARKET_SCREENSHOTS=/some/dir ./gradlew :shared:jvmTest --tests '*ListingScreenshotTest*'
 * ```
 * Photos come from a local HTTP server on 127.0.0.1 (the app's base URL), so Coil loads them like real ones.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class ListingScreenshotTest {
    private val dir: File? = System.getenv("CAMPUS_MARKET_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File)
    private val now = System.currentTimeMillis()
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private lateinit var photoServer: HttpServer
    private lateinit var base: String

    // ------------------------------------------------------------------------------------------------ fake data

    private val me: UserPrivate = testUser.copy(displayName = "Ada Lovelace", joinedAt = now - 200 * day)
    private val maya = UserPublic("s-maya", "Maya Chen", campusDomain = "uni.edu", ratingAverage = 4.8, ratingCount = 12, joinedAt = now - 400 * day)
    private val jordan = UserPublic("s-jordan", "Jordan Alvarez-Whitfield", campusDomain = "uni.edu", joinedAt = now - 20 * day)
    private val sam = UserPublic("s-sam", "Sam Patel", campusDomain = "uni.edu", ratingAverage = 4.2, ratingCount = 3, joinedAt = now - 90 * day)
    private val blockedSeller = UserPublic("s-blocked", "Riley Brooks", campusDomain = "uni.edu", joinedAt = now - 60 * day, blockedByMe = true)
    private val meAsSeller = UserPublic(me.id, me.displayName, campusDomain = "uni.edu", ratingAverage = 5.0, ratingCount = 2, joinedAt = me.joinedAt)

    private class Fake(
        val detail: ListingDetail,
    ) {
        val summary: ListingSummary get() = detail.toSummary()
    }

    private fun photo(name: String) = Photo("ph-$name", "$base/uploads/$name.jpg")

    private fun listing(
        id: String,
        title: String,
        priceCents: Int,
        category: ListingCategory,
        condition: ListingCondition,
        photos: List<String>,
        seller: UserPublic = maya,
        status: ListingStatus = ListingStatus.ACTIVE,
        age: Long = 2 * hour,
        description: String = "",
        pickup: String = "",
        favorited: Boolean = false,
        favoriteCount: Int = 0,
    ) = Fake(
        ListingDetail(
            id = id,
            title = title,
            description = description,
            priceCents = priceCents,
            category = category,
            condition = condition,
            status = status,
            pickupLocation = pickup,
            photos = photos.map(::photo),
            seller = seller,
            favorited = favorited,
            favoriteCount = favoriteCount,
            isMine = seller.id == me.id,
            createdAt = now - age,
            updatedAt = now - age,
        ),
    )

    private val all: List<Fake> by lazy {
        listOf(
            listing(
                "biology", "Campbell Biology, 12th edition", 6500, ListingCategory.TEXTBOOKS, ListingCondition.GOOD,
                listOf("portrait", "square", "landscape"), age = 25 * minute, favoriteCount = 4,
                description = "Used for BIO 101 last semester. A few highlights in the first three chapters, otherwise clean. " +
                    "No missing pages, cover has a small crease.\n\nHappy to meet anywhere on campus.",
                pickup = "Library steps, main entrance",
            ),
            listing("lamp", "Desk lamp", 500, ListingCategory.DORM, ListingCondition.LIKE_NEW, listOf("landscape"), seller = sam, age = 3 * hour),
            listing(
                "bike", "Trek FX 2 hybrid bike, 54 cm frame, barely ridden, new tires, lights and lock included",
                125000, ListingCategory.BIKES, ListingCondition.LIKE_NEW, listOf("wide", "tall"), seller = jordan, age = 26 * hour,
                description = "Bought in August, ridden maybe ten times. Comes with front and rear lights and a U-lock.",
                pickup = "Bike racks behind North Hall",
            ),
            listing("fridge", "Mini fridge", 8000, ListingCategory.DORM, ListingCondition.FAIR, listOf("square"), age = 3 * day),
            listing("calc", "TI-84 Plus CE graphing calculator", 7000, ListingCategory.ELECTRONICS, ListingCondition.GOOD, listOf("tall"), seller = sam, age = 5 * day),
            listing("tickets", "Spring formal tickets (2)", 4000, ListingCategory.TICKETS, ListingCondition.NEW, emptyList(), seller = jordan, age = 40 * minute),
            listing("chair", "Ergonomic desk chair", 4000, ListingCategory.FURNITURE, ListingCondition.GOOD, listOf("portrait"), seller = meAsSeller, age = 6 * hour, pickup = "West dorms lobby"),
            listing("broken", "Wool winter coat, size M", 3500, ListingCategory.CLOTHING, ListingCondition.LIKE_NEW, listOf("missing"), age = 8 * day),
            listing("rice", "Rice cooker", 2500, ListingCategory.DORM, ListingCondition.GOOD, listOf("landscape"), seller = blockedSeller, age = 9 * hour),
            listing("pads", "Graph paper pads (5)", 600, ListingCategory.OTHER, ListingCondition.NEW, listOf("square"), seller = sam, age = 50 * minute)
                .let { Fake(it.detail.copy(blocked = true, seller = UserPublic("s-casey", "Casey Morgan", campusDomain = "uni.edu", joinedAt = now - 30 * day))) },
            listing("hoodie", "University hoodie", 1800, ListingCategory.CLOTHING, ListingCondition.GOOD, listOf("square"), age = 10 * day),
            listing("racket", "Tennis racket", 2200, ListingCategory.SPORTS, ListingCondition.FAIR, listOf("wide"), seller = sam, age = 12 * day),
            listing("monitor", "27\" 4K monitor", 18000, ListingCategory.ELECTRONICS, ListingCondition.LIKE_NEW, listOf("landscape"), seller = jordan, age = 14 * day),
            // Not in browse (not ACTIVE / mine): reached from favorites and my listings.
            listing("sold", "Organic Chemistry model kit", 1500, ListingCategory.TEXTBOOKS, ListingCondition.GOOD, listOf("square"), status = ListingStatus.SOLD, favorited = true, age = 20 * day),
            listing("reserved", "Standing desk converter", 9000, ListingCategory.FURNITURE, ListingCondition.LIKE_NEW, listOf("landscape"), seller = sam, status = ListingStatus.RESERVED, favorited = true, age = 4 * day),
            listing("my-reserved", "Mechanical keyboard", 6000, ListingCategory.ELECTRONICS, ListingCondition.GOOD, listOf("wide"), seller = meAsSeller, status = ListingStatus.RESERVED, age = 7 * day),
            listing("my-sold", "Psychology 101 textbook", 2000, ListingCategory.TEXTBOOKS, ListingCondition.FAIR, listOf("portrait"), seller = meAsSeller, status = ListingStatus.SOLD, age = 30 * day),
            listing("my-removed", "Concert wristband", 3000, ListingCategory.TICKETS, ListingCondition.NEW, emptyList(), seller = meAsSeller, status = ListingStatus.REMOVED, age = 2 * day),
        )
    }

    private val created: ListingDetail by lazy {
        listing(
            "new1", "IKEA desk lamp, warm white", 125000, ListingCategory.DORM, ListingCondition.LIKE_NEW,
            listOf("square", "landscape"), seller = meAsSeller, age = 0,
        ).detail
    }

    private fun byId(id: String) = all.first { it.detail.id == id }
    private val browseIds = listOf("biology", "lamp", "bike", "fridge", "calc", "tickets", "chair", "broken", "rice", "pads", "hoodie", "racket", "monitor")
    private val favoriteIds = listOf("biology", "sold", "reserved", "bike")
    private val myIds = listOf("chair", "my-reserved", "my-removed", "my-sold")

    /** Knobs a scenario flips before or while it runs. */
    private class Knobs {
        @Volatile var browseGate: CompletableDeferred<Unit>? = null
        @Volatile var detailGate: CompletableDeferred<Unit>? = null
        @Volatile var browseFails = false
        @Volatile var myListingsFails = false
        @Volatile var morePagesGate: CompletableDeferred<Unit>? = null
        @Volatile var morePagesFail = false
        @Volatile var uploadGate: CompletableDeferred<Unit>? = null
        @Volatile var uploads = 0
        @Volatile var sellVm: SellViewModel? = null
    }

    private fun startApp(knobs: Knobs) {
        val env = AppEnvironment(isDebug = true, defaultBaseUrl = base, platform = PlatformKind.DESKTOP)
        val pages = Page.serializer(ListingSummary.serializer())
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            val p = req.url.parameters
            when {
                path == ApiRoutes.ME -> respondJson(UserPrivate.serializer(), me)
                path == ApiRoutes.CATEGORIES -> respondJson(
                    ListSerializer(CategoryInfo.serializer()),
                    ListingCategory.entries.map { c -> CategoryInfo(c, c.label, browseIds.count { byId(it).detail.category == c }) },
                )
                path == ApiRoutes.LISTINGS && req.method == HttpMethod.Get -> {
                    knobs.browseGate?.await()
                    if (knobs.browseFails) return@MockEngine respondError(HttpStatusCode.ServiceUnavailable, ErrorCodes.INTERNAL, "Quad is having trouble right now. Try again in a moment.")
                    val q = p["q"]?.lowercase()
                    val category = p["category"]
                    val conditions = p["condition"]?.split(",")
                    var items = browseIds.map { byId(it).summary }
                        .filter { q == null || it.title.lowercase().contains(q) }
                        .filter { category == null || it.category.name == category }
                        .filter { conditions == null || it.condition.name in conditions }
                    when (p["sort"]) {
                        "PRICE_ASC" -> items = items.sortedBy { it.priceCents }
                        "PRICE_DESC" -> items = items.sortedByDescending { it.priceCents }
                    }
                    // Two pages: 8 then the rest.
                    if (p["cursor"] == "p2") {
                        knobs.morePagesGate?.await()
                        if (knobs.morePagesFail) return@MockEngine respondError(HttpStatusCode.ServiceUnavailable, ErrorCodes.INTERNAL, "Try again")
                        respondJson(pages, Page(items.drop(8), null))
                    } else {
                        respondJson(pages, Page(items.take(8), if (items.size > 8) "p2" else null))
                    }
                }
                path == ApiRoutes.MY_FAVORITES -> respondJson(pages, Page(favoriteIds.map { byId(it).summary.copy(favorited = true) }, null))
                path == ApiRoutes.MY_LISTINGS -> {
                    if (knobs.myListingsFails) return@MockEngine respondError(HttpStatusCode.ServiceUnavailable, ErrorCodes.INTERNAL, "Can't reach Quad. Check your connection and try again.")
                    val status = p["status"]
                    respondJson(pages, Page(myIds.map { byId(it).summary }.filter { status == null || it.status.name == status }, null))
                }
                path.endsWith("/favorite") -> respondJson(OkResponse.serializer(), OkResponse())
                path == ApiRoutes.PHOTOS -> {
                    // 1st and 2nd upload succeed, the 3rd waits for the gate, the 4th is rejected.
                    val n = synchronized(knobs) { ++knobs.uploads }
                    if (n == 3) knobs.uploadGate?.await()
                    if (n == 4) respondError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION, "Bad photo", mapOf("file" to "That file isn't a photo we can use"))
                    else respondJson(Photo.serializer(), photo(listOf("square", "landscape", "portrait", "wide")[(n - 1) % 4]).copy(id = "up$n"), HttpStatusCode.Created)
                }
                path == ApiRoutes.LISTINGS && req.method == HttpMethod.Post -> respondJson(ListingDetail.serializer(), created, HttpStatusCode.Created)
                path == ApiRoutes.listing(created.id) -> respondJson(ListingDetail.serializer(), created)
                path.startsWith("/api/v1/listings/") -> {
                    knobs.detailGate?.await()
                    val id = path.removePrefix("/api/v1/listings/")
                    val fake = all.firstOrNull { it.detail.id == id } ?: return@MockEngine respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "Listing not found")
                    respondJson(ListingDetail.serializer(), fake.detail)
                }
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "Not found")
            }
        }
        startKoin {
            modules(
                listOf(
                    module { single<PlatformUrlLauncher> { PlatformUrlLauncher { true } } },
                    coreModule(env),
                    module {
                        single<Settings> { PreferencesSettings(java.util.prefs.Preferences.userRoot().node("campusmarket-listing-shots").also { it.clear() }) }
                        single<TokenStore> { InMemoryTokenStore(StoredSession("a1", "r1", now + 10 * hour, me)) }
                        single<HttpClient> { HttpClient(engine) { installCampusMarketDefaults(debugLogging = false) } }
                    },
                ) + featureModules + module { viewModel { SellViewModel(get(), get(), get()).also { knobs.sellVm = it } } },
            )
        }
    }

    // ------------------------------------------------------------------------------------------------ photos

    /** Product-ish test photos of mixed aspect ratios (labelled, so crops are easy to judge). */
    private fun startPhotoServer() {
        val specs = mapOf(
            "portrait" to Triple(900, 1200, AwtColor(0x3E7CB1)),
            "landscape" to Triple(1200, 900, AwtColor(0xE0A458)),
            "square" to Triple(1000, 1000, AwtColor(0x6A994E)),
            "wide" to Triple(1600, 700, AwtColor(0x9C6644)),
            "tall" to Triple(700, 1500, AwtColor(0x7B2CBF)),
        )
        val images = specs.mapValues { (name, spec) -> jpeg(name, spec.first, spec.second, spec.third) }
        photoServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/uploads/") { exchange ->
                val name = exchange.requestURI.path.removePrefix("/uploads/").removeSuffix(".jpg")
                val bytes = images[name]
                if (bytes == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    exchange.responseHeaders.add("Content-Type", "image/jpeg")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                exchange.close()
            }
            start()
        }
        base = "http://127.0.0.1:${photoServer.address.port}"
    }

    private fun jpeg(label: String, w: Int, h: Int, color: AwtColor): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = GradientPaint(0f, 0f, color.brighter(), w.toFloat(), h.toFloat(), color.darker())
        g.fillRect(0, 0, w, h)
        // A "product": a rounded block centred, plus a frame so cropping is visible.
        g.color = AwtColor(255, 255, 255, 210)
        val s = minOf(w, h) / 2
        g.fillRoundRect((w - s) / 2, (h - s) / 2, s, s, s / 5, s / 5)
        g.stroke = BasicStroke(12f)
        g.color = AwtColor(255, 255, 255, 140)
        g.drawRect(24, 24, w - 48, h - 48)
        g.color = AwtColor(40, 40, 40)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, s / 6)
        val text = "$label ${w}x$h"
        val fm = g.fontMetrics
        g.drawString(text, (w - fm.stringWidth(text)) / 2, h / 2 + fm.ascent / 3)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private fun picked(seed: Int): PickedImage =
        PickedImage(jpeg("pick $seed", 800, 600, AwtColor(0x3E7CB1 + seed * 0x202020)), "pick$seed.jpg", "image/jpeg")

    // ------------------------------------------------------------------------------------------------ driving

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.awaitNode(matcher: SemanticsMatcher) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.clickNode(matcher: SemanticsMatcher) {
        awaitNode(matcher)
        val node = onAllNodes(matcher).onFirst()
        runCatching { node.performScrollTo() }
        runOnUiThread { node.performClick() }
        waitForIdle()
    }

    private fun ComposeUiTest.click(text: String, substring: Boolean = false) = clickNode(hasText(text, substring = substring) and hasClickAction())
    private fun ComposeUiTest.clickDescription(description: String) = clickNode(hasContentDescription(description, substring = true) and hasClickAction())

    private fun ComposeUiTest.back() = clickDescription("Back")

    /** Scrolls the listing grid (the last lazy container on screen) to the tile with [title] and opens it. */
    private fun ComposeUiTest.open(title: String) {
        list().performScrollToNode(hasText(title, substring = true))
        click(title, substring = true)
    }

    /** The listing grid, or else the last lazy list on screen (my listings). */
    private fun ComposeUiTest.list(): androidx.compose.ui.test.SemanticsNodeInteraction {
        awaitNode(hasScrollToIndexAction())
        if (onAllNodes(hasTestTag(LISTING_GRID_TAG)).fetchSemanticsNodes().isNotEmpty()) return onNode(hasTestTag(LISTING_GRID_TAG))
        val containers = onAllNodes(hasScrollToIndexAction())
        return containers[containers.fetchSemanticsNodes().size - 1]
    }

    private var prefix = ""

    private fun SkikoComposeUiTest.shot(name: String) {
        repeat(20) {
            Thread.sleep(80)
            waitForIdle()
        }
        dir!!.mkdirs()
        ImageIO.write(captureToImage().toAwtImage(), "png", File(dir, "$prefix$name.png"))
    }

    private fun app(
        dark: Boolean,
        tag: String,
        knobs: Knobs = Knobs(),
        landscape: Boolean = false,
        fontScale: Float = 1f,
        block: SkikoComposeUiTest.(Knobs) -> Unit,
    ) {
        startApp(knobs)
        prefix = "${if (dark) "dark" else "light"}-$tag-"
        val size = if (landscape) Size(892f * 2, 412f * 2) else Size(412f * 2, 892f * 2)
        try {
            runSkikoComposeUiTest(size = size, density = Density(2f, fontScale)) {
                setContent {
                    CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) { App() }
                }
                try {
                    block(knobs)
                } catch (e: Throwable) {
                    runCatching { shot("FAILURE") }
                    throw e
                }
            }
        } finally {
            stopKoin()
        }
    }

    @Test
    fun listingScreenshots() {
        if (dir == null) {
            println("ListingScreenshotTest skipped: set CAMPUS_MARKET_SCREENSHOTS")
            return
        }
        startPhotoServer()
        try {
            for (dark in listOf(false, true)) scenarios(dark)
            extraScenarios()
        } finally {
            photoServer.stop(0)
        }
    }

    private fun scenarios(dark: Boolean) {
        // Browse: loading, populated, end of list, filters, search, empty.
        app(dark, "browse", Knobs().apply { browseGate = CompletableDeferred(); morePagesGate = CompletableDeferred() }) { k ->
            waitForIdle()
            shot("01-loading")
            k.browseGate!!.complete(Unit)
            awaitText("Campbell Biology")
            shot("02-feed")
            list().performScrollToIndex(9)
            shot("03a-loading-more")
            k.morePagesGate!!.complete(Unit)
            waitUntil(timeoutMillis = 10_000) { runCatching { list().performScrollToNode(hasText("You've seen everything", substring = true)) }.isSuccess }
            shot("03-feed-end")
            clickDescription("Filters")
            awaitText("Show results")
            click("Like new")
            shot("04-filter-sheet")
            click("Show results")
            awaitText("Desk lamp")
            shot("05-filtered")
            onAllNodes(hasSetTextAction()).onFirst().performTextInput("zzqxv")
            onAllNodes(hasSetTextAction()).onFirst().performImeAction()
            awaitText("No ")
            shot("06-no-results")
            clickDescription("Clear search")
            // A desktop click on the clear button takes focus from the field (a tap on a phone doesn't): focus it again.
            clickNode(hasSetTextAction())
            awaitText("Recent searches")
            shot("07-recent-searches")
        }
        app(dark, "browse-sort", Knobs()) {
            awaitText("Campbell Biology")
            clickDescription("Sort by")
            awaitText("Price: low to high")
            shot("01-sort-menu")
            click("Price: low to high")
            awaitText("Desk lamp")
            shot("02-sorted")
        }
        app(dark, "browse-error", Knobs().apply { browseFails = true }) {
            awaitText("Try again")
            shot("01-error")
        }
        // Detail: someone else's, mine, blocked, sold, reserved, removed; photo viewer; loading.
        app(dark, "detail", Knobs()) { k ->
            awaitText("Campbell Biology")
            k.detailGate = CompletableDeferred()
            open("Campbell Biology")
            waitForIdle()
            shot("00-loading")
            k.detailGate!!.complete(Unit)
            k.detailGate = null
            awaitText("Buy now")
            shot("01-other")
            onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes() // no-op: detail is a plain scroll
            awaitText("Maya Chen")
            runCatching { onAllNodesWithText("Maya Chen").onFirst().performScrollTo() }
            shot("02-other-scrolled")
            clickDescription("photo 1 of")
            waitForIdle()
            shot("03-viewer")
            onNode(hasContentDescription("Photo 1 of 3")).performTouchInput { doubleClick(center + Offset(0f, -200f)) }
            waitForIdle()
            shot("03b-viewer-zoomed")
            clickDescription("Close photos")
            back()
            open("Trek FX 2")
            awaitText("Jordan")
            shot("04-long-title-1250")
            back()
            open("Ergonomic desk chair")
            awaitText("Edit")
            shot("05-mine")
            back()
            open("Rice cooker")
            awaitText("Riley Brooks")
            shot("06-blocked")
            back()
            open("Desk lamp")
            awaitText("Sam Patel")
            shot("07-five-dollars")
            back()
            open("Graph paper pads")
            awaitText("Not available")
            shot("07b-blocked-by-seller")
            back()
            clickDescription("Favorites")
            awaitText("Organic Chemistry")
            shot("08-favorites")
            open("Organic Chemistry")
            awaitText("sold", substring = true)
            shot("09-sold")
            back()
            open("Standing desk")
            awaitText("Sam Patel")
            shot("10-reserved")
        }
        // My listings + its failed refresh, sell and edit.
        app(dark, "mine", Knobs()) { k ->
            awaitText("Campbell Biology")
            click("Sell")
            awaitText("Post listing")
            shot("01-sell")
            click("Post listing")
            awaitText("Add at least one photo")
            shot("02-sell-errors")
            clickDescription("My listings")
            awaitText("Mechanical keyboard")
            shot("03-my-listings")
            open("Concert wristband")
            awaitText("moderator", substring = true)
            shot("04-removed")
            back()
            open("Mechanical keyboard")
            awaitText("order", substring = true)
            shot("05-my-reserved")
            back()
            open("Ergonomic desk chair")
            awaitText("Edit")
            click("Edit listing")
            awaitText("Save changes")
            shot("06-edit")
            onAllNodes(hasSetTextAction()).onFirst().performTextInput(" (barely used)")
            back()
            awaitText("Discard your changes?")
            shot("06b-discard-changes")
            click("Discard")
            back()
            k.myListingsFails = true
            // Pull-to-refresh isn't drivable here; a status filter round trip refreshes the same way.
            click("Sold")
            waitForIdle()
            shot("07-my-listings-error")
        }
    }

    private fun ComposeUiTest.awaitText(text: String, substring: Boolean) =
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, substring = substring, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }

    private fun extraScenarios() {
        app(dark = false, tag = "browse-more-error", knobs = Knobs().apply { morePagesFail = true }) {
            awaitText("Campbell Biology")
            list().performScrollToIndex(9)
            awaitText("Couldn't load more")
            shot("01-retry-footer")
        }
        for (dark in listOf(false, true)) {
            app(dark, "sell-flow", Knobs().apply { uploadGate = CompletableDeferred() }) { k ->
                awaitText("Campbell Biology")
                click("Sell")
                awaitText("Post listing")
                runOnUiThread { k.sellVm!!.addPhotos(listOf(picked(1), picked(2), picked(3), picked(4))) }
                awaitText("Uploading 1 photo")
                shot("01-uploading")
                k.uploadGate!!.complete(Unit)
                awaitText("didn't upload")
                onAllNodes(hasSetTextAction() and hasText("Title")).onFirst().performTextInput("IKEA desk lamp, warm white")
                onAllNodes(hasSetTextAction() and hasText("Price")).onFirst().performTextInput("1250")
                click("Dorm & Kitchen")
                click("Like new")
                shot("02-filled")
                clickDescription("Remove photo 4")
                click("Post listing")
                awaitText("Your listing is live")
                shot("03-posted")
            }
        }
        app(dark = false, tag = "mine-refresh", knobs = Knobs()) { k ->
            awaitText("Campbell Biology")
            click("Sell")
            clickDescription("My listings")
            awaitText("Mechanical keyboard")
            k.myListingsFails = true
            list().performTouchInput { swipeDown(startY = top + 20f, endY = bottom - 20f, durationMillis = 400) }
            awaitText("Can't reach Quad")
            shot("01-refresh-failed")
        }
        // Landscape detail and feed; 1.3x font.
        app(dark = false, tag = "landscape", landscape = true) {
            awaitText("Campbell Biology")
            shot("01-feed")
            open("Campbell Biology")
            awaitText("Buy now")
            shot("02-detail")
        }
        app(dark = false, tag = "font130", fontScale = 1.3f) {
            awaitText("Campbell Biology")
            shot("01-feed")
            clickDescription("Filters")
            awaitText("Show results")
            shot("01b-filter-sheet")
            click("Show results")
            open("Trek FX 2")
            awaitText("Jordan")
            shot("02-detail")
            back()
            click("Sell")
            awaitText("Post listing")
            shot("03-sell")
        }
    }
}
