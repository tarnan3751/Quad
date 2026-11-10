package com.campusmarket.app.data

import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.AuthResponse
import com.campusmarket.core.ChatCommand
import com.campusmarket.core.ChatEvent
import com.campusmarket.core.Message
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MINUTE = 60_000L
private const val TOKEN_LIFETIME = 15 * MINUTE

/** The chat socket across access-token expiry: replaced ahead of time, or refreshed and reopened at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSocketRenewalTest {
    private class FakeConnection(val token: String) : ChatConnection {
        private val incoming = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        var closedByClient = false
        var answerPings = true
        private var rejected = false

        override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()

        override suspend fun send(text: String) {
            sent += text
            if (answerPings && text == PING) incoming.trySend(PONG)
        }

        override suspend fun close() {
            closedByClient = true
            incoming.close()
        }

        override suspend fun wasRejected(): Boolean = rejected

        fun push(event: ChatEvent) {
            incoming.trySend(ApiJson.encodeToString(ChatEvent.serializer(), event))
        }

        fun closedByServer(rejected: Boolean) {
            this.rejected = rejected
            incoming.close()
        }
    }

    private class Fixture(scope: TestScope) {
        val connections = mutableListOf<FakeConnection>()
        var failOpens = false
        var answerPings = true
        var refreshes = 0
        var refreshFails = false
        val now = { NOW + scope.currentTime }
        val harness = Harness(initial = session(expiresAt = NOW + TOKEN_LIFETIME), scope = scope) { req ->
            check(req.url.encodedPath == ApiRoutes.REFRESH) { "unexpected ${req.url}" }
            if (refreshFails) throw kotlinx.io.IOException("offline")
            refreshes++
            respondJson(AuthResponse.serializer(), authResponse("access-${refreshes + 1}", "refresh-${refreshes + 1}", expiresAt = now() + TOKEN_LIFETIME))
        }
        val api = ApiClient(harness.http, harness.server, harness.tokens, nowMillis = now)
        val socket = ChatSocket(
            connector = { token ->
                if (failOpens) throw kotlinx.io.IOException("no route")
                FakeConnection(token).also {
                    it.answerPings = answerPings
                    connections += it
                }
            },
            api = api,
            tokens = harness.tokens,
            scope = scope.backgroundScope,
            nowMillis = now,
        )
        val events = mutableListOf<ChatEvent>()
        val statuses = mutableListOf<ChatSocket.Status>()

        init {
            scope.backgroundScope.launch { socket.events.collect { events += it } }
            scope.backgroundScope.launch { socket.status.collect { statuses += it } }
        }
    }

    private fun message(id: String) = ChatEvent.MessageCreated(Message(id, "c1", "u2", "hello $id", NOW))

    @Test
    fun theSocketIsReplacedBeforeItsTokenExpiresWithoutAGap() = runTest {
        val f = Fixture(this)
        runCurrent()
        val first = f.connections.single()
        assertEquals("access-1", first.token)
        first.push(message("m1"))
        runCurrent()

        advanceTimeBy(TOKEN_LIFETIME - ChatSocket.RENEW_BEFORE_EXPIRY_MS - 1_000)
        runCurrent()
        assertEquals(1, f.connections.size, "not yet")
        assertEquals(0, f.refreshes)

        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, f.refreshes, "refreshed a minute before the token expires")
        val second = f.connections.last()
        assertEquals(2, f.connections.size)
        assertEquals("access-2", second.token)
        assertEquals(PING, second.sent.first(), "the new socket is confirmed with a ping")
        assertFalse(first.closedByClient, "the old socket lingers for events sent just before the new one subscribed")

        // During the handover the server delivers on both sockets.
        first.push(message("m2"))
        second.push(message("m2"))
        second.push(message("m3"))
        runCurrent()
        advanceTimeBy(ChatSocket.HANDOVER_GRACE_MS + 100)
        runCurrent()
        assertTrue(first.closedByClient)
        assertFalse(second.closedByClient)
        second.push(message("m4"))
        runCurrent()

        assertEquals(listOf("m1", "m2", "m3", "m4"), f.events.map { (it as ChatEvent.MessageCreated).message.id })
        assertFalse(ChatSocket.Status.Reconnecting in f.statuses, "never reconnecting: ${f.statuses}")
        assertEquals(ChatSocket.Status.Connected, f.statuses.last())

        // And again for the next token.
        advanceTimeBy(TOKEN_LIFETIME)
        runCurrent()
        assertEquals(2, f.refreshes)
        assertEquals("access-3", f.connections.last().token)
        assertEquals(ChatSocket.Status.Connected, f.socket.status.value)
    }

    @Test
    fun aTokenSomeoneElseAlreadyRefreshedIsReused() = runTest {
        val f = Fixture(this)
        runCurrent()
        advanceTimeBy(TOKEN_LIFETIME - 2 * MINUTE)
        // A REST call refreshed the session in the meantime.
        f.harness.tokens.save(session("access-rest", "refresh-rest", expiresAt = f.now() + TOKEN_LIFETIME))
        advanceTimeBy(2 * MINUTE)
        runCurrent()
        assertEquals(0, f.refreshes, "no second refresh")
        assertEquals("access-rest", f.connections.last().token)
    }

    @Test
    fun ifTheNewSocketCannotBeOpenedTheOldOneStaysUntilTheServerClosesIt() = runTest {
        val f = Fixture(this)
        runCurrent()
        val first = f.connections.single()
        f.failOpens = true
        advanceTimeBy(TOKEN_LIFETIME - ChatSocket.RENEW_BEFORE_EXPIRY_MS + 500)
        runCurrent()
        assertEquals(1, f.refreshes)
        assertEquals(1, f.connections.size)
        assertFalse(first.closedByClient)
        first.push(message("m1"))
        runCurrent()
        assertEquals(1, f.events.size, "still listening on the old socket")
        assertEquals(ChatSocket.Status.Connected, f.socket.status.value)

        // The server closes it when the token expires: reconnect at once with the token already in hand.
        f.failOpens = false
        first.closedByServer(rejected = true)
        runCurrent()
        assertEquals(2, f.connections.size, "no backoff wait")
        assertEquals("access-2", f.connections.last().token)
        assertEquals(1, f.refreshes)
        assertEquals(ChatSocket.Status.Connected, f.socket.status.value)
    }

    @Test
    fun aSocketThatNeverAnswersIsNotSwitchedTo() = runTest {
        val f = Fixture(this)
        runCurrent()
        val first = f.connections.single()
        f.answerPings = false
        advanceTimeBy(TOKEN_LIFETIME - ChatSocket.RENEW_BEFORE_EXPIRY_MS + 500)
        runCurrent()
        val silent = f.connections.last()
        advanceTimeBy(ChatSocket.HANDOVER_TIMEOUT_MS + 100)
        runCurrent()
        assertTrue(silent.closedByClient)
        assertFalse(first.closedByClient)
        first.push(message("m1"))
        runCurrent()
        assertEquals(1, f.events.size)
    }

    @Test
    fun aRejectedSocketRefreshesAndReconnectsAtOnce() = runTest {
        val f = Fixture(this)
        runCurrent()
        advanceTimeBy(10_000)
        // E.g. the device slept through the renewal and the server closed the socket on expiry.
        f.connections.single().closedByServer(rejected = true)
        runCurrent()
        assertEquals(1, f.refreshes)
        assertEquals(2, f.connections.size, "reconnected without a backoff wait")
        assertEquals("access-2", f.connections.last().token)
        assertEquals(ChatSocket.Status.Connected, f.socket.status.value)
        // Reconnecting is reported in between, so screens re-fetch what they may have missed.
        assertTrue(ChatSocket.Status.Reconnecting in f.statuses)

        // Rejected again straight away: now it backs off instead of spinning.
        f.connections.last().closedByServer(rejected = true)
        runCurrent()
        assertEquals(2, f.connections.size)
        assertEquals(ChatSocket.Status.Reconnecting, f.socket.status.value)
        advanceTimeBy(ChatSocket.MIN_BACKOFF_MS + 600)
        runCurrent()
        assertEquals(3, f.connections.size)
    }

    @Test
    fun aDroppedSocketBacksOffWithoutRefreshing() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.connections.single().closedByServer(rejected = false)
        runCurrent()
        assertEquals(1, f.connections.size)
        assertEquals(ChatSocket.Status.Reconnecting, f.socket.status.value)
        advanceTimeBy(ChatSocket.MIN_BACKOFF_MS + 600)
        runCurrent()
        assertEquals(2, f.connections.size)
        assertEquals("access-1", f.connections.last().token)
        assertEquals(0, f.refreshes)
    }

    @Test
    fun aRefreshThatFailsOfflineLeavesTheSocketAlone() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.refreshFails = true
        advanceTimeBy(TOKEN_LIFETIME - ChatSocket.RENEW_BEFORE_EXPIRY_MS + 500)
        runCurrent()
        assertEquals(1, f.connections.size)
        assertFalse(f.connections.single().closedByClient)
        assertEquals(ChatSocket.Status.Connected, f.socket.status.value)
    }

    @Test
    fun keepsPingingTheCurrentSocket() = runTest {
        val f = Fixture(this)
        runCurrent()
        advanceTimeBy(ChatSocket.PING_INTERVAL_MS * 2 + 100)
        runCurrent()
        assertEquals(2, f.connections.single().sent.count { it == PING })
        assertTrue(f.events.isEmpty(), "pongs are swallowed")
    }

    private companion object {
        val PING = ApiJson.encodeToString(ChatCommand.serializer(), ChatCommand.Ping)
        val PONG = ApiJson.encodeToString(ChatEvent.serializer(), ChatEvent.Pong)
    }
}
