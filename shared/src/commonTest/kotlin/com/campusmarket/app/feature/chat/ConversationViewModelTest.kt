package com.campusmarket.app.feature.chat

import com.campusmarket.app.data.ChatSocket
import com.campusmarket.app.data.Harness
import com.campusmarket.app.data.NOW
import com.campusmarket.app.data.bodyText
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
        var conversation = Conversation("c1", listingSummary(sellerId = "u2"), other, "See you then", NOW - 1_000, 1, NOW - 50_000)
        var newest = mutableListOf(msg("m2", "u2", "See you then", NOW - 1_000), msg("m1", me, "Is it still available?", NOW - 2_000))
        val older = listOf(msg("m0", me, "Hi!", NOW - 3 * 86_400_000L))
        var sendStatus: HttpStatusCode? = null
        var sendCode = ErrorCodes.VALIDATION
        var sendThrows = false
        var sendGate: CompletableDeferred<Unit>? = null
        var sentCount = 0

        /** The message is stored but its response never arrives (a timeout on the way back). */
        var loseResponse = false

        /** Sent messages show up in the history, as on the real server. */
        var keepSent = false
        val byClientId = HashMap<String, Message>()
        val clientIds = mutableListOf<String?>()

        fun harness(scope: TestScope) = Harness(scope = scope) { req ->
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
                        val sent = ApiJson.decodeFromString(SendMessageRequest.serializer(), req.bodyText)
                        clientIds += sent.clientId
                        val message = sent.clientId?.let(byClientId::get) ?: run {
                            sentCount++
                            msg("s$sentCount", me, sent.body, NOW + sentCount).copy(clientId = sent.clientId).also { m ->
                                sent.clientId?.let { byClientId[it] = m }
                                if (keepSent) newest.add(0, m)
                            }
                        }
                        if (loseResponse) throw kotlinx.io.IOException("timeout")
                        respondJson(Message.serializer(), message)
                    }
                }
                path == ApiRoutes.conversationRead("c1") -> respondJson(OkResponse.serializer(), OkResponse())
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
    }

    private fun TestScope.setUp(status: ChatSocket.Status = ChatSocket.Status.Connected): Triple<FakeChatServer, Harness, FakeLiveChat> {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val server = FakeChatServer()
        return Triple(server, server.harness(this), FakeLiveChat(status))
    }

    private var ids = 0

    private fun vm(h: Harness, live: FakeLiveChat, outbox: ChatOutbox = ChatOutbox()) =
        ConversationViewModel(
            ConversationRoute("c1"), h.api, live, h.session, h.messages, outbox = outbox, nowMillis = { NOW + 500 }, newClientId = { "local-${ids++}" },
        )

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
    fun sendRejectedByTheServerKeepsTheMessageAsNotSent() = runTest {
        val (server, h, live) = setUp()
        val vm = vm(h, live)
        advanceUntilIdle()
        server.sendStatus = HttpStatusCode.InternalServerError
        server.sendCode = ErrorCodes.INTERNAL

        vm.onDraft("Can you do 4pm?")
        vm.send()
        advanceUntilIdle()

        assertEquals("", vm.state.value.draft, "the text moved into the bubble")
        val bubble = vm.bubbles().first()
        assertEquals("Can you do 4pm?", bubble.body)
        assertEquals(Delivery.Failed, bubble.delivery)
        assertTrue(bubble.mine)
        assertTrue(!bubble.error.isNullOrBlank())
        assertNull(vm.state.value.restriction, "a server error doesn't lock the composer")
        // Nothing takes it away: not a live event from the other person, not a re-sync.
        live.emit(ChatEvent.MessageCreated(msg("m3", "u2", "Hello?", NOW + 10)))
        vm.syncNewest()
        advanceUntilIdle()
        assertEquals(listOf("Can you do 4pm?", "Hello?"), vm.bubbles().take(2).map { it.body })
        assertEquals(Delivery.Failed, vm.state.value.pending.single().delivery)
        vm.close()
    }

    @Test
    fun retryAfterAFailureSendsTheSameTextOnce() = runTest {
        val (server, h, live) = setUp()
        val vm = vm(h, live)
        advanceUntilIdle()
        server.sendThrows = true
        vm.onDraft("On my way")
        vm.send()
        advanceUntilIdle()
        val failed = vm.state.value.pending.single()

        // Still failing: it stays, still marked.
        vm.retry(failed.localId)
        advanceUntilIdle()
        assertEquals(Delivery.Failed, vm.state.value.pending.single().delivery)

        server.sendThrows = false
        server.sendGate = CompletableDeferred()
        vm.retry(failed.localId)
        runCurrent()
        assertEquals(Delivery.Sending, vm.state.value.pending.single().delivery)
        vm.retry(failed.localId) // a second tap while it's sending does nothing
        server.sendGate!!.complete(Unit)
        advanceUntilIdle()

        assertTrue(vm.state.value.pending.isEmpty())
        assertEquals(1, server.sentCount)
        assertEquals(1, vm.bubbles().count { it.body == "On my way" })
        assertEquals(Delivery.Sent, vm.bubbles().first { it.body == "On my way" }.delivery)
        vm.close()
    }

    @Test
    fun discardRemovesOnlyThatFailedMessage() = runTest {
        val (server, h, live) = setUp()
        val vm = vm(h, live)
        advanceUntilIdle()
        server.sendThrows = true
        vm.onDraft("First")
        vm.send()
        vm.onDraft("Second")
        vm.send()
        advanceUntilIdle()
        val (first, second) = vm.state.value.pending

        vm.discard(first.localId)

        assertEquals(listOf(second), vm.state.value.pending)
        assertTrue(vm.bubbles().none { it.body == "First" })
        assertEquals(Delivery.Failed, vm.bubbles().first { it.body == "Second" }.delivery)
        assertEquals(0, server.sentCount)
        vm.close()
    }

    @Test
    fun unsentMessagesAndTheDraftAreStillThereAfterLeavingAndComingBack() = runTest {
        val (server, h, live) = setUp()
        val outbox = ChatOutbox()
        val first = vm(h, live, outbox)
        advanceUntilIdle()
        server.sendThrows = true
        first.onDraft("Is 5pm ok?")
        first.send()
        advanceUntilIdle()
        first.onDraft("half typed")
        first.close()

        val second = vm(h, live, outbox)
        advanceUntilIdle()
        val restored = second.state.value.pending.single()
        assertEquals("Is 5pm ok?", restored.body)
        assertEquals(Delivery.Failed, restored.delivery)
        assertEquals("half typed", second.state.value.draft)

        server.sendThrows = false
        second.retry(restored.localId)
        advanceUntilIdle()
        assertTrue(second.state.value.pending.isEmpty())
        assertEquals("Is 5pm ok?", second.history.state.value.items.first().body)
        second.close()

        // Sent now, so a third visit starts clean (the draft is still unsent).
        val third = vm(h, live, outbox)
        advanceUntilIdle()
        assertTrue(third.state.value.pending.isEmpty())
        assertEquals("half typed", third.state.value.draft)
        third.close()
    }

    @Test
    fun aSendCutOffByLeavingComesBackAsNotSentUnlessTheServerGotIt() = runTest {
        val (server, h, live) = setUp()
        val outbox = ChatOutbox()
        server.sendGate = CompletableDeferred()
        val first = vm(h, live, outbox)
        advanceUntilIdle()
        first.onDraft("Leaving now")
        first.send()
        runCurrent()
        assertEquals(Delivery.Sending, first.state.value.pending.single().delivery)
        first.close()

        // The server never got it: it's offered again.
        val second = vm(h, live, outbox)
        advanceUntilIdle()
        assertEquals(Delivery.Failed, second.state.value.pending.single().delivery)
        assertEquals("Leaving now", second.bubbles().first().body)
        second.close()

        // The server did get it: the history has it, so the unsent copy goes.
        server.newest.add(0, msg("m5", me, "Leaving now", NOW + 400))
        val third = vm(h, live, outbox)
        advanceUntilIdle()
        assertTrue(third.state.value.pending.isEmpty())
        assertEquals(1, third.bubbles().count { it.body == "Leaving now" })
        third.close()
    }

    @Test
    fun everySendCarriesItsOwnClientIdAndARetryRepeatsIt() = runTest {
        val (server, h, live) = setUp()
        val vm = vm(h, live)
        advanceUntilIdle()
        vm.onDraft("One")
        vm.send()
        vm.onDraft("Two")
        vm.send()
        advanceUntilIdle()
        assertEquals(listOf<String?>("local-0", "local-1"), server.clientIds)

        server.sendThrows = true
        vm.onDraft("Three")
        vm.send()
        advanceUntilIdle()
        server.sendThrows = false
        vm.retry(vm.state.value.pending.single().localId)
        advanceUntilIdle()
        assertEquals("local-2", server.clientIds.last())
        assertEquals(3, server.sentCount)
        vm.close()
    }

    @Test
    fun retryingASendWhoseResponseWasLostDoesNotSendItTwice() = runTest {
        val (server, h, live) = setUp()
        val vm = vm(h, live)
        advanceUntilIdle()
        server.loseResponse = true
        vm.onDraft("See you at 5")
        vm.send()
        advanceUntilIdle()
        val failed = vm.state.value.pending.single()
        assertEquals(Delivery.Failed, failed.delivery)
        assertEquals(1, server.sentCount, "the server did store it")

        server.loseResponse = false
        vm.retry(failed.localId)
        advanceUntilIdle()

        assertEquals(1, server.sentCount, "the retry got the stored message back")
        assertEquals(listOf<String?>(failed.localId, failed.localId), server.clientIds)
        assertTrue(vm.state.value.pending.isEmpty())
        assertEquals(1, vm.bubbles().count { it.body == "See you at 5" })
        assertEquals("s1", vm.bubbles().first { it.body == "See you at 5" }.messageId)
        vm.close()
    }

    @Test
    fun aMessageThatComesBackWithMyClientIdRetiresItsNotSentBubble() = runTest {
        val (server, h, live) = setUp()
        val vm = vm(h, live)
        advanceUntilIdle()
        server.loseResponse = true
        vm.onDraft("First")
        vm.send()
        vm.onDraft("Second")
        vm.send()
        advanceUntilIdle()
        val (first, second) = vm.state.value.pending
        assertEquals(listOf(Delivery.Failed, Delivery.Failed), listOf(first.delivery, second.delivery))

        // The socket's echo arrives after all.
        live.emit(ChatEvent.MessageCreated(server.byClientId.getValue(first.localId)))
        runCurrent()
        assertEquals(listOf(second), vm.state.value.pending)

        // A catch-up over REST finds the other one.
        server.newest.add(0, server.byClientId.getValue(second.localId))
        vm.syncNewest()
        advanceUntilIdle()
        assertTrue(vm.state.value.pending.isEmpty())
        assertEquals(listOf("Second", "First"), vm.bubbles().take(2).map { it.body })
        assertTrue(vm.bubbles().take(2).all { it.delivery == Delivery.Sent })
        vm.close()
    }

    @Test
    fun unsentMessagesAndTheDraftSurviveTheProcess() = runTest {
        val (server, h, live) = setUp()
        val disk = FakeSecretStorage()
        server.sendThrows = true
        val first = vm(h, live, ChatOutbox(disk))
        advanceUntilIdle()
        first.onDraft("Is 5pm ok?")
        first.send()
        advanceUntilIdle()
        first.onDraft("half typed")
        first.close()

        // A new process: nothing in memory, only what was stored.
        val second = vm(h, live, ChatOutbox(disk))
        advanceUntilIdle()
        val restored = second.state.value.pending.single()
        assertEquals("Is 5pm ok?", restored.body)
        assertEquals(Delivery.Failed, restored.delivery)
        assertEquals("half typed", second.state.value.draft)

        server.sendThrows = false
        second.retry(restored.localId)
        advanceUntilIdle()
        assertTrue(second.state.value.pending.isEmpty())
        second.onDraft("")
        second.close()
        assertNull(disk.value, "nothing left to keep")
    }

    @Test
    fun aSendInFlightWhenTheProcessDiedIsNotSentUnlessTheHistoryHasItsClientId() = runTest {
        val (server, h, live) = setUp()
        val disk = FakeSecretStorage()
        server.keepSent = true
        server.sendGate = CompletableDeferred()
        val first = vm(h, live, ChatOutbox(disk))
        advanceUntilIdle()
        first.onDraft("Leaving now")
        first.send()
        runCurrent()
        assertEquals(Delivery.Sending, first.state.value.pending.single().delivery)
        first.close()

        // The request never reached the server: restored as "Not sent" (and flagged for the inbox right away).
        val restoredOutbox = ChatOutbox(disk)
        assertEquals(Delivery.Failed, restoredOutbox.load(me, "c1").pending.single().delivery)
        val second = vm(h, live, restoredOutbox)
        advanceUntilIdle()
        assertEquals(Delivery.Failed, second.state.value.pending.single().delivery)
        assertEquals("Not sent", second.bubbles().first().error)
        second.close()

        // It did reach the server (another message with the same text is not mistaken for it): the stored copy goes.
        server.newest.add(0, msg("m5", me, "Leaving now", NOW + 400).copy(clientId = "someone-else"))
        val third = vm(h, live, ChatOutbox(disk))
        advanceUntilIdle()
        assertEquals(1, third.state.value.pending.size, "same text, different id: still unsent")
        third.close()
        server.newest.add(0, msg("m6", me, "Leaving now", NOW + 450).copy(clientId = "local-0"))
        val fourth = vm(h, live, ChatOutbox(disk))
        advanceUntilIdle()
        assertTrue(fourth.state.value.pending.isEmpty())
        assertEquals(2, fourth.bubbles().count { it.body == "Leaving now" })
        fourth.close()
    }

    @Test
    fun outboxIsPerUser() {
        val outbox = ChatOutbox()
        outbox.save("u1", "c1", "draft", listOf(PendingMessage("l1", "hi", 1, Delivery.Failed)))
        assertEquals(ChatOutbox.Entry(), outbox.load("u2", "c1"))
        assertEquals("draft", outbox.load("u1", "c1").draft)
        outbox.save("u1", "c1", " ", emptyList())
        assertEquals(ChatOutbox.Entry(), outbox.load("u1", "c1"))
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
        assertNull(withTimeoutOrNull(1_000) { h.messages.messages.first() })
        vm.close()
    }

    @Test
    fun comingBackPicksUpABlockMadeElsewhere() = runTest {
        val (server, h, live) = setUp()
        val vm = vm(h, live)
        vm.setVisible(true)
        advanceUntilIdle()
        assertNull(vm.state.value.restriction)

        // Blocked from their profile, opened from this chat; then back here.
        vm.setVisible(false)
        server.conversation = server.conversation.copy(otherUser = other.copy(blockedByMe = true))
        vm.setVisible(true)
        advanceUntilIdle()
        assertEquals(SendRestriction.BlockedByMe, vm.state.value.restriction)

        vm.setVisible(false)
        server.conversation = server.conversation.copy(otherUser = other.copy(blockedByMe = false))
        vm.setVisible(true)
        advanceUntilIdle()
        assertNull(vm.state.value.restriction)
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
