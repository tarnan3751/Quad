package com.campusmarket.app.data

import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.PlatformKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSocketTest {
    /** Counts session reads: the connection loop reads the token once per connection attempt. */
    private class CountingTokens(initial: StoredSession) : TokenStore {
        private val inner = InMemoryTokenStore(initial)
        var reads = 0
        override val session: StateFlow<StoredSession?> get() = inner.session
        override fun current(): StoredSession? = inner.session.value.also { reads++ }
        override fun save(session: StoredSession) = inner.save(session)
        override fun clear() = inner.clear()
    }

    private fun TestScope.socket(network: MutableSharedFlow<Unit>): Pair<ChatSocket, CountingTokens> {
        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { respondError(HttpStatusCode.ServiceUnavailable, "UNAVAILABLE") }
            },
        )
        val http = HttpClient(engine) { installCampusMarketDefaults(debugLogging = false) }
        val server = ServerConfig(AppEnvironment(isDebug = false, defaultBaseUrl = BASE, platform = PlatformKind.DESKTOP))
        val tokens = CountingTokens(session())
        val api = ApiClient(http, server, tokens, nowMillis = { NOW })
        return ChatSocket(http, server, api, tokens, backgroundScope, network) to tokens
    }

    @Test
    fun networkComingBackSkipsTheBackoffWait() = runTest {
        val network = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val (socket, tokens) = socket(network)
        backgroundScope.launch { socket.events.collect { } }
        runCurrent()
        assertEquals(ChatSocket.Status.Reconnecting, socket.status.value)

        val attempts = tokens.reads
        advanceTimeBy(500)
        assertEquals(attempts, tokens.reads, "waits out the first backoff (at least 1 s)")

        network.emit(Unit)
        runCurrent()
        assertTrue(tokens.reads > attempts, "retries as soon as the network is back")
    }

    @Test
    fun networkEventsAreIgnoredWhileNobodyListens() = runTest {
        val network = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val (socket, tokens) = socket(network)
        network.emit(Unit)
        advanceTimeBy(10_000)
        assertEquals(ChatSocket.Status.Idle, socket.status.value)
        assertEquals(0, tokens.reads)
    }
}
