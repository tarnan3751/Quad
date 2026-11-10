package com.campusmarket.app.feature.chat

import com.campusmarket.app.data.ChatSocket
import com.campusmarket.app.data.Harness
import com.campusmarket.app.data.NOW
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.data.testUser
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ChatEvent
import com.campusmarket.core.Conversation
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Message
import com.campusmarket.core.Page
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The app-wide unread total behind the Inbox tab badge. */
@OptIn(ExperimentalCoroutinesApi::class)
class UnreadConversationsTest {
    private val me = testUser.id

    private fun conversation(id: String, lastAt: Long, unread: Int) =
        Conversation(id, listingSummary(id = "l-$id"), publicUser("u-$id", "User $id"), "hi", lastAt, unread, NOW - 100_000)

    private var page = listOf(conversation("c1", NOW - 1_000, 2), conversation("c2", NOW - 5_000, 0), conversation("c3", NOW - 9_000, 1))

    private class Setup(val unread: UnreadConversations, val h: Harness, val live: FakeLiveChat, private val job: Job) {
        fun close() = job.cancel()
    }

    private fun TestScope.setUp(status: ChatSocket.Status = ChatSocket.Status.Connected): Setup {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val h = Harness(scope = this) { req ->
            when (req.url.encodedPath) {
                ApiRoutes.CONVERSATIONS -> respondJson(Page.serializer(Conversation.serializer()), Page(page, null))
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        val live = FakeLiveChat(status)
        // Not backgroundScope: advanceUntilIdle() doesn't wait for background work. Cancelled by Setup.close().
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val unread = UnreadConversations(h.api, live, h.session, scope)
        scope.launch { unread.count.collect { } }
        return Setup(unread, h, live, job)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun countsConversationsWithUnreadMessagesFromTheFirstSync() = runTest {
        val s = setUp()
        advanceUntilIdle()
        assertEquals(2, s.unread.count.value)
        assertEquals(1, s.h.requests(HttpMethod.Get, ApiRoutes.CONVERSATIONS).size)
        s.close()
    }

    @Test
    fun liveMessagesAndReadsMoveTheCount() = runTest {
        val s = setUp()
        advanceUntilIdle()

        s.live.emit(ChatEvent.MessageCreated(Message("m1", "c2", "u-c2", "still there?", NOW + 10)))
        runCurrent()
        assertEquals(3, s.unread.count.value)

        // The same message again (the socket echoes, REST already saw it) doesn't change anything.
        s.live.emit(ChatEvent.MessageCreated(Message("m1", "c2", "u-c2", "still there?", NOW + 10)))
        runCurrent()
        assertEquals(1, s.unread.unreadByConversation["c2"])

        // A brand-new conversation counts too.
        s.live.emit(ChatEvent.MessageCreated(Message("m2", "c9", "u9", "hello", NOW + 20)))
        runCurrent()
        assertEquals(4, s.unread.count.value)

        // Read on another device (my read event) and my own reply both clear a conversation; their read doesn't.
        s.live.emit(ChatEvent.ConversationRead("c1", readerId = "u-c1", readAt = NOW + 30))
        runCurrent()
        assertEquals(4, s.unread.count.value)
        s.live.emit(ChatEvent.ConversationRead("c1", readerId = me, readAt = NOW + 30))
        s.live.emit(ChatEvent.MessageCreated(Message("m3", "c3", me, "on my way", NOW + 40)))
        runCurrent()
        assertEquals(2, s.unread.count.value)
        s.close()
    }

    @Test
    fun theOpenConversationDoesNotCountAndStaleReportsCannotBringItBack() = runTest {
        val s = setUp()
        advanceUntilIdle()
        s.unread.onConversationVisible("c1", visible = true)
        runCurrent()
        assertEquals(1, s.unread.count.value)

        s.live.emit(ChatEvent.MessageCreated(Message("m1", "c1", "u-c1", "are you here?", NOW + 10)))
        runCurrent()
        assertEquals(0, s.unread.unreadByConversation["c1"])

        // Back in the inbox, whose row still shows the old count until the read event lands.
        s.unread.onConversationVisible("c1", visible = false)
        s.unread.report(listOf(conversation("c1", NOW + 10, 3)))
        runCurrent()
        assertEquals(0, s.unread.unreadByConversation["c1"])
        assertEquals(1, s.unread.count.value)

        // A newer message after leaving counts again.
        s.live.emit(ChatEvent.MessageCreated(Message("m2", "c1", "u-c1", "?", NOW + 20)))
        runCurrent()
        assertEquals(2, s.unread.count.value)
        s.close()
    }

    @Test
    fun inboxReportsWinUnlessOlderThanWhatWeKnow() = runTest {
        val s = setUp()
        advanceUntilIdle()
        s.unread.report(listOf(conversation("c2", NOW - 5_000, 4)))
        assertEquals(4, s.unread.unreadByConversation["c2"])
        s.unread.report(listOf(conversation("c2", NOW - 6_000, 0)))
        runCurrent()
        assertEquals(4, s.unread.unreadByConversation["c2"])
        assertEquals(3, s.unread.count.value)
        s.close()
    }

    @Test
    fun pollsWhileTheSocketIsDownAndResyncsWhenItReturns() = runTest {
        val s = setUp(ChatSocket.Status.Reconnecting)
        advanceTimeBy(1_000)
        assertEquals(2, s.unread.count.value)

        page = listOf(conversation("c1", NOW - 1_000, 0), conversation("c2", NOW + 100, 1), conversation("c3", NOW - 9_000, 0))
        advanceTimeBy(CHAT_POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(1, s.unread.count.value)

        page = page.map { if (it.id == "c3") it.copy(lastMessageAt = NOW + 200, unreadCount = 5) else it }
        s.live.status.value = ChatSocket.Status.Connected
        runCurrent()
        assertEquals(2, s.unread.count.value)

        val before = s.h.requests(HttpMethod.Get, ApiRoutes.CONVERSATIONS).size
        s.unread.refresh()
        runCurrent()
        assertEquals(before + 1, s.h.requests(HttpMethod.Get, ApiRoutes.CONVERSATIONS).size)
        s.close()
    }

    @Test
    fun signingOutResetsToZero() = runTest {
        val s = setUp()
        advanceUntilIdle()
        assertEquals(2, s.unread.count.value)
        s.h.tokens.clear()
        runCurrent()
        assertEquals(0, s.unread.count.value)
        assertEquals(emptyMap(), s.unread.unreadByConversation)
        s.close()
    }

    @Test
    fun inboxViewModelFeedsTheTotalAndClearsARowWhenOpened() = runTest {
        val s = setUp()
        val vm = InboxViewModel(s.h.api, s.live, s.h.session, s.h.messages, s.unread)
        advanceUntilIdle()
        s.live.emit(ChatEvent.MessageCreated(Message("m1", "c2", "u-c2", "new", NOW + 10)))
        runCurrent()
        assertEquals(3, s.unread.count.value)
        vm.onOpen("c1")
        runCurrent()
        assertEquals(0, vm.conversations.state.value.items.first { it.id == "c1" }.unreadCount)
        assertEquals(2, s.unread.count.value)
        vm.close()
        s.close()
    }
}
