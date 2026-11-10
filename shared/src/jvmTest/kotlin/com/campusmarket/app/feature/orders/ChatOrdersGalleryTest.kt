package com.campusmarket.app.feature.orders

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.campusmarket.app.App
import com.campusmarket.app.data.InMemoryTokenStore
import com.campusmarket.app.data.StoredSession
import com.campusmarket.app.data.TokenStore
import com.campusmarket.app.data.installCampusMarketDefaults
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.di.coreModule
import com.campusmarket.app.di.featureModules
import com.campusmarket.app.feature.chat.FakeLiveChat
import com.campusmarket.app.feature.chat.LiveChat
import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.DeepLinks
import com.campusmarket.app.platform.PlatformKind
import com.campusmarket.app.platform.PlatformUrlLauncher
import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ChatEvent
import com.campusmarket.core.Conversation
import com.campusmarket.core.DisputeOutcome
import com.campusmarket.core.DisputeReason
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.Message
import com.campusmarket.core.OkResponse
import com.campusmarket.core.Order
import com.campusmarket.core.OrderDispute
import com.campusmarket.core.OrderRole
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.Page
import com.campusmarket.core.PayoutAccountStatus
import com.campusmarket.core.Photo
import com.campusmarket.core.SendMessageRequest
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserPublic
import com.campusmarket.app.data.ChatSocket
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

private const val MIN = 60_000L
private const val HR = 60 * MIN
private const val DAY = 24 * HR

/**
 * Visual gallery of every chat / orders / payouts screen and state (light, dark, 1.3x font), rendered against a
 * stateful fake server so all order statuses and both roles can be shown. Opt-in:
 * ```
 * CAMPUS_MARKET_SCREENSHOTS=/some/dir [CHAT_ORDERS_PHOTOS=/dir/with/jpgs] ./gradlew :shared:jvmTest --tests '*ChatOrdersGalleryTest*'
 * ```
 * Photos (optional) are `<name>.jpg` files: biology, keyboard, headphones, bike, kettle, jacket, camera, desk, bookshelf.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class ChatOrdersGalleryTest {
    private val dir: File? = System.getenv("CAMPUS_MARKET_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File)
    private val photoDir: File? = System.getenv("CHAT_ORDERS_PHOTOS")?.takeIf { it.isNotBlank() }?.let(::File)
    private val only: String? = System.getenv("CHAT_ORDERS_ONLY")?.takeIf { it.isNotBlank() }

    private fun photo(name: String): String? = photoDir?.let { File(it, "$name.jpg") }?.takeIf { it.exists() }?.toURI()?.toString()

    // ------------------------------------------------------------------------------------------------ fake world

    private val now = System.currentTimeMillis()

    private val me = UserPrivate(
        id = "me", email = "liam@state.edu", displayName = "Liam Park", campusDomain = "state.edu",
        joinedAt = now - 200 * DAY, payoutsEnabled = true,
    )

    private fun person(id: String, name: String, rating: Double? = 4.8, count: Int = 12, blocked: Boolean = false) =
        UserPublic(id = id, displayName = name, campusDomain = "state.edu", ratingAverage = rating, ratingCount = if (rating == null) 0 else count, joinedAt = now - 300 * DAY, blockedByMe = blocked)

    private val maya = person("maya", "Maya Chen", 4.9, 23)
    private val jordan = person("jordan", "Jordan Reyes", 4.6, 8)
    private val priya = person("priya", "Priya Shah", null)
    private val noah = person("noah", "Noah Williams", 5.0, 3)
    private val sofia = person("sofia", "Sofia Alvarez", 4.7, 15)
    private val ethan = person("ethan", "Ethan Brooks", 3.9, 5, blocked = true)
    private val meAsPublic = UserPublic(me.id, me.displayName, campusDomain = me.campusDomain, joinedAt = me.joinedAt, ratingAverage = 4.8, ratingCount = 6)

    private fun listing(id: String, title: String, cents: Int, seller: UserPublic, photo: String, status: ListingStatus = ListingStatus.ACTIVE, category: ListingCategory = ListingCategory.ELECTRONICS) =
        ListingSummary(id, title, cents, category = category, condition = ListingCondition.LIKE_NEW, status = status, thumbnailUrl = photo(photo), sellerId = seller.id, sellerName = seller.displayName, createdAt = now - 6 * DAY)

    private val headphones = listing("l-headphones", "Noise-cancelling headphones", 7500, maya, "headphones")
    private val keyboard = listing("l-keyboard", "Mechanical keyboard, brown switches", 4200, jordan, "keyboard")
    private val bike = listing("l-bike", "Road bike, 54cm aluminium frame", 28000, meAsPublic, "bike", category = ListingCategory.SPORTS)
    private val biology = listing("l-biology", "Campbell Biology, 12th edition", 6500, meAsPublic, "biology", category = ListingCategory.TEXTBOOKS)
    private val jacket = listing("l-jacket", "Vintage denim jacket, size M", 3200, sofia, "jacket", ListingStatus.SOLD, ListingCategory.CLOTHING)
    private val kettle = listing("l-kettle", "Electric kettle, 1.7L", 1800, ethan, "kettle", category = ListingCategory.DORM)
    private val camera = listing("l-camera", "Polaroid camera + 20 film sheets", 6500, jordan, "camera", ListingStatus.RESERVED)
    private val desk = listing("l-desk", "Standing desk converter", 8500, sofia, "desk", ListingStatus.RESERVED, ListingCategory.FURNITURE)
    private val bookshelf = listing("l-bookshelf", "Bookshelf, 5 shelves", 2500, noah, "bookshelf", category = ListingCategory.FURNITURE)

    private val conversations = mutableListOf(
        Conversation("c-maya", headphones, maya, "Perfect, see you at the main entrance!", now - 6 * MIN, 2, now - 3 * DAY),
        Conversation("c-priya", bike, priya, "Is the bike still for sale? Could I take it for a quick test ride this weekend?", now - 3 * HR, 1, now - 3 * HR),
        Conversation("c-jordan", keyboard, jordan, "I can do 42, it's basically new.", now - 26 * HR, 0, now - 4 * DAY),
        Conversation("c-noah", biology, noah, null, null, 0, now - 2 * DAY),
        Conversation("c-sofia", jacket, sofia, "Great, thanks! I'll think it over.", now - 9 * DAY, 0, now - 12 * DAY),
        Conversation("c-ethan", kettle, ethan, "ok", now - 40 * DAY, 0, now - 41 * DAY),
    )

    private val messages: MutableMap<String, MutableList<Message>> = mutableMapOf(
        "c-maya" to mutableListOf(
            Message("m1", "c-maya", me.id, "Hi! Are the headphones still available?", now - 3 * DAY),
            Message("m2", "c-maya", maya.id, "Yes they are! Barely used, I still have the box and the case.", now - 3 * DAY + 7 * MIN),
            Message("m3", "c-maya", maya.id, "Battery is great, around 30 hours.", now - 3 * DAY + 8 * MIN),
            Message("m4", "c-maya", me.id, "Would you take $70?", now - DAY - 2 * HR),
            Message("m5", "c-maya", maya.id, "I could do 75, they're basically new.", now - DAY - HR),
            Message("m6", "c-maya", me.id, "Deal. Can we meet at the library?", now - 40 * MIN),
            Message("m7", "c-maya", me.id, "Around 5 works for me.", now - 39 * MIN),
            Message("m8", "c-maya", maya.id, "Sounds good.", now - 7 * MIN),
            Message("m9", "c-maya", maya.id, "Perfect, see you at the main entrance!", now - 6 * MIN),
        ).asReversed().toMutableList(),
        "c-priya" to mutableListOf(
            Message("p1", "c-priya", priya.id, "Is the bike still for sale? Could I take it for a quick test ride this weekend?", now - 3 * HR),
        ),
        "c-jordan" to mutableListOf(
            Message("j1", "c-jordan", me.id, "Would you do 40 for the keyboard?", now - 27 * HR),
            Message("j2", "c-jordan", jordan.id, "I can do 42, it's basically new.", now - 26 * HR),
        ).asReversed().toMutableList(),
        "c-noah" to mutableListOf(),
        "c-sofia" to mutableListOf(Message("s1", "c-sofia", sofia.id, "Great, thanks! I'll think it over.", now - 9 * DAY)),
        "c-ethan" to mutableListOf(Message("e1", "c-ethan", ethan.id, "ok", now - 40 * DAY)),
    )

    private fun order(
        id: String,
        listing: ListingSummary,
        status: OrderStatus,
        role: OrderRole,
        other: UserPublic,
        paidAgo: Long? = 20 * HR,
        rated: Boolean = false,
        dispute: OrderDispute? = null,
        autoReleaseIn: Long? = if (status == OrderStatus.PAID) 52 * HR else null,
    ): Order {
        val created = now - (paidAgo ?: 0) - 5 * MIN
        return Order(
            id = id,
            listing = listing,
            buyer = if (role == OrderRole.BUYER) meAsPublic else other,
            seller = if (role == OrderRole.SELLER) meAsPublic else other,
            amountCents = listing.priceCents,
            feeCents = listing.priceCents * 5 / 100 + 30,
            status = status,
            myRole = role,
            checkoutUrl = if (status == OrderStatus.PENDING_PAYMENT && role == OrderRole.BUYER) "https://checkout.example/$id" else null,
            iHaveRated = rated,
            createdAt = created,
            paidAt = if (status in setOf(OrderStatus.PAID, OrderStatus.DISPUTED, OrderStatus.COMPLETED, OrderStatus.REFUNDED)) now - (paidAgo ?: 0) else null,
            completedAt = if (status == OrderStatus.COMPLETED) now - (paidAgo ?: 0) + 3 * HR else null,
            autoReleaseAt = autoReleaseIn?.let { now + it },
            dispute = dispute,
        )
    }

    private fun dispute(reason: DisputeReason, details: String, outcome: DisputeOutcome? = null, system: Boolean = false, openedAgo: Long = 4 * HR) = OrderDispute(
        id = "d-$reason", reason = reason, details = details, status = if (outcome == null) DisputeStatus.OPEN else DisputeStatus.CLOSED,
        outcome = outcome, openedBySystem = system, openedAt = now - openedAgo, closedAt = if (outcome == null) null else now - HR,
    )

    private val orders = linkedMapOf(
        "b-pending" to order("b-pending", camera, OrderStatus.PENDING_PAYMENT, OrderRole.BUYER, jordan, paidAgo = null),
        "b-paid" to order("b-paid", headphones, OrderStatus.PAID, OrderRole.BUYER, maya),
        "b-disputed" to order("b-disputed", desk, OrderStatus.DISPUTED, OrderRole.BUYER, sofia, dispute = dispute(DisputeReason.ITEM_NOT_AS_DESCRIBED, "The left leg is cracked and it wobbles. The listing said like new.")),
        "b-held" to order("b-held", kettle, OrderStatus.DISPUTED, OrderRole.BUYER, ethan.copy(blockedByMe = false), dispute = dispute(DisputeReason.SELLER_SUSPENDED, "", system = true)),
        "b-completed" to order("b-completed", keyboard, OrderStatus.COMPLETED, OrderRole.BUYER, jordan, paidAgo = 3 * DAY),
        "b-completed-rated" to order("b-completed-rated", bookshelf, OrderStatus.COMPLETED, OrderRole.BUYER, noah, paidAgo = 12 * DAY, rated = true),
        "b-cancelled" to order("b-cancelled", bookshelf.copy(id = "l-bookshelf2"), OrderStatus.CANCELLED, OrderRole.BUYER, noah, paidAgo = null),
        "b-refunded" to order("b-refunded", jacket, OrderStatus.REFUNDED, OrderRole.BUYER, sofia, paidAgo = 6 * DAY, dispute = dispute(DisputeReason.SELLER_NO_SHOW, "Waited 30 minutes at the library, no reply.", DisputeOutcome.REFUNDED_BY_MODERATOR, openedAgo = 5 * DAY)),
        "s-pending" to order("s-pending", bike, OrderStatus.PENDING_PAYMENT, OrderRole.SELLER, priya, paidAgo = null),
        "s-paid" to order("s-paid", biology, OrderStatus.PAID, OrderRole.SELLER, noah, autoReleaseIn = 20 * HR),
        "s-disputed" to order("s-disputed", biology.copy(id = "l-biology2", title = "Organic Chemistry, Clayden 2nd ed", thumbnailUrl = null), OrderStatus.DISPUTED, OrderRole.SELLER, priya, dispute = dispute(DisputeReason.SELLER_NO_SHOW, "We agreed on 3pm at the student union and nobody came.")),
        "s-completed" to order("s-completed", bike.copy(id = "l-bike2", title = "Desk lamp with USB port", priceCents = 1200, thumbnailUrl = null, status = ListingStatus.SOLD), OrderStatus.COMPLETED, OrderRole.SELLER, sofia, paidAgo = 4 * DAY),
        "s-refunded" to order("s-refunded", bike.copy(id = "l-bike3", title = "Mini fridge, 3.2 cu ft", priceCents = 6000, thumbnailUrl = null), OrderStatus.REFUNDED, OrderRole.SELLER, jordan, paidAgo = 8 * DAY, dispute = dispute(DisputeReason.ITEM_NOT_AS_DESCRIBED, "Doesn't get cold.", DisputeOutcome.SELLER_REFUNDED, openedAgo = 7 * DAY)),
    )

    private var payout = PayoutAccountStatus(hasAccount = true, detailsSubmitted = true, payoutsEnabled = true)

    /** Switches for special states. */
    private var inboxMode = Mode.Normal
    private var ordersMode = Mode.Normal
    private var messagesMode = Mode.Normal
    private var sendFails = false
    private var sendSlow = false
    private var payoutsNotEnabledFor: String? = null

    private enum class Mode { Normal, Empty, Error, Slow }

    private val live = FakeLiveChat()

    private fun listingDetail(s: ListingSummary, seller: UserPublic) = ListingDetail(
        id = s.id, title = s.title, description = "Barely used.", priceCents = s.priceCents, category = s.category, condition = s.condition,
        status = s.status, pickupLocation = "Main library entrance", photos = listOfNotNull(s.thumbnailUrl?.let { Photo("p-${s.id}", it) }),
        seller = seller, createdAt = s.createdAt, updatedAt = s.createdAt,
    )

    private fun startApp() {
        val env = AppEnvironment(isDebug = true, defaultBaseUrl = "http://test.local", platform = PlatformKind.DESKTOP)
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            val get = req.method == HttpMethod.Get
            when {
                path == ApiRoutes.ME -> respondJson(UserPrivate.serializer(), me)
                path == ApiRoutes.CONVERSATIONS && get -> when (inboxMode) {
                    Mode.Empty -> respondJson(Page.serializer(Conversation.serializer()), Page(emptyList(), null))
                    Mode.Error -> respondError(HttpStatusCode.ServiceUnavailable, ErrorCodes.INTERNAL, "The server is having a moment. Try again shortly.")
                    Mode.Slow -> { delay(600_000); respondError(HttpStatusCode.ServiceUnavailable, ErrorCodes.INTERNAL) }
                    Mode.Normal -> respondJson(Page.serializer(Conversation.serializer()), Page(conversations.toList(), null))
                }
                path.startsWith(ApiRoutes.CONVERSATIONS + "/") && path.endsWith("/messages") && get -> {
                    val id = path.removePrefix(ApiRoutes.CONVERSATIONS + "/").removeSuffix("/messages")
                    when (messagesMode) {
                        Mode.Slow -> { delay(600_000); respondError(HttpStatusCode.ServiceUnavailable, ErrorCodes.INTERNAL) }
                        Mode.Error -> respondError(HttpStatusCode.ServiceUnavailable, ErrorCodes.INTERNAL, "Couldn't load messages.")
                        else -> respondJson(Page.serializer(Message.serializer()), Page(messages[id].orEmpty().toList(), null))
                    }
                }
                path.startsWith(ApiRoutes.CONVERSATIONS + "/") && path.endsWith("/messages") -> {
                    val id = path.removePrefix(ApiRoutes.CONVERSATIONS + "/").removeSuffix("/messages")
                    val body = ApiJson.decodeFromString(SendMessageRequest.serializer(), req.body.toByteArray().decodeToString()).body
                    if (sendSlow) delay(600_000)
                    if (sendFails) {
                        respondError(HttpStatusCode.ServiceUnavailable, ErrorCodes.INTERNAL, "Couldn't send")
                    } else {
                        val m = Message("sent-${System.nanoTime()}", id, me.id, body, System.currentTimeMillis())
                        messages.getOrPut(id) { mutableListOf() }.add(0, m)
                        respondJson(Message.serializer(), m)
                    }
                }
                path.startsWith(ApiRoutes.CONVERSATIONS + "/") && path.endsWith("/read") -> respondJson(OkResponse.serializer(), OkResponse())
                path.startsWith(ApiRoutes.CONVERSATIONS + "/") -> {
                    val id = path.removePrefix(ApiRoutes.CONVERSATIONS + "/")
                    conversations.firstOrNull { it.id == id }?.let { respondJson(Conversation.serializer(), it) }
                        ?: respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
                }
                path.startsWith(ApiRoutes.LISTINGS + "/") && get -> {
                    val id = path.removePrefix(ApiRoutes.LISTINGS + "/")
                    val all = conversations.map { it.listing to it.otherUser } + orders.values.map { it.listing to it.seller }
                    val hit = all.firstOrNull { it.first.id == id }
                    if (hit == null) respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
                    else respondJson(ListingDetail.serializer(), listingDetail(hit.first, if (hit.first.sellerId == me.id) meAsPublic else hit.second))
                }
                path == ApiRoutes.PAYOUT_ACCOUNT -> respondJson(PayoutAccountStatus.serializer(), payout)
                path == ApiRoutes.ORDERS && get -> {
                    val role = req.url.parameters["role"]
                    when (ordersMode) {
                        Mode.Empty -> respondJson(Page.serializer(Order.serializer()), Page(emptyList(), null))
                        Mode.Error -> respondError(HttpStatusCode.ServiceUnavailable, ErrorCodes.INTERNAL, "The server is having a moment. Try again shortly.")
                        Mode.Slow -> { delay(600_000); respondError(HttpStatusCode.ServiceUnavailable, ErrorCodes.INTERNAL) }
                        Mode.Normal -> respondJson(
                            Page.serializer(Order.serializer()),
                            Page(orders.values.filter { role == null || it.myRole.name.equals(role, ignoreCase = true) }.sortedByDescending { it.createdAt }, null),
                        )
                    }
                }
                path == ApiRoutes.ORDERS -> {
                    if (payoutsNotEnabledFor != null) respondError(HttpStatusCode.Conflict, ErrorCodes.PAYOUTS_NOT_ENABLED, "The seller can't accept payments yet.")
                    else respondJson(Order.serializer(), orders.getValue("b-pending"))
                }
                path.startsWith(ApiRoutes.ORDERS + "/") && get -> {
                    val id = path.removePrefix(ApiRoutes.ORDERS + "/")
                    orders[id]?.let { respondJson(Order.serializer(), it) } ?: respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
                }
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        val session = StoredSession("a1", "r1", System.currentTimeMillis() + 10 * HR, me)
        startKoin {
            modules(
                listOf(
                    module { single<PlatformUrlLauncher> { PlatformUrlLauncher { true } } },
                    coreModule(env),
                    module {
                        single<Settings> { PreferencesSettings(java.util.prefs.Preferences.userRoot().node("campusmarket-chat-orders-gallery").also { it.clear() }) }
                        single<TokenStore> { InMemoryTokenStore(session) }
                        single<HttpClient> { HttpClient(engine) { installCampusMarketDefaults(debugLogging = false) } }
                    },
                ) + featureModules + module { single<LiveChat> { live } },
            )
        }
    }

    // ------------------------------------------------------------------------------------------------ driving

    private fun SkikoComposeUiTest.await(text: String, timeout: Long = 8_000) =
        waitUntil(timeoutMillis = timeout) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun SkikoComposeUiTest.clickNode(matcher: SemanticsMatcher) {
        waitUntil(timeoutMillis = 8_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        val node = onAllNodes(matcher).onFirst()
        runCatching { node.performScrollTo() }
        runOnUiThread { node.performClick() }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.click(text: String, substring: Boolean = false) = clickNode(hasText(text, substring = substring) and hasClickAction())
    private fun SkikoComposeUiTest.clickDesc(desc: String) = clickNode(hasContentDescription(desc, substring = true) and hasClickAction())
    private fun SkikoComposeUiTest.back() = clickDesc("Back")

    private var prefix = ""

    private fun SkikoComposeUiTest.shot(name: String) {
        repeat(8) {
            Thread.sleep(60)
            runCatching { waitForIdle() }
        }
        dir!!.mkdirs()
        ImageIO.write(captureToImage().toAwtImage(), "png", File(dir, "$prefix$name.png"))
    }

    private fun session(dark: Boolean, fontScale: Float = 1f, height: Float = 892f, block: SkikoComposeUiTest.() -> Unit) {
        startApp()
        try {
            runSkikoComposeUiTest(size = Size(412f * 2, height * 2), density = Density(2f, fontScale)) {
                setContent {
                    CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) { App() }
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Inbox") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
                try {
                    block()
                } catch (e: Throwable) {
                    runCatching { shot("zz-failure") }
                    throw e
                }
            }
        } finally {
            stopKoin()
            inboxMode = Mode.Normal; ordersMode = Mode.Normal; messagesMode = Mode.Normal
            sendFails = false; sendSlow = false; payoutsNotEnabledFor = null
            live.status.value = ChatSocket.Status.Connected
        }
    }

    private fun wanted(group: String) = only == null || only.split(',').any { group.startsWith(it) }

    @Test
    fun gallery() {
        if (dir == null) {
            println("ChatOrdersGalleryTest skipped: set CAMPUS_MARKET_SCREENSHOTS")
            return
        }
        for (dark in listOf(false, true)) {
            val t = if (dark) "dark" else "light"
            prefix = "$t-"
            if (wanted("chat")) chat(dark)
            if (wanted("states")) states(dark)
            if (wanted("orders")) ordersGallery(dark)
            if (wanted("checkout")) checkout(dark)
            if (wanted("payouts")) payouts(dark)
        }
        if (wanted("font")) {
            prefix = "font13-"
            session(dark = false, fontScale = 1.3f) {
                click("Inbox"); await("Maya Chen"); shot("inbox")
                click("Maya Chen"); await("Sounds good"); shot("conversation")
                DeepLinks.handle("campusmarket://orders/b-paid"); await("Confirm I received it"); shot("order-paid")
                DeepLinks.handle("campusmarket://orders/s-paid"); await("Campbell Biology"); shot("order-seller-paid")
            }
        }
    }

    private fun chat(dark: Boolean) = session(dark) {
        click("Inbox")
        await("Maya Chen")
        shot("chat-01-inbox")
        click("Maya Chen")
        await("Sounds good")
        shot("chat-02-conversation")
        // Live incoming + then my send failing, and one sending.
        live.emit(ChatEvent.MessageCreated(Message("live-1", "c-maya", maya.id, "Running 5 minutes late, sorry!", System.currentTimeMillis())))
        await("Running 5 minutes late")
        sendFails = true
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("No worries, I'm by the front desk.")
        clickDesc("Send")
        await("Tap to retry", 8_000)
        shot("chat-03-failed-send")
        sendFails = false
        sendSlow = true
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("A long message to see how the bubble wraps when someone writes a lot of text in one go, which happens more than you'd think when arranging a pickup time and place.")
        shot("chat-04-typing")
        clickDesc("Send")
        shot("chat-05-sending")
        live.status.value = ChatSocket.Status.Reconnecting
        mainClock.advanceTimeBy(2_500)
        await("Reconnecting")
        shot("chat-06-reconnecting")
        live.status.value = ChatSocket.Status.Connected
        // Scrolled up when new messages arrive: the jump chip counts them.
        onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(9)
        live.emit(ChatEvent.MessageCreated(Message("live-2", "c-maya", maya.id, "I'm here now", System.currentTimeMillis())))
        live.emit(ChatEvent.MessageCreated(Message("live-3", "c-maya", maya.id, "Blue jacket, by the stairs", System.currentTimeMillis() + 1)))
        await("2 new messages")
        shot("chat-06b-new-messages")
        back()
        await("Priya Shah")
        click("Priya Shah")
        await("test ride")
        shot("chat-07-seller-view")
        back()
        click("Noah Williams")
        await("Campbell Biology")
        shot("chat-08-empty-conversation")
        back()
        click("Ethan Brooks")
        await("Unblock")
        shot("chat-09-blocked")
        back()
        click("Sofia Alvarez")
        await("think it over")
        shot("chat-10-sold-listing")
    }

    private fun states(dark: Boolean) {
        inboxMode = Mode.Empty
        session(dark) { click("Inbox"); await("No messages yet"); shot("state-inbox-empty") }
        inboxMode = Mode.Error
        session(dark) { click("Inbox"); await("Try again"); shot("state-inbox-error") }
        inboxMode = Mode.Slow
        session(dark) { click("Inbox"); Thread.sleep(500); shot("state-inbox-loading") }
        messagesMode = Mode.Slow
        session(dark) { click("Inbox"); await("Maya Chen"); click("Maya Chen"); Thread.sleep(500); shot("state-conversation-loading") }
        ordersMode = Mode.Empty
        session(dark) {
            click("Profile"); click("Orders"); await("No purchases yet"); shot("state-orders-empty-buying")
            click("Selling"); await("No sales yet"); shot("state-orders-empty-selling")
        }
        ordersMode = Mode.Slow
        session(dark) { click("Profile"); click("Orders"); Thread.sleep(500); shot("state-orders-loading") }
    }

    private fun ordersGallery(dark: Boolean) = session(dark) {
        click("Profile")
        click("Orders")
        await("Noise-cancelling")
        shot("orders-01-buying")
        click("Selling")
        await("Campbell Biology")
        shot("orders-02-selling")
        back()

        val ids = listOf(
            "b-pending", "b-paid", "b-disputed", "b-held", "b-completed", "b-completed-rated", "b-cancelled", "b-refunded",
            "s-pending", "s-paid", "s-disputed", "s-completed", "s-refunded",
        )
        for (id in ids) {
            DeepLinks.handle("campusmarket://orders/$id")
            await(orders.getValue(id).listing.title)
            shot("order-$id")
        }
        // Long screens: also the bottom half.
        DeepLinks.handle("campusmarket://orders/b-paid")
        await("Confirm I received it")
        runCatching { onAllNodesWithText("placed ", substring = true).onFirst().performScrollTo() }
        shot("order-b-paid-bottom")
        click("Confirm I received it")
        await("Confirm you received it?")
        shot("order-dialog-confirm")
        click("Not now")
        click("Report a problem")
        await("What went wrong")
        shot("order-report-problem")
        back()
        DeepLinks.handle("campusmarket://orders/s-paid")
        await("Campbell Biology")
        click("Cancel and refund", substring = true)
        await("Refund buyer")
        shot("order-dialog-refund")
        click("Not now")
        DeepLinks.handle("campusmarket://orders/b-completed")
        await("Rate Jordan")
        click("Rate Jordan")
        await("How was trading")
        clickDesc("4 stars")
        shot("order-rate")
    }

    private fun checkout(dark: Boolean) = session(dark) {
        // Checkout: review, then the return from the hosted checkout (pending order).
        click("Inbox")
        await("Maya Chen")
        click("Maya Chen")
        await("Sounds good")
        click("Buy")
        await("What happens next")
        shot("checkout-01-review")
        click("Continue to payment", substring = true)
        await("Polaroid")
        shot("checkout-02-returned-pending")
        back()
        payoutsNotEnabledFor = "l-headphones"
        click("Buy")
        await("What happens next")
        click("Continue to payment", substring = true)
        await("set up payouts", 8_000)
        shot("checkout-03-payouts-not-enabled")
    }

    private fun payouts(dark: Boolean) = session(dark) {
        val stages = listOf(
            "not-started" to PayoutAccountStatus(hasAccount = false),
            "details-needed" to PayoutAccountStatus(hasAccount = true),
            "in-review" to PayoutAccountStatus(hasAccount = true, detailsSubmitted = true),
            "enabled" to PayoutAccountStatus(hasAccount = true, detailsSubmitted = true, payoutsEnabled = true),
        )
        for ((name, status) in stages) {
            payout = status
            DeepLinks.handle("campusmarket://payouts")
            await("How getting paid works")
            shot("payouts-$name")
            back()
            waitForIdle()
        }
    }
}
