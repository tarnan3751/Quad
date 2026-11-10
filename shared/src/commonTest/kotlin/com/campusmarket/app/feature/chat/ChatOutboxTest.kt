package com.campusmarket.app.feature.chat

import com.campusmarket.app.data.Harness
import com.campusmarket.app.data.NOW
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.data.testUser
import com.campusmarket.core.Conversation
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.OkResponse
import com.campusmarket.core.Page
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The outbox on disk: what survives the process, whose it is, and when it is forgotten. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatOutboxTest {
    private val failed = PendingMessage("id-1", "Is 5pm ok?", NOW, Delivery.Failed, "Not sent. Check your connection.")
    private val sending = PendingMessage("id-2", "On my way", NOW + 1)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun draftsAndUnsentMessagesAreRestoredFromStorage() {
        val disk = FakeSecretStorage()
        ChatOutbox(disk).apply {
            save("u1", "c1", "half typed", listOf(failed))
            save("u1", "c2", "", listOf(sending))
            save("u1", "c3", "only a draft", emptyList())
        }

        val restored = ChatOutbox(disk)
        assertEquals(ChatOutbox.Entry("half typed", listOf(failed)), restored.load("u1", "c1"))
        assertEquals("only a draft", restored.load("u1", "c3").draft)
        // It was still sending when the process died: not sent as far as we know, but the server may have it.
        val interrupted = restored.load("u1", "c2").pending.single()
        assertEquals(Delivery.Failed, interrupted.delivery)
        assertEquals("Not sent", interrupted.error)
        assertTrue(interrupted.unconfirmed)
        assertEquals("id-2", interrupted.localId, "keeps the id the server may know it by")
    }

    @Test
    fun theStoredTextIsOnlyEverGivenToSecretStorage() {
        val disk = FakeSecretStorage()
        val outbox = ChatOutbox(disk)
        outbox.save("u1", "c1", "private words", listOf(failed))
        assertTrue("private words" in disk.value.orEmpty())
        outbox.save("u1", "c1", "", emptyList())
        assertNull(disk.value, "an empty outbox leaves nothing behind")
    }

    @Test
    fun anotherUserNeverSeesItAndReplacesIt() {
        val disk = FakeSecretStorage()
        val outbox = ChatOutbox(disk)
        outbox.save("u1", "c1", "mine", listOf(failed))

        assertEquals(ChatOutbox.Entry(), outbox.load("u2", "c1"))
        assertEquals(ChatOutbox.Entry(), ChatOutbox(disk).load("u2", "c1"))
        assertEquals("mine", ChatOutbox(disk).load("u1", "c1").draft, "reading as someone else changes nothing")

        outbox.save("u2", "c1", "theirs", emptyList())
        assertEquals(ChatOutbox.Entry(), ChatOutbox(disk).load("u1", "c1"))
        assertFalse("mine" in disk.value.orEmpty())
        assertFalse("Is 5pm ok?" in disk.value.orEmpty())
    }

    @Test
    fun unreadableStorageStartsEmptyAndIsCleared() {
        val corrupt = FakeSecretStorage("{not json")
        assertEquals(ChatOutbox.Entry(), ChatOutbox(corrupt).load("u1", "c1"))
        assertNull(corrupt.value)

        val broken = FakeSecretStorage("x").apply { failReads = true }
        val errors = mutableListOf<String>()
        val outbox = ChatOutbox(broken, onError = { what, _ -> errors += what })
        assertEquals(ChatOutbox.Entry(), outbox.load("u1", "c1"))
        assertEquals(1, errors.size)

        // A failing write keeps the outbox in memory for this run.
        broken.failWrites = true
        outbox.save("u1", "c1", "draft", emptyList())
        assertEquals("draft", outbox.load("u1", "c1").draft)
        assertEquals(2, errors.size)
    }

    @Test
    fun writesAreBatchedWhileTyping() = runTest {
        val disk = FakeSecretStorage()
        val outbox = ChatOutbox(disk, backgroundScope)
        "Is it still available".forEachIndexed { i, _ ->
            outbox.save("u1", "c1", "Is it still available".take(i + 1), emptyList())
            advanceTimeBy(50)
        }
        assertTrue(disk.writes <= 3, "${disk.writes} writes for 21 keystrokes")
        advanceTimeBy(1_000)
        assertTrue("Is it still available" in disk.value.orEmpty())
        assertEquals("Is it still available", ChatOutbox(disk).load("u1", "c1").draft)
    }

    @Test
    fun entriesFollowChangesForTheirUserOnly() = runTest {
        val outbox = ChatOutbox()
        val seen = mutableListOf<Set<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { outbox.entries("u1").collect { seen += it.keys } }
        outbox.save("u1", "c1", "", listOf(failed))
        outbox.save("u1", "c2", "draft", emptyList())
        outbox.save("u1", "c1", "", emptyList())
        runCurrent()
        assertEquals(listOf(emptySet(), setOf("c1"), setOf("c1", "c2"), setOf("c2")), seen)
        assertTrue(outbox.entries("u2").first().isEmpty())
        assertEquals(listOf(failed), ChatOutbox().apply { save("u1", "c1", "", listOf(failed, sending)) }.load("u1", "c1").notSent)
    }

    @Test
    fun signingOutAndDeletingTheAccountForgetTheOutbox() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val disk = FakeSecretStorage()
        val outbox = ChatOutbox(disk)
        val h = Harness(scope = this) { respondJson(OkResponse.serializer(), OkResponse()) }
        val session = SessionManager(h.api, h.tokens, backgroundScope, signOutListeners = { listOf(outbox) })

        outbox.save(testUser.id, "c1", "draft", listOf(failed))
        assertNotNull(disk.value)
        session.signOut()
        assertNull(disk.value)
        assertEquals(ChatOutbox.Entry(), outbox.load(testUser.id, "c1"))

        outbox.save(testUser.id, "c1", "again", emptyList())
        session.deleteAccountWithCredentials("ada@uni.edu", "password1")
        assertNull(disk.value)
    }

    @Test
    fun aSessionEndedByTheServerKeepsTheOutboxForTheNextSignIn() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val disk = FakeSecretStorage()
        val outbox = ChatOutbox(disk)
        val h = Harness(scope = this) { req ->
            respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, req.url.encodedPath)
        }
        SessionManager(h.api, h.tokens, backgroundScope, signOutListeners = { listOf(outbox) })
        outbox.save(testUser.id, "c1", "draft", listOf(failed))

        runCatching { h.api.conversations() }
        advanceUntilIdle()
        assertNull(h.tokens.current(), "the session expired")
        assertEquals("draft", ChatOutbox(disk).load(testUser.id, "c1").draft)
    }

    @Test
    fun theInboxFlagsConversationsWithUnsentMessages() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val outbox = ChatOutbox()
        val h = Harness(scope = this) { respondJson(Page.serializer(Conversation.serializer()), Page(emptyList(), null)) }
        val vm = InboxViewModel(h.api, FakeLiveChat(), h.session, h.messages, outbox = outbox)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.notSent.collect { } }
        advanceUntilIdle()
        assertTrue(vm.notSent.value.isEmpty())

        val newer = failed.copy(localId = "id-3", body = "Still there?")
        outbox.save(testUser.id, "c1", "", listOf(failed, newer))
        outbox.save(testUser.id, "c2", "a draft is not a failure", emptyList())
        outbox.save(testUser.id, "c3", "", listOf(sending))
        runCurrent()
        assertEquals(mapOf("c1" to newer), vm.notSent.value, "the newest unsent message of each conversation")

        // Retried (sending again), then gone (delivered or deleted).
        outbox.save(testUser.id, "c1", "", listOf(newer.copy(delivery = Delivery.Sending)))
        runCurrent()
        assertTrue(vm.notSent.value.isEmpty())
        outbox.save(testUser.id, "c1", "", listOf(newer))
        runCurrent()
        assertEquals(setOf("c1"), vm.notSent.value.keys)
        outbox.save(testUser.id, "c1", "", emptyList())
        runCurrent()
        assertTrue(vm.notSent.value.isEmpty())
        vm.close()
    }
}
