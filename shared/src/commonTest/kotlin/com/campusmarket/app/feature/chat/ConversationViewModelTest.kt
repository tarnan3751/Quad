package com.campusmarket.app.feature.chat

import com.campusmarket.app.data.NOW
import com.campusmarket.app.data.ChatSocket
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.data.testUser
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ChatEvent
import com.campusmarket.core.Conversation
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Message
import com.campusmarket.core.OkResponse
import com.campusmarket.core.Page
import com.campusmarket.core.SendMessageRequest
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Conversation send / receive / de-dup / read logic against a fake server and a fake socket. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {

    private val me = testUser.id // "u1", the buyer
    private val other = publicUser("u2", "Maya Chen")

    private fun msg(id: String, sender: String, body: String, at: Long) = Message(id, "c1", sender, body, at)

    /** Mutable fake server for one conversation. */
    private inner class FakeChatServer {
        val conversation = Conversation("c1", listingSummary(sellerId = "u2"), other, "See you then", NOW - 1_000, 1, NOW - 50_000)
        var newest = mutableListOf(msg("m2", "u2", "See you then", NOW - 1_000), msg("m1", me, "Is it still available?", NOW - 2_000))
        val older = listOf(msg("m0", me, "Hi!", NOW - 3 * 86_400_000L))
        var sendStatus: HttpStatusCode? = null
        var sendCode = ErrorCodes.VALIDATION
        var sendThrows = false
        var sendGate: CompletableDeferred<Unit>? = null
        var sentCount = 0

        fun harness(scope: TestScope) = VmHarness(scope) { req ->
            val path = req.url.encodedPath
            when {
                path == ApiRoutes.conversation("c1") -> respondJson(Conversation.serializer(), conversation)
                path == ApiRoutes.conversationMessages("c1") && req.method == HttpMethod.Get -> {
                    val page = if (req.url.parameters["cursor"] == "older") Page(older, null) else Page(newest.toList(), "older")
                    respondJson(Page.serializer(Message.serializer()), page)
                }
                path == ApiRoutes.conversationMessages("c1") && req.method == HttpMethod.Post -> {
                    sendGate?.await()
                    if (sendThrows) throw kotlinx.io.IOException("offline")
                    val status = sendStatus
                    if (status != null) {
                        respondError(status, sendCode, "You can't message this user")
                    } else {
                        val body = ApiJson.decodeFromString(SendMessageRequest.serializer(), req.bodyText()).body
                        sentCount++
                        respondJson(Message.serializer(), msg("s$sentCount", me, body, NOW + sentCount))
                    }
                }
                path == ApiRoutes.conversationRead("c1") -> respondJson(OkResponse.serializer(), OkResponse())
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
    }

    private fun TestScope.setUp(status: ChatSocket.Status = ChatSocket.Status.Connected): Triple<FakeChatServer, VmHarness, FakeLiveChat> {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val server = FakeChatServer()
        return Triple(server, server.harness(this), FakeLiveChat(status))
    }

    private fun vm(h: VmHarness, live: FakeLiveChat) =
        ConversationViewModel(ConversationRoute("c1"), h.api, live, h.session, h.userMessages, nowMillis = { NOW + 500 })

    private fun ConversationViewModel.bubbles() = rows.value.filterIsInstance<ChatRow.Bubble>()

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadsHeaderAndHistoryNewestFirstAndMarksReadWhenVisible() = runTest {
        val (_, h, live) = setUp()
        val vm = vm(h, live)
        advanceUntilIdle()

        assertIs<UiState.Content<Conversation>>(vm.conversation.value)
        assertEquals(listOf("m2", "m1"), vm.history.state.value.items.map { it.id })
        assertEquals(listOf("m2", "m1"), vm.bubbles().map { it.messageId })
        assertEquals(listOf(false, true), vm.bubbles().map { it.mine })
        assertEquals(0, h.requests(HttpMethod.Post, ApiRoutes.conversationRead("c1")).size, "not visible yet")

        vm.setVisible(true)
        advanceUntilIdle()
        assertEquals(1, h.requests(HttpMethod.Post, ApiRoutes.conversationRead("c1")).size)

        // Older page: appended at the end (top of the screen); the very first message gets a day separator.
        vm.loadOlder()
        advanceUntilIdle()
        assertEquals(listOf("m2", "m1", "m0"), vm.history.state.value.items.map { it.id })
        assertIs<ChatRow.DayHeader>(vm.rows.value.last())
        vm.close()
    }

    @Test
    fun sendShowsOptimisticBubbleThenTheServerMessage() = runTest {
        val (server, h, live) = setUp()
        val vm = vm(h, live)
        advanceUntilIdle()
        server.sendGate = CompletableDeferred()

        vm.onDraft("  Can I pick it up at 5?  ")
        vm.send()
        runCurrent()

        assertEquals("", vm.state.value.draft)
        val pending = vm.bubbles().first()
        assertEquals(Delivery.Sending, pending.delivery)
        assertEquals("Can I pick it up at 5?", pending.body, "trimmed")
        assertNull(pending.messageId)

        server.sendGate!!.complete(Unit)
        advanceUntilIdle()

        assertTrue(vm.state.value.pending.isEmpty())
        val sent = vm.bubbles().first()
        assertEquals("s1", sent.messageId)
        assertEquals(Delivery.Sent, sent.delivery)
        assertEquals(Receipt.Sent, sent.receipt)
        assertEquals(3, vm.history.state.value.items.size)
        vm.close()
    }

    @Test
    fun socketEchoOfMyMessageBeforeTheRestResponseIsNotDuplicated() = runTest {
        val (server, h, live) = setUp()
        val vm = vm(h, live)
        advanceUntilIdle()
        server.sendGate = CompletableDeferred()

        vm.onDraft("On my way")
        vm.send()
        runCurrent()
        // The server fans the message out to my own socket before the REST call returns.
        live.emit(ChatEvent.MessageCreated(msg("s1", me, "On my way", NOW + 1)))
        runCurrent()
        assertTrue(vm.state.value.pending.isEmpty(), "echo retires the optimistic bubble")
        assertEquals(1, vm.bubbles().count { it.body == "On my way" })

        server.sendGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, vm.bubbles().count { it.body == "On my way" })
        assertEquals(1, vm.history.state.value.items.count { it.id == "s1" })

        // A second echo (e.g. another device) changes nothing.
        live.emit(ChatEvent.MessageCreated(msg("s1", me, "On my way", NOW + 1)))
        runCurrent()
        assertEquals(1, vm.bubbles().count { it.body == "On my way" })
        vm.close()
    }

    @Test
    fun incomingLiveMessagesAreMergedByIdAndMarkedRead() = runTest {
        val (_, h, live) = setUp()
        val vm = vm(h, live)
        vm.setVisible(true)
        advanceUntilIdle()
        val reads = { h.requests(HttpMethod.Post, ApiRoutes.conversationRead("c1")).size }
        assertEquals(1, reads())

        val m3 = msg("m3", "u2", "Still there?", NOW + 10)
        live.emit(ChatEvent.MessageCreated(m3))
        advanceUntilIdle()
        assertEquals("m3", vm.history.state.value.items.first().id)
        assertEquals(2, reads(), "new incoming message while visible → mark read")

        live.emit(ChatEvent.MessageCreated(m3))
        live.emit(ChatEvent.MessageCreated(Message("x1", "other-conversation", "u9", "nope", NOW + 20)))
        advanceUntilIdle()
        assertEquals(listOf("m3", "m2", "m1"), vm.history.state.value.items.map { it.id })
        assertEquals(2, reads(), "duplicates and other conversations are ignored")

        // While paused, messages wait; resuming marks them read and catches up over REST.
        vm.setVisible(false)
        live.emit(ChatEvent.MessageCreated(msg("m4", "u2", "Hello?", NOW + 30)))
        advanceUntilIdle()
        assertEquals(2, reads())
        vm.setVisible(true)
        advanceUntilIdle()
        assertEquals(3, reads())
        assertEquals(1, live.reconnectRequests)
        vm.close()
    }

    @Test
    fun liveMessageArrivingBeforeTheFirstPageIsKept() = runTest {
        val (_, h, live) = setUp()
        val vm = vm(h, live)
        // History request in flight; an event arrives first.
        live.emit(ChatEvent.MessageCreated(msg("m9", "u2", "Early bird", NOW + 5)))
        advanceUntilIdle()
        assertEquals(listOf("m9", "m2", "m1"), vm.history.state.value.items.map { it.id })
        vm.close()
    }

    @Test
    fun failedSendStaysWithRetryAndDiscard() = runTest {
        val (server, h, live) = setUp()
        val vm = vm(h, live)
        advanceUntilIdle()
        server.sendThrows = true

        vm.onDraft("Hello?")
        vm.send()
        advanceUntilIdle()
        val failed = vm.state.value.pending.single()
        assertEquals(Delivery.Failed, failed.delivery)
        assertEquals("Not sent. Check your connection.", failed.error)
        assertEquals(Delivery.Failed, vm.bubbles().first().delivery)

        server.sendThrows = false
        vm.retry(failed.localId)
        advanceUntilIdle()
        assertTrue(vm.state.value.pending.isEmpty())
        assertEquals("Hello?", vm.history.state.value.items.first().body)

        server.sendThrows = true
        vm.onDraft("Second")
        vm.send()
        advanceUntilIdle()
        vm.discard(vm.state.value.pending.single().localId)
        assertTrue(vm.bubbles().none { it.body == "Second" })
        vm.close()
    }

    @Test
    fun blockedSendDisablesTheComposer() = runTest {
        val (server, h, live) = setUp()
        val vm = vm(h, live)
        advanceUntilIdle()
        server.sendStatus = HttpStatusCode.Forbidden
        server.sendCode = ErrorCodes.BLOCKED

        vm.onDraft("Hey")
        vm.send()
        advanceUntilIdle()
        assertEquals(SendRestriction.Blocked, vm.state.value.restriction)
        assertEquals(Delivery.Failed, vm.state.value.pending.single().delivery)

        val before = h.requests(HttpMethod.Post, ApiRoutes.conversationMessages("c1")).size
        vm.onDraft("Again")
        vm.send()
        advanceUntilIdle()
        assertEquals(before, h.requests(HttpMethod.Post, ApiRoutes.conversationMessages("c1")).size, "no request while blocked")
        vm.close()
    }

    @Test
    fun deletedUserConflictDisablesTheComposer() = runTest {
        val (server, h, live) = setUp()
        val vm = vm(h, live)
        advanceUntilIdle()
        server.sendStatus = HttpStatusCode.Conflict
        server.sendCode = ErrorCodes.CONFLICT

        vm.onDraft("Hey")
        vm.send()
        advanceUntilIdle()
        assertEquals(SendRestriction.UserUnavailable, vm.state.value.restriction)
        vm.close()
    }

    @Test
    fun blockingFromTheDialogUpdatesHeaderAndComposer() = runTest {
        val (_, h, live) = setUp()
        val vm = vm(h, live)
        advanceUntilIdle()
        vm.onBlockChanged(true)
        assertEquals(SendRestriction.BlockedByMe, vm.state.value.restriction)
        assertTrue((vm.conversation.value as UiState.Content).value.otherUser.blockedByMe)
        vm.onBlockChanged(false)
        assertNull(vm.state.value.restriction)
        // BlockUserDialog shows the "Blocked …" / "Unblocked …" snackbar itself: no second one from here.
        assertNull(withTimeoutOrNull(1_000) { h.userMessages.messages.first() })
        vm.close()
    }

    @Test
    fun readEventFromTheOtherPersonShowsSeen() = runTest {
        val (_, h, live) = setUp()
        val vm = vm(h, live)
        advanceUntilIdle()
        vm.onDraft("Deal")
        vm.send()
        advanceUntilIdle()
        assertEquals(Receipt.Sent, vm.bubbles().first().receipt)

        live.emit(ChatEvent.ConversationRead("c1", readerId = me, readAt = NOW + 100))
        runCurrent()
        assertEquals(Receipt.Sent, vm.bubbles().first().receipt, "my own read event doesn't count")

        live.emit(ChatEvent.ConversationRead("c1", readerId = "u2", readAt = NOW + 100))
        runCurrent()
        assertEquals(Receipt.Seen, vm.bubbles().first().receipt)
        vm.close()
    }

    @Test
    fun pollsOverRestWhileTheSocketIsDownAndCatchesUpOnReconnect() = runTest {
        val (server, h, live) = setUp(status = ChatSocket.Status.Reconnecting)
        val vm = vm(h, live)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, vm.history.state.value.items.size)

        server.newest.add(0, msg("m3", "u2", "Polled", NOW + 10))
        advanceTimeBy(CHAT_POLL_INTERVAL_MS)
        runCurrent()
        assertEquals("m3", vm.history.state.value.items.first().id)

        server.newest.add(0, msg("m4", "u2", "Missed while offline", NOW + 20))
        live.status.value = ChatSocket.Status.Connected
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("m4", vm.history.state.value.items.first().id, "one catch-up fetch after reconnecting")

        val fetches = h.requests(HttpMethod.Get, ApiRoutes.conversationMessages("c1")).size
        advanceTimeBy(5 * CHAT_POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(fetches, h.requests(HttpMethod.Get, ApiRoutes.conversationMessages("c1")).size, "no polling while connected")
        vm.close()
    }

    @Test
    fun overLongDraftIsCappedAtTheLimit() = runTest {
        val (_, h, live) = setUp()
        val vm = vm(h, live)
        vm.onDraft("x".repeat(com.campusmarket.core.Limits.MESSAGE_MAX + 50))
        assertEquals(com.campusmarket.core.Limits.MESSAGE_MAX, vm.state.value.draft.length)
        vm.onDraft("   ")
        vm.send()
        assertTrue(vm.state.value.pending.isEmpty(), "blank drafts aren't sent")
        vm.close()
    }
}

/** Pure row building: grouping, day separators, receipts, pending order. */
class ChatRowsTest {
    private val day = 86_400_000L
    private val sameDay: (Long, Long) -> Boolean = { a, b -> a / day == b / day }
    private fun m(id: String, sender: String, at: Long) = Message(id, "c1", sender, id, at)

    @Test
    fun groupsAndSeparatesDays() {
        val base = 100 * day
        val history = listOf(
            m("d", "me", base + 2 * day + 60_000),
            m("c", "me", base + 2 * day),
            m("b", "them", base + 10_000),
            m("a", "them", base),
        )
        val rows = buildChatRows(history, endReached = true, state = ConversationUiState(), myId = "me", sameDay = sameDay)
        assertEquals(listOf("d", "c", "day-c", "b", "a", "day-a"), rows.map { it.key })
        val bubbles = rows.filterIsInstance<ChatRow.Bubble>().associateBy { it.key }
        assertTrue(bubbles.getValue("d").groupedWithOlder)
        assertTrue(!bubbles.getValue("c").groupedWithOlder, "different day from the older one")
        assertTrue(bubbles.getValue("b").groupedWithOlder)
        assertEquals(Receipt.Sent, bubbles.getValue("d").receipt)
        assertNull(bubbles.getValue("c").receipt)
    }

    @Test
    fun noSeparatorAboveOldestLoadedUntilTheStartIsKnown() {
        val rows = buildChatRows(listOf(m("a", "them", 5 * day)), endReached = false, state = ConversationUiState(), myId = "me", sameDay = sameDay)
        assertEquals(listOf("a"), rows.map { it.key })
    }

    @Test
    fun pendingMessagesComeFirstNewestAtTheBottom() {
        val state = ConversationUiState(
            pending = listOf(PendingMessage("local-0", "first", 10 * day), PendingMessage("local-1", "second", 10 * day + 1, Delivery.Failed, "x")),
            otherReadAt = 10 * day,
        )
        val rows = buildChatRows(listOf(m("a", "me", 10 * day - 5)), endReached = true, state = state, myId = "me", sameDay = sameDay)
        val bubbles = rows.filterIsInstance<ChatRow.Bubble>()
        assertEquals(listOf("local-1", "local-0", "a"), bubbles.map { it.key })
        assertEquals(Delivery.Failed, bubbles[0].delivery)
        assertEquals(Receipt.Seen, bubbles[2].receipt, "read at/after it")
    }

    @Test
    fun mergeKeepsServerOrderAndDropsDuplicates() {
        val current = listOf(m("b", "x", 20), m("a", "x", 10))
        val merged = mergeNewestFirst(current, listOf(m("c", "x", 30), m("b", "x", 20), m("a2", "x", 15)))
        assertEquals(listOf("c", "b", "a2", "a"), merged.map { it.id })
        assertTrue(mergeNewestFirst(current, listOf(m("a", "x", 10))) === current)
    }

    @Test
    fun previewMatchesServerShape() {
        assertEquals("Hi there friend", ChatFormat.preview("  Hi\n\nthere   friend "))
        val long = ChatFormat.preview("x".repeat(500))
        assertEquals(120, long.length)
        assertTrue(long.endsWith("…"))
    }
}
