package com.campusmarket.app.feature.chat

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.PayoutAccountStatus
import java.io.File
import javax.imageio.ImageIO
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
import com.campusmarket.app.feature.orders.buyerUser
import com.campusmarket.app.feature.orders.sellerUser
import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.DeepLinks
import com.campusmarket.app.platform.PlatformKind
import com.campusmarket.app.platform.PlatformUrlLauncher
import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ChatEvent
import com.campusmarket.core.Conversation
import com.campusmarket.core.CreateRatingRequest
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Message
import com.campusmarket.core.OkResponse
import com.campusmarket.core.Order
import com.campusmarket.core.OrderRole
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.Page
import com.campusmarket.core.Rating
import com.campusmarket.core.SendMessageRequest
import com.campusmarket.core.UserPrivate
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives the real App() through B's flows against a stateful fake server (MockEngine) and a fake live connection:
 * inbox → conversation (history, send, live receive) and order detail via deep link (confirm → rate).
 */
@OptIn(ExperimentalTestApi::class)
class ChatOrdersUiTest {
    private val now = System.currentTimeMillis()
    private val live = FakeLiveChat()
    private val sent = CopyOnWriteArrayList<String>()
    private val opened = CopyOnWriteArrayList<String>()

    private val conversation = Conversation(
        id = "c1",
        listing = listingSummary(sellerId = sellerUser.id, sellerName = sellerUser.displayName),
        otherUser = sellerUser,
        lastMessagePreview = "See you at the library",
        lastMessageAt = now - 60_000,
        unreadCount = 2,
        createdAt = now - HOUR,
    )
    private val history = mutableListOf(
        Message("m2", "c1", sellerUser.id, "See you at the library", now - 60_000),
        Message("m1", "c1", testUser.id, "Is it still available?", now - 120_000),
    )

    @Volatile
    private var order = Order(
        id = "o1",
        listing = conversation.listing,
        buyer = buyerUser,
        seller = sellerUser,
        amountCents = 2500,
        feeCents = 125,
        status = OrderStatus.PAID,
        myRole = OrderRole.BUYER,
        createdAt = now - HOUR,
        paidAt = now - 30 * 60_000,
    )

    private fun startApp(tokens: TokenStore) {
        val env = AppEnvironment(isDebug = true, defaultBaseUrl = "http://test.local", platform = PlatformKind.DESKTOP)
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path == ApiRoutes.ME -> respondJson(UserPrivate.serializer(), testUser)
                path == ApiRoutes.CONVERSATIONS -> respondJson(Page.serializer(Conversation.serializer()), Page(listOf(conversation), null))
                path == ApiRoutes.conversation("c1") -> respondJson(Conversation.serializer(), conversation)
                path == ApiRoutes.conversationMessages("c1") && req.method == HttpMethod.Get ->
                    respondJson(Page.serializer(Message.serializer()), Page(synchronized(history) { history.toList() }, null))
                path == ApiRoutes.conversationMessages("c1") -> {
                    val body = ApiJson.decodeFromString(SendMessageRequest.serializer(), req.body.toByteArray().decodeToString()).body
                    sent += body
                    val m = Message("s${sent.size}", "c1", testUser.id, body, System.currentTimeMillis())
                    synchronized(history) { history.add(0, m) }
                    respondJson(Message.serializer(), m)
                }
                path == ApiRoutes.conversationRead("c1") -> respondJson(OkResponse.serializer(), OkResponse())
                path == ApiRoutes.listing(conversation.listing.id) -> respondJson(ListingDetail.serializer(), listingDetail())
                path == ApiRoutes.PAYOUT_ACCOUNT -> respondJson(PayoutAccountStatus.serializer(), PayoutAccountStatus(hasAccount = true))
                path == ApiRoutes.ORDERS -> respondJson(Page.serializer(Order.serializer()), Page(listOf(order), null))
                path == ApiRoutes.order("o1") -> respondJson(Order.serializer(), order)
                path == ApiRoutes.orderConfirm("o1") -> {
                    order = order.copy(status = OrderStatus.COMPLETED, completedAt = System.currentTimeMillis())
                    respondJson(Order.serializer(), order)
                }
                path == ApiRoutes.orderRating("o1") -> {
                    val r = ApiJson.decodeFromString(CreateRatingRequest.serializer(), req.body.toByteArray().decodeToString())
                    order = order.copy(iHaveRated = true)
                    respondJson(Rating.serializer(), Rating("r1", "o1", buyerUser, sellerUser.id, r.stars, r.comment, createdAt = now))
                }
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        startKoin {
            modules(
                listOf(
                    module { single<PlatformUrlLauncher> { PlatformUrlLauncher { url -> opened += url; true } } },
                    coreModule(env),
                    module {
                        single<Settings> { PreferencesSettings(java.util.prefs.Preferences.userRoot().node("campusmarket-b-ui-test").also { it.clear() }) }
                        single<TokenStore> { tokens }
                        single<HttpClient> { HttpClient(engine) { installCampusMarketDefaults(debugLogging = false) } }
                    },
                ) + featureModules + module { single<LiveChat> { live } },
            )
        }
    }

    private fun listingDetail() = ListingDetail(
        id = conversation.listing.id,
        title = conversation.listing.title,
        description = "Warm LED desk lamp, three brightness levels.",
        priceCents = conversation.listing.priceCents,
        category = conversation.listing.category,
        condition = conversation.listing.condition,
        status = conversation.listing.status,
        pickupLocation = "Library steps",
        photos = emptyList(),
        seller = sellerUser,
        createdAt = now - 5 * HOUR,
        updatedAt = now - 5 * HOUR,
    )

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    private fun signedIn() = InMemoryTokenStore(StoredSession("a1", "r1", System.currentTimeMillis() + HOUR, testUser))

    private fun ComposeUiTest.awaitText(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis = timeoutMillis) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.click(text: String) {
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        val node = onAllNodes(hasText(text) and hasClickAction()).onFirst()
        try {
            node.performScrollTo()
        } catch (e: AssertionError) {
            // not in a scrollable container
        }
        runOnUiThread { node.performClick() }
        waitForIdle()
    }

    private fun ComposeUiTest.clickDescription(description: String) {
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        val node = onAllNodesWithContentDescription(description).onFirst()
        runOnUiThread { node.performClick() }
        waitForIdle()
    }

    @Test
    fun inboxToConversationSendAndReceiveLive() = runComposeUiTest {
        startApp(signedIn())
        setContent { App() }

        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Inbox") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        click("Inbox")

        // Row: other person, listing, preview, unread badge.
        awaitText("Maya Chen")
        awaitText("See you at the library")
        awaitText("Desk lamp")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("2 unread", substring = true).fetchSemanticsNodes().isNotEmpty() }
        click("Maya Chen")

        // Conversation: header, listing strip, history, day separator.
        awaitText("Is it still available?")
        awaitText(ChatFormat.dayLabel(now - 120_000)) // "Today" (or "Yesterday" right after midnight)
        awaitText("Buy")

        onAllNodes(hasSetTextAction()).onFirst().performTextInput("Can I come at 5?")
        clickDescription("Send")
        waitUntil(timeoutMillis = 5_000) { sent.contains("Can I come at 5?") }
        awaitText("Sent")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Can I come at 5?").fetchSemanticsNodes().size == 1 }

        // Live message from the seller, then their read receipt.
        live.emit(ChatEvent.MessageCreated(Message("m-live", "c1", sellerUser.id, "Sure, see you then!", System.currentTimeMillis())))
        awaitText("Sure, see you then!")
        live.emit(ChatEvent.MessageCreated(Message("m-live", "c1", sellerUser.id, "Sure, see you then!", System.currentTimeMillis())))
        waitForIdle()
        assertEquals(1, onAllNodesWithText("Sure, see you then!").fetchSemanticsNodes().size, "de-duplicated by id")

        // Back to the inbox: the row reflects the live message and the read.
        clickDescription("Back")
        awaitText("Sure, see you then!")
    }

    @Test
    fun orderDetailConfirmReceiptThenRate() = runComposeUiTest {
        startApp(signedIn())
        setContent { App() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Browse") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }

        DeepLinks.handle("campusmarket://orders/o1")
        awaitText("Confirm I received it")
        awaitText("Your payment is held securely")
        // the buyer of a paid order reports a problem rather than cancelling (no self-refund)
        awaitText("Report a problem")
        assertEquals(0, onAllNodesWithText("Cancel order").fetchSemanticsNodes().size + onAllNodesWithText("Cancel and", substring = true).fetchSemanticsNodes().size)

        click("Confirm I received it")
        awaitText("Confirm you received it?")
        click("Yes, I received it")
        awaitText("Rate Maya")
        awaitText("the payment was released to Maya Chen")

        click("Rate Maya")
        awaitText("How was trading with Maya Chen?")
        click("Submit rating")
        awaitText("Choose 1 to 5 stars")
        clickDescription("4 stars")
        click("Submit rating")

        awaitText("You rated Maya Chen")
        assertEquals(true, order.iHaveRated)
    }

    /**
     * Phone-sized screenshots of B's screens for visual review. Opt-in:
     * `CAMPUS_MARKET_SCREENSHOTS=/some/dir ./gradlew :shared:jvmTest --tests '*ChatOrdersUiTest*'`.
     */
    @Test
    fun screenshots() {
        val dir = System.getenv("CAMPUS_MARKET_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        dir.mkdirs()
        runSkikoComposeUiTest(size = Size(412f * 2, 860f * 2), density = Density(2f)) {
            fun shot(name: String) {
                waitForIdle()
                Thread.sleep(300) // let ripples / animations settle
                waitForIdle()
                ImageIO.write(captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
            }
            startApp(signedIn())
            setContent { App() }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Inbox") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }

            click("Inbox")
            awaitText("See you at the library")
            shot("1-inbox")

            click("Maya Chen")
            awaitText("Is it still available?")
            onAllNodes(hasSetTextAction()).onFirst().performTextInput("Can I come by at 5?")
            clickDescription("Send")
            waitUntil(timeoutMillis = 5_000) { sent.isNotEmpty() }
            live.emit(ChatEvent.MessageCreated(Message("m-live", "c1", sellerUser.id, "Sure, see you then!", System.currentTimeMillis())))
            awaitText("Sure, see you then!")
            shot("2-conversation")

            click("Buy")
            awaitText("What happens next")
            shot("3-checkout")
            clickDescription("Back")

            DeepLinks.handle("campusmarket://orders/o1")
            awaitText("Confirm I received it")
            shot("4-order-paid")
            click("Confirm I received it")
            awaitText("Confirm you received it?")
            shot("5-confirm-dialog")
            click("Yes, I received it")
            awaitText("Rate Maya")
            shot("6-order-completed")

            click("Rate Maya")
            awaitText("How was trading")
            clickDescription("4 stars")
            shot("7-rate")
            clickDescription("Back")

            DeepLinks.handle("campusmarket://payouts")
            awaitText("Finish setting up payouts")
            shot("8-payouts")
        }
    }
}
