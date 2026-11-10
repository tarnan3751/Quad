package com.campusmarket.app.feature.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.campusmarket.app.App
import com.campusmarket.app.FakeServer
import com.campusmarket.app.UI_TIMEOUT
import com.campusmarket.app.awaitNode
import com.campusmarket.app.awaitText
import com.campusmarket.app.click
import com.campusmarket.app.clickDescription
import com.campusmarket.app.data.HOUR
import com.campusmarket.app.data.bodyText
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.data.testUser
import com.campusmarket.app.feature.orders.buyerUser
import com.campusmarket.app.feature.orders.sellerUser
import com.campusmarket.app.platform.DeepLinks
import com.campusmarket.app.platform.PlatformUrlLauncher
import com.campusmarket.app.shows
import com.campusmarket.app.signedIn
import com.campusmarket.app.startTestApp
import com.campusmarket.app.ui.TimeFormat
import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ChatEvent
import com.campusmarket.core.Conversation
import com.campusmarket.core.CreateRatingRequest
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.Message
import com.campusmarket.core.OkResponse
import com.campusmarket.core.Order
import com.campusmarket.core.OrderRole
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.Page
import com.campusmarket.core.PayoutAccountStatus
import com.campusmarket.core.Rating
import com.campusmarket.core.SendMessageRequest
import com.campusmarket.core.UserPrivate
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.koin.core.context.stopKoin
import org.koin.dsl.module

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

    private fun startApp() {
        val server = FakeServer { req ->
            val path = req.url.encodedPath
            when {
                path == ApiRoutes.ME -> respondJson(UserPrivate.serializer(), testUser)
                path == ApiRoutes.CONVERSATIONS -> respondJson(Page.serializer(Conversation.serializer()), Page(listOf(conversation), null))
                path == ApiRoutes.conversation("c1") -> respondJson(Conversation.serializer(), conversation)
                path == ApiRoutes.conversationMessages("c1") && req.method == HttpMethod.Get ->
                    respondJson(Page.serializer(Message.serializer()), Page(synchronized(history) { history.toList() }, null))
                path == ApiRoutes.conversationMessages("c1") -> {
                    val body = ApiJson.decodeFromString(SendMessageRequest.serializer(), req.bodyText).body
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
                    val r = ApiJson.decodeFromString(CreateRatingRequest.serializer(), req.bodyText)
                    order = order.copy(iHaveRated = true)
                    respondJson(Rating.serializer(), Rating("r1", "o1", buyerUser, sellerUser.id, r.stars, r.comment, createdAt = now))
                }
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        startTestApp(server, signedIn(), launcher = PlatformUrlLauncher { url -> opened += url; true }, overrides = module { single<LiveChat> { live } })
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

    @Test
    fun inboxToConversationSendAndReceiveLive() = runComposeUiTest {
        startApp()
        setContent { App() }

        awaitNode(hasText("Inbox") and hasClickAction())
        click("Inbox")

        // Row: other person, listing, preview, unread badge.
        awaitText("Maya Chen")
        awaitText("See you at the library")
        awaitText("Desk lamp")
        awaitNode(hasContentDescription("2 unread", substring = true))
        click("Maya Chen")

        // Conversation: header, listing strip, history, day separator.
        awaitText("Is it still available?")
        awaitText(TimeFormat().dayLabel(now - 120_000)) // "Today" (or "Yesterday" right after midnight)
        awaitText("Buy")

        onAllNodes(hasSetTextAction()).onFirst().performTextInput("Can I come at 5?")
        clickDescription("Send")
        waitUntil(timeoutMillis = UI_TIMEOUT) { sent.contains("Can I come at 5?") }
        awaitText("Sent")
        waitUntil(timeoutMillis = UI_TIMEOUT) { onAllNodes(shows("Can I come at 5?")).fetchSemanticsNodes().size == 1 }

        // Live message from the seller, then their read receipt.
        live.emit(ChatEvent.MessageCreated(Message("m-live", "c1", sellerUser.id, "Sure, see you then!", System.currentTimeMillis())))
        awaitText("Sure, see you then!")
        live.emit(ChatEvent.MessageCreated(Message("m-live", "c1", sellerUser.id, "Sure, see you then!", System.currentTimeMillis())))
        waitForIdle()
        assertEquals(1, onAllNodes(shows("Sure, see you then!")).fetchSemanticsNodes().size, "de-duplicated by id")

        // Back to the inbox: the row reflects the live message and the read.
        clickDescription("Back")
        awaitText("Sure, see you then!")
    }

    @Test
    fun orderDetailConfirmReceiptThenRate() = runComposeUiTest {
        startApp()
        setContent { App() }
        awaitNode(hasText("Browse") and hasClickAction())

        DeepLinks.handle("campusmarket://orders/o1")
        awaitText("Confirm I received it")
        awaitText("Your payment is held by Quad")
        // the buyer of a paid order reports a problem rather than cancelling (no self-refund)
        awaitText("Report a problem")
        assertEquals(0, onAllNodesWithText("Cancel order").fetchSemanticsNodes().size + onAllNodesWithText("Cancel and", substring = true).fetchSemanticsNodes().size)

        click("Confirm I received it")
        awaitText("Confirm you received it?")
        click("Yes, I received it")
        awaitText("How was trading with Maya?")
        awaitText("Maya Chen has been paid")

        // Tapping a star on the order opens the rating form with it selected.
        clickDescription("Rate 4 stars")
        awaitText("How was trading with Maya Chen?")
        awaitText("Good")
        clickDescription("2 stars")
        awaitText("Not great")
        click("Submit rating")

        awaitText("You rated Maya Chen")
        assertEquals(true, order.iHaveRated)
    }
}
