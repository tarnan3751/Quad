package com.campusmarket.app.feature.chat

import com.campusmarket.app.data.ChatSocket
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Inbox live updates: bump / create rows, unread badges, read events, fallback sync. */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {
    private val me = testUser.id

    private fun conversation(id: String, otherId: String, lastAt: Long, unread: Int, preview: String = "hello") =
        Conversation(id, listingSummary(id = "l-$id", sellerId = otherId), publicUser(otherId, "User $otherId"), preview, lastAt, unread, NOW - 100_000)

    private var page = listOf(
        conversation("c1", "u2", NOW - 1_000, 0),
        conversation("c2", "u3", NOW - 5_000, 1),
    )
    private val c9 = conversation("c9", "u9", NOW + 50, 1, "New buyer here")
    private var offline = false

    private fun TestScope.setUp(status: ChatSocket.Status = ChatSocket.Status.Connected): Triple<InboxViewModel, VmHarness, FakeLiveChat> {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val h = VmHarness(this) { req ->
            if (offline) throw kotlinx.io.IOException("offline")
            when (req.url.encodedPath) {
                ApiRoutes.CONVERSATIONS -> respondJson(Page.serializer(Conversation.serializer()), Page(page, null))
                ApiRoutes.conversation("c9") -> respondJson(Conversation.serializer(), c9)
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        val live = FakeLiveChat(status)
        return Triple(InboxViewModel(h.api, live, h.session, h.userMessages), h, live)
    }

    private fun InboxViewModel.rows() = conversations.state.value.items.map { "${it.id}:${it.unreadCount}" }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun incomingMessageBumpsTheConversationAndCountsUnreadOnce() = runTest {
        val (vm, _, live) = setUp()
        advanceUntilIdle()
        assertEquals(listOf("c1:0", "c2:1"), vm.rows())

        val m = Message("m1", "c2", "u3", "Is  it\nstill available?", NOW + 10)
        live.emit(ChatEvent.MessageCreated(m))
        runCurrent()
        assertEquals(listOf("c2:2", "c1:0"), vm.rows())
        assertEquals("Is it still available?", vm.conversations.state.value.items.first().lastMessagePreview)

        live.emit(ChatEvent.MessageCreated(m)) // duplicate delivery
        runCurrent()
        assertEquals(listOf("c2:2", "c1:0"), vm.rows())

        // My own message (sent from the conversation screen) bumps without a badge.
        live.emit(ChatEvent.MessageCreated(Message("m2", "c1", me, "Sure", NOW + 20)))
        runCurrent()
        assertEquals(listOf("c1:0", "c2:2"), vm.rows())

        // An event older than what a sync already showed doesn't double count.
        live.emit(ChatEvent.MessageCreated(Message("m0", "c2", "u3", "old", NOW - 9_000)))
        runCurrent()
        assertEquals(listOf("c1:0", "c2:2"), vm.rows())
        vm.close()
    }

    @Test
    fun readByMeClearsTheBadgeReadByThemDoesNot() = runTest {
        val (vm, _, live) = setUp()
        advanceUntilIdle()
        live.emit(ChatEvent.ConversationRead("c2", readerId = "u3", readAt = NOW))
        runCurrent()
        assertEquals(listOf("c1:0", "c2:1"), vm.rows())
        live.emit(ChatEvent.ConversationRead("c2", readerId = me, readAt = NOW))
        runCurrent()
        assertEquals(listOf("c1:0", "c2:0"), vm.rows())
        vm.close()
    }

    @Test
    fun messageInAnUnknownConversationFetchesAndInsertsIt() = runTest {
        val (vm, h, live) = setUp()
        advanceUntilIdle()
        live.emit(ChatEvent.MessageCreated(Message("m5", "c9", "u9", "New buyer here", NOW + 50)))
        advanceUntilIdle()
        assertEquals(listOf("c9:1", "c1:0", "c2:1"), vm.rows())
        assertEquals(1, h.requests(HttpMethod.Get, ApiRoutes.conversation("c9")).size)
        vm.close()
    }

    @Test
    fun staleSyncDoesNotUndoANewerLiveUpdate() {
        val live = conversation("c1", "u2", NOW + 500, 2, "newest via socket")
        val stale = conversation("c1", "u2", NOW - 1_000, 1, "old")
        val other = conversation("c2", "u3", NOW, 0)
        val merged = mergeConversations(listOf(live, conversation("c3", "u4", NOW - 50_000, 0)), listOf(stale, other))
        assertEquals(listOf("c1", "c2", "c3"), merged.map { it.id })
        assertEquals("newest via socket", merged[0].lastMessagePreview)
        assertEquals(2, merged[0].unreadCount)
        // A fresher server copy wins (e.g. read elsewhere → unread 0).
        val fresher = conversation("c1", "u2", NOW + 500, 0, "newest via socket")
        assertEquals(0, mergeConversations(listOf(live), listOf(fresher)).single().unreadCount)
    }

    @Test
    fun resyncsTheFirstPageWhileDisconnectedAndOnResume() = runTest {
        val (vm, h, live) = setUp(status = ChatSocket.Status.Reconnecting)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("c1:0", "c2:1"), vm.rows())

        page = listOf(conversation("c2", "u3", NOW + 100, 3, "three new")) + page.filter { it.id == "c1" }
        advanceTimeBy(CHAT_POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf("c2:3", "c1:0"), vm.rows())

        live.status.value = ChatSocket.Status.Connected
        runCurrent()
        val before = h.requests(HttpMethod.Get, ApiRoutes.CONVERSATIONS).size
        vm.onResume() // first resume = initial display
        vm.onResume()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(before + 1, h.requests(HttpMethod.Get, ApiRoutes.CONVERSATIONS).size)
        assertEquals(1, live.reconnectRequests)
        vm.close()
    }

    @Test
    fun openedOfflineLoadsByItselfOnceTheConnectionIsBack() = runTest {
        offline = true
        val (vm, _, live) = setUp(status = ChatSocket.Status.Reconnecting)
        runCurrent()
        assertNotNull(vm.conversations.state.value.error)

        offline = false
        live.status.value = ChatSocket.Status.Connected
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("c1:0", "c2:1"), vm.rows())
        assertNull(vm.conversations.state.value.error)
        vm.close()
    }
}
