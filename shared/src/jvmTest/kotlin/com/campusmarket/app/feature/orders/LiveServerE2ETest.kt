package com.campusmarket.app.feature.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.data.ChatSocket
import com.campusmarket.app.data.InMemoryTokenStore
import com.campusmarket.app.data.ServerConfig
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.data.installCampusMarketDefaults
import com.campusmarket.app.feature.chat.ChatRow
import com.campusmarket.app.feature.chat.ConversationRoute
import com.campusmarket.app.feature.chat.ConversationViewModel
import com.campusmarket.app.feature.chat.InboxViewModel
import com.campusmarket.app.feature.chat.MessageSellerRoute
import com.campusmarket.app.feature.chat.MessageSellerViewModel
import com.campusmarket.app.feature.chat.Receipt
import com.campusmarket.app.feature.chat.SendRestriction
import com.campusmarket.app.feature.chat.SocketLiveChat
import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.ExternalLinks
import com.campusmarket.app.platform.PlatformKind
import com.campusmarket.app.platform.PlatformUrlLauncher
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.app.ui.state.valueOrNull
import com.campusmarket.core.CreateListingRequest
import com.campusmarket.core.CreateRatingRequest
import com.campusmarket.core.DisputeOutcome
import com.campusmarket.core.DisputeReason
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.OpenDisputeRequest
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.OrderRole
import com.campusmarket.core.OrderStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end check of feature B's data flow against a REAL dev server (fake payment gateway), exercising the actual
 * view models, ApiClient and ChatSocket. Skipped unless `CAMPUS_MARKET_LIVE_API` is set:
 *
 * ```
 * CAMPUS_MARKET_LIVE_API=http://localhost:8080 ./gradlew :shared:jvmTest --tests '*LiveServerE2ETest*'
 * ```
 * It registers two throwaway users on a private campus domain (`e2e-<random>.edu`), so the seeded demo data is not
 * touched and nothing it creates is visible to other users. Listings it doesn't sell are deleted at the end.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
class LiveServerE2ETest {
    private val base = System.getenv("CAMPUS_MARKET_LIVE_API")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    @Test
    fun chatOrdersRatingsAndPayoutsAgainstTheRealServer() {
        if (base == null) {
            println("LiveServerE2ETest skipped: set CAMPUS_MARKET_LIVE_API (e.g. http://localhost:8080) to run it.")
            return
        }
        val main = newSingleThreadContext("e2e-main")
        Dispatchers.setMain(main)
        try {
            runBlocking { withTimeout(180_000) { Scenario(base, main).run() } }
        } finally {
            Dispatchers.resetMain()
            main.close()
        }
    }
}

private class Client(base: String, val label: String) {
    val tokens = InMemoryTokenStore()
    val server = ServerConfig(AppEnvironment(isDebug = false, defaultBaseUrl = base, platform = PlatformKind.DESKTOP))
    val http = HttpClient(OkHttp) { installCampusMarketDefaults(debugLogging = false) }
    val api = ApiClient(http, server, tokens)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = SessionManager(api, tokens, scope)
    val socket = ChatSocket(http, server, api, tokens, scope)
    val live = SocketLiveChat(socket)
    val messages = UserMessages()
    val opened = java.util.concurrent.CopyOnWriteArrayList<String>()
    val links = ExternalLinks(PlatformUrlLauncher { url -> opened += url; true }, server)
    val id get() = session.currentUser!!.id

    fun close() {
        scope.cancel()
        http.close()
    }
}

private class Scenario(private val base: String, private val main: ExecutorCoroutineDispatcher) {
    private val run = Random.nextInt(100_000, 999_999).toString()
    private val domain = "e2e-$run.edu"
    private val browser = HttpClient(OkHttp) // plays the user's web browser on the fake Stripe pages
    private val viewModels = mutableListOf<ViewModel>()

    private fun step(text: String) = println("[e2e $run] $text")

    private suspend fun <T> onMain(block: () -> T): T = withContext(main) { block() }

    private fun <V : ViewModel> V.tracked(): V = also { viewModels += it }

    private suspend fun <T : Any> await(what: String, timeoutMs: Long = 15_000, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (System.currentTimeMillis() > deadline) fail("Timed out after $timeoutMs ms waiting for: $what")
            delay(50)
        }
    }

    suspend fun run() {
        val seller = Client(base, "seller")
        val buyer = Client(base, "buyer")
        try {
            signUp(seller, "E2E Seller")
            signUp(buyer, "E2E Buyer")
            payouts(seller)
            val photo = jpeg()
            val sold = listing(seller, "E2E desk lamp $run", 2500, photo)
            val toCancel = listing(seller, "E2E kettle $run", 1500, photo)
            val toRefund = listing(seller, "E2E bike lock $run", 1800, photo)

            val conversationId = chat(seller, buyer, sold)
            buyAndComplete(seller, buyer, sold, conversationId)
            cancelBeforePayment(buyer, toCancel)
            refundAfterPayment(seller, buyer, toRefund)
            errorMapping(seller, buyer, sold, toCancel, photo, conversationId)

            step("cleanup")
            listOf(toCancel, toRefund).forEach { seller.api.deleteListing(it.id) }
            step("PASSED")
        } finally {
            withContext(main) { viewModels.forEach { it.viewModelScope.cancel() } }
            seller.close()
            buyer.close()
            browser.close()
        }
    }

    private suspend fun signUp(c: Client, name: String) {
        val email = "${c.label}-$run@$domain"
        val reg = c.session.register(email, "password123", name)
        val code = reg.devCode ?: fail("Server didn't return devCode: is it in dev mode?")
        c.session.verify(email, code, "password123")
        step("registered ${c.label} $email (${c.id})")
    }

    // ------------------------------------------------------------------------------------------------ payouts

    private suspend fun payouts(seller: Client) {
        val vm = onMain { PayoutsViewModel(seller.api, seller.session, seller.links, seller.messages).tracked() }
        val first = await("payout status") { vm.account.value.valueOrNull }
        assertEquals(PayoutStage.NotStarted, first.stage)
        onMain { vm.onResume() } // initial display

        onMain { vm.startOnboarding() }
        val url = await("onboarding URL opened") { seller.opened.lastOrNull() }
        submitFakeStripeForm(url, "/complete")

        onMain { vm.onResume() } // back from the browser
        await("payouts enabled") { vm.account.value.valueOrNull?.takeIf { it.stage == PayoutStage.Enabled } }
        await("session user refreshed") { seller.session.currentUser?.takeIf { it.payoutsEnabled } }
        step("payouts: NotStarted → onboarding page → Enabled; session.payoutsEnabled = true")
    }

    // ------------------------------------------------------------------------------------------------ chat

    private suspend fun chat(seller: Client, buyer: Client, listing: ListingDetail): String {
        // "Message seller" (idempotent POST /conversations).
        val start = onMain { MessageSellerViewModel(MessageSellerRoute(listing.id), buyer.api).tracked() }
        val conversationId = withTimeout(15_000) { start.events.flow.first() }
        assertEquals(conversationId, buyer.api.startConversation(listing.id).id, "start is idempotent")

        val sellerInbox = onMain { InboxViewModel(seller.api, seller.live, seller.session, seller.messages).tracked() }
        val buyerChat = onMain {
            ConversationViewModel(ConversationRoute(conversationId), buyer.api, buyer.live, buyer.session, buyer.messages).tracked()
                .also { it.setVisible(true) }
        }
        await("sockets connected") {
            Unit.takeIf { seller.socket.status.value == ChatSocket.Status.Connected && buyer.socket.status.value == ChatSocket.Status.Connected }
        }
        await("buyer history loaded") { buyerChat.history.state.value.takeIf { it.loaded } }

        // Buyer sends over REST; the server echoes it to the buyer's own socket too: exactly one bubble.
        val hello = "Hi! Is this still available? ($run)"
        onMain {
            buyerChat.onDraft(hello)
            buyerChat.send()
        }
        val sent = await("buyer's message confirmed") {
            buyerChat.history.state.value.items.firstOrNull { it.body == hello }?.takeIf { buyerChat.state.value.pending.isEmpty() }
        }
        delay(1_000) // give the socket echo time to arrive
        assertEquals(1, buyerChat.rows.value.count { it is ChatRow.Bubble && it.body == hello }, "echo de-duplicated")

        // Seller: inbox row appears live (new conversation fetched on the fly) with an unread badge.
        val row = await("seller inbox row") {
            sellerInbox.conversations.state.value.items.firstOrNull { it.id == conversationId && it.lastMessagePreview == hello }
        }
        assertTrue(row.unreadCount >= 1, "unread badge for the seller")

        // Seller opens the conversation: receives history, marks read → buyer sees "Seen", seller's badge clears.
        val sellerChat = onMain {
            ConversationViewModel(ConversationRoute(conversationId), seller.api, seller.live, seller.session, seller.messages).tracked()
                .also { it.setVisible(true) }
        }
        await("seller sees the message") { sellerChat.history.state.value.items.firstOrNull { it.id == sent.id } }
        await("seller's badge cleared by the read event") {
            sellerInbox.conversations.state.value.items.firstOrNull { it.id == conversationId && it.unreadCount == 0 }
        }
        await("buyer sees Seen") { (buyerChat.rows.value.first() as? ChatRow.Bubble)?.takeIf { it.receipt == Receipt.Seen } }

        // Seller replies; the buyer receives it over the websocket.
        val reply = "Yes! Pickup at the library? ($run)"
        onMain {
            sellerChat.onDraft(reply)
            sellerChat.send()
        }
        await("buyer receives the reply live") { buyerChat.history.state.value.items.firstOrNull { it.body == reply } }
        step("chat: start (idempotent) → send REST + echo de-dup → seller inbox live row/unread → read → Seen → live reply")
        return conversationId
    }

    // ------------------------------------------------------------------------------------------------ orders

    private suspend fun placeOrder(buyer: Client, listing: ListingDetail, updates: OrderUpdates = OrderUpdates()): String {
        val checkout = onMain { CheckoutViewModel(CheckoutRoute(listing.id), buyer.api, buyer.links, updates, buyer.messages).tracked() }
        await("listing for checkout") { checkout.listing.value.valueOrNull }
        val before = buyer.opened.size
        onMain { checkout.placeOrder() }
        val placed = withTimeout(15_000) { checkout.events.flow.first() } as CheckoutEvent.OrderPlaced
        val url = await("checkout URL opened") { buyer.opened.drop(before).firstOrNull() }
        assertTrue("/fake-stripe/checkout/${placed.orderId}" in url, url)
        return placed.orderId
    }

    /** Lets the test say "the user spent a while in the browser" without sleeping. */
    private val clock = TestTimeSource()

    private suspend fun detail(c: Client, orderId: String): OrderDetailViewModel {
        val vm = onMain {
            OrderDetailViewModel(OrderDetailRoute(orderId), c.api, c.links, OrderUpdates(), c.messages, timeSource = clock).tracked()
        }
        await("order loaded") { vm.order.value.valueOrNull }
        onMain { vm.onResume() } // initial display
        return vm
    }

    /** Back in the app after time in the browser. */
    private suspend fun OrderDetailViewModel.resumeFromBrowser() {
        clock += 30.seconds
        onMain { onResume() }
    }

    private suspend fun OrderDetailViewModel.awaitStatus(status: OrderStatus) =
        await("order $status") { order.value.valueOrNull?.takeIf { it.status == status } }

    private suspend fun OrderDetailViewModel.runConfirmed(action: OrderAction) {
        onMain {
            onAction(action)
            assertEquals(action, ui.value.confirming)
            confirm()
        }
        await("$action finished") { ui.value.takeIf { it.running == null && it.confirming == null } }
        ui.value.error?.let { fail("$action failed: $it") }
    }

    private suspend fun buyAndComplete(seller: Client, buyer: Client, listing: ListingDetail, conversationId: String) {
        val orderId = placeOrder(buyer, listing)
        val vm = detail(buyer, orderId)
        assertEquals(listOf(OrderAction.Pay, OrderAction.Message, OrderAction.Cancel), vm.order.value.valueOrNull!!.availableActions())
        assertEquals(ListingStatus.RESERVED, buyer.api.listing(listing.id).status)

        // "Continue to payment" reopens the same checkout; pay on the fake Stripe page; back in the app → resume.
        onMain { vm.onAction(OrderAction.Pay) }
        submitFakeStripeForm(buyer.opened.last(), "/pay")
        vm.resumeFromBrowser()
        val paid = vm.awaitStatus(OrderStatus.PAID)
        // paid: the buyer confirms or reports a problem; no self-refund. The hold's end is shown.
        assertEquals(listOf(OrderAction.ConfirmReceipt, OrderAction.ReportProblem, OrderAction.Message), paid.availableActions())
        assertNotNull(paid.autoReleaseAt, "the auto-release deadline is shown while PAID")

        vm.runConfirmed(OrderAction.ConfirmReceipt)
        val done = vm.awaitStatus(OrderStatus.COMPLETED)
        assertEquals(listOf(OrderAction.Rate, OrderAction.Message), done.availableActions())
        assertEquals(ListingStatus.SOLD, buyer.api.listing(listing.id).status)

        // Buyer rates the seller through the rating screen's view model.
        val rate = onMain { RateOrderViewModel(RateOrderRoute(orderId), buyer.api, OrderUpdates(), buyer.messages).tracked() }
        await("rating order loaded") { rate.order.value.valueOrNull }
        onMain {
            rate.onStars(5)
            rate.onComment("Smooth pickup, thanks! ($run)")
            rate.submit()
        }
        withTimeout(15_000) { rate.done.flow.first() }
        assertTrue(buyer.api.order(orderId).iHaveRated)
        val again = assertFailsWith<ApiError> { buyer.api.rateOrder(orderId, CreateRatingRequest(4)) }
        assertEquals(409, again.status, "second rating rejected")

        // Seller side: payout = amount − fee, can rate the buyer, and "Message" finds the buyer's conversation.
        val sellerView = detail(seller, orderId)
        val sellerOrder = sellerView.order.value.valueOrNull!!
        assertEquals(OrderRole.SELLER, sellerOrder.myRole)
        assertEquals(sellerOrder.amountCents - sellerOrder.feeCents, sellerOrder.payoutCents)
        assertTrue(sellerOrder.feeCents > 0, "platform fee applied")
        seller.api.rateOrder(orderId, CreateRatingRequest(4, "Great buyer"))
        onMain { sellerView.onAction(OrderAction.Message) }
        val open = withTimeout(15_000) { sellerView.events.flow.first() }
        assertEquals(OrderDetailEvent.OpenConversation(conversationId), open)
        assertEquals(5.0, seller.api.user(seller.id).ratingAverage)
        step("buy: checkout → fake pay → PAID → confirm → COMPLETED/SOLD → rate (5★; repeat 409) → seller payout ${sellerOrder.payoutCents}¢, rates, finds chat")
    }

    private suspend fun cancelBeforePayment(buyer: Client, listing: ListingDetail) {
        val orderId = placeOrder(buyer, listing)
        val vm = detail(buyer, orderId)
        vm.runConfirmed(OrderAction.Cancel)
        vm.awaitStatus(OrderStatus.CANCELLED)
        assertEquals(ListingStatus.ACTIVE, buyer.api.listing(listing.id).status)
        step("cancel: PENDING_PAYMENT → CANCELLED, listing ACTIVE again")
    }

    private suspend fun refundAfterPayment(seller: Client, buyer: Client, listing: ListingDetail) {
        val orderId = placeOrder(buyer, listing)
        submitFakeStripeForm(buyer.opened.last(), "/pay")

        // The buyer may already hold the item: they can't refund a paid order, they report a problem instead.
        val buyerView = detail(buyer, orderId)
        onMain { buyerView.refresh() }
        val paid = buyerView.awaitStatus(OrderStatus.PAID)
        assertEquals(listOf(OrderAction.ConfirmReceipt, OrderAction.ReportProblem, OrderAction.Message), paid.availableActions())
        val selfRefund = assertFailsWith<ApiError> { buyer.api.cancelOrder(orderId) }
        assertEquals(ErrorCodes.FORBIDDEN, selfRefund.code, "buyer can't cancel a paid order")

        // Report a problem through the form's view model → DISPUTED (payment frozen) → withdraw → PAID → report again.
        val report = onMain { ReportProblemViewModel(ReportProblemRoute(orderId), buyer.api, OrderUpdates(), buyer.messages).tracked() }
        await("report form loaded") { report.order.value.valueOrNull }
        onMain {
            report.onReason(DisputeReason.SELLER_NO_SHOW)
            report.onDetails("E2E: the seller didn't show up ($run)")
            report.submit()
        }
        withTimeout(15_000) { report.done.flow.first() }
        onMain { buyerView.refresh() }
        val disputed = buyerView.awaitStatus(OrderStatus.DISPUTED)
        assertNull(disputed.autoReleaseAt, "a dispute freezes the auto-release")
        assertEquals(DisputeStatus.OPEN, disputed.dispute?.status)
        assertEquals(listOf(OrderAction.ConfirmReceipt, OrderAction.Message, OrderAction.WithdrawDispute), disputed.availableActions())
        buyerView.runConfirmed(OrderAction.WithdrawDispute)
        val withdrawn = buyerView.awaitStatus(OrderStatus.PAID)
        assertEquals(DisputeOutcome.WITHDRAWN, withdrawn.dispute?.outcome)
        assertNotNull(withdrawn.autoReleaseAt, "the clock restarts")
        buyer.api.openDispute(orderId, OpenDisputeRequest(DisputeReason.ITEM_NOT_AS_DESCRIBED, "E2E: not as described"))

        // The seller sees the report and can still cancel and refund.
        val sellerView = detail(seller, orderId)
        onMain { sellerView.refresh() }
        val sellerSees = sellerView.awaitStatus(OrderStatus.DISPUTED)
        assertEquals(DisputeReason.ITEM_NOT_AS_DESCRIBED, sellerSees.dispute?.reason)
        assertEquals(listOf(OrderAction.Message, OrderAction.Cancel), sellerSees.availableActions())
        sellerView.runConfirmed(OrderAction.Cancel)
        val refunded = sellerView.awaitStatus(OrderStatus.REFUNDED)
        assertEquals(DisputeOutcome.SELLER_REFUNDED, refunded.dispute?.outcome)
        assertEquals(ListingStatus.ACTIVE, buyer.api.listing(listing.id).status)
        step("refund: PAID (buyer cancel → 403) → report problem → DISPUTED → withdraw → PAID → report again → seller refunds → REFUNDED, listing ACTIVE")
    }

    // ------------------------------------------------------------------------------------------------ errors

    private suspend fun errorMapping(
        seller: Client,
        buyer: Client,
        sold: ListingDetail,
        active: ListingDetail,
        photo: ByteArray,
        conversationId: String,
    ) {
        // Own listing: detected up front, and the server's plain 400 maps the same way.
        val own = onMain { CheckoutViewModel(CheckoutRoute(active.id), seller.api, seller.links, OrderUpdates(), seller.messages).tracked() }
        await("own listing problem") { own.ui.value.problem }.let { assertEquals(CheckoutProblem.OwnListing, it) }
        assertEquals(CheckoutProblem.OwnListing, checkoutProblemFor(assertFailsWith<ApiError> { seller.api.createOrder(active.id) }))

        // Sold listing: the checkout screen shows it as unavailable on load, and the server's 409 maps the same way.
        val soldCheckout = onMain { CheckoutViewModel(CheckoutRoute(sold.id), buyer.api, buyer.links, OrderUpdates(), buyer.messages).tracked() }
        await("sold listing problem") { soldCheckout.ui.value.problem }.let { assertEquals(CheckoutProblem.Unavailable, it) }
        val soldError = assertFailsWith<ApiError> { buyer.api.createOrder(sold.id) }
        assertEquals(ErrorCodes.LISTING_UNAVAILABLE, soldError.code)
        assertEquals(CheckoutProblem.Unavailable, checkoutProblemFor(soldError))

        // Seller without payouts (the buyer lists something).
        val buyersItem = listing(buyer, "E2E textbook $run", 1200, photo)
        val noPayouts = assertFailsWith<ApiError> { seller.api.createOrder(buyersItem.id) }
        assertEquals(ErrorCodes.PAYOUTS_NOT_ENABLED, noPayouts.code)
        assertEquals(CheckoutProblem.PayoutsNotEnabled, checkoutProblemFor(noPayouts))
        buyer.api.deleteListing(buyersItem.id)

        // Blocked: sending and buying are refused with BLOCKED and the composer locks.
        seller.api.blockUser(buyer.id)
        try {
            // The existing conversation stays readable (from the inbox); sending is refused.
            val chat = onMain {
                ConversationViewModel(ConversationRoute(conversationId), buyer.api, buyer.live, buyer.session, buyer.messages).tracked()
            }
            await("conversation loaded") { chat.conversation.value as? UiState.Content }
            onMain {
                chat.onDraft("Are you there?")
                chat.send()
            }
            await("blocked restriction") { chat.state.value.restriction }.let { assertEquals(SendRestriction.Blocked, it) }
            val blocked = assertFailsWith<ApiError> { buyer.api.createOrder(active.id) }
            assertEquals(CheckoutProblem.Blocked, checkoutProblemFor(blocked))
            val newChat = assertFailsWith<ApiError> { buyer.api.startConversation(active.id) }
            assertEquals("You can't message this seller", com.campusmarket.app.feature.chat.startChatProblem(newChat).title)
        } finally {
            seller.api.unblockUser(buyer.id)
        }
        step("errors: own listing / LISTING_UNAVAILABLE / PAYOUTS_NOT_ENABLED / BLOCKED (chat + order) mapped")
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private suspend fun listing(c: Client, title: String, priceCents: Int, photo: ByteArray): ListingDetail {
        val uploaded = c.api.uploadPhoto(photo, "e2e.jpg", "image/jpeg")
        return c.api.createListing(
            CreateListingRequest(
                title = title,
                description = "Created by the feature-B end-to-end test.",
                category = ListingCategory.DORM,
                condition = ListingCondition.GOOD,
                priceCents = priceCents,
                pickupLocation = "Library steps",
                photoIds = listOf(uploaded.id),
            ),
        )
    }

    /** Plays the browser: loads a fake-Stripe page and submits the form whose action ends with [actionSuffix]. */
    private suspend fun submitFakeStripeForm(url: String, actionSuffix: String) {
        val html = browser.get(url).bodyAsText()
        val actions = Regex("""<form method="post" action="([^"]+)"""").findAll(html).map { it.groupValues[1].replace("&amp;", "&") }.toList()
        val relative = actions.firstOrNull { it.endsWith(actionSuffix) } ?: fail("No '$actionSuffix' form on $url; forms: $actions")
        val action = java.net.URI(url).resolve(relative).toString() // the pages use origin-relative actions
        val response = browser.post(action)
        assertTrue(response.status.value in 200..399, "POST $action → ${response.status}")
    }

    private fun jpeg(): ByteArray {
        val img = BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(0x1F, 0x6F, 0x50)
        g.fillRect(0, 0, 96, 96)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }
}
