package com.campusmarket.app.data

import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ChatCommand
import com.campusmarket.core.ChatEvent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.parameter
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Clock

/** One open chat websocket, reduced to what [ChatSocket] needs (tests script it). */
interface ChatConnection {
    /** The next text frame, or null once the socket has closed. */
    suspend fun receive(): String?

    suspend fun send(text: String)

    suspend fun close()

    /** After [receive] returned null: whether the server closed it for a bad or expired token (policy violation). */
    suspend fun wasRejected(): Boolean
}

/** Opens a websocket authenticated with [token]. Throws when the connection can't be made. */
fun interface ChatConnector {
    suspend fun open(token: String): ChatConnection
}

/**
 * Live chat over `WS /ws?token=…`.
 *
 * - [events] is hot and connection-on-demand: the socket opens when the first collector subscribes and closes
 *   5 s after the last one leaves (so it only runs while a chat screen is visible). Collect it from a view model.
 * - The server closes a socket when its access token expires (about every 15 minutes). A minute before that the
 *   token is refreshed and a second socket opened; once that one answers, the old one is closed. Events never stop
 *   and [status] stays [Status.Connected]; a message delivered on both sockets is emitted once.
 * - Otherwise it reconnects with exponential backoff (1 s → 30 s, jittered). A policy-violation close (the token was
 *   rejected after all) refreshes the token and reconnects at once; if the session has ended it waits for the next
 *   sign-in.
 * - Server `pong`s are swallowed. Unknown event types are ignored (forward compatible).
 * - Messages sent via REST are echoed here too (`MessageCreated`), so de-duplicate by message id.
 */
class ChatSocket(
    private val connector: ChatConnector,
    private val api: ApiClient,
    private val tokens: TokenStore,
    scope: CoroutineScope,
    /** Emits when a network becomes available: a reconnect waiting out its backoff retries at once. */
    private val networkAvailable: Flow<Unit> = emptyFlow(),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    constructor(
        http: HttpClient,
        server: ServerConfig,
        api: ApiClient,
        tokens: TokenStore,
        scope: CoroutineScope,
        networkAvailable: Flow<Unit> = emptyFlow(),
    ) : this(KtorChatConnector(http, server), api, tokens, scope, networkAvailable)

    enum class Status { Idle, Connecting, Connected, Reconnecting }

    private val _status = MutableStateFlow(Status.Idle)

    /** Connection state, for a "Reconnecting…" hint. Idle when nobody collects [events]. */
    val status: StateFlow<Status> = _status.asStateFlow()

    private val kick = Channel<Unit>(Channel.CONFLATED)

    val events: SharedFlow<ChatEvent> = channelFlow { runConnectionLoop(this) }
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), replay = 0)

    /** Skip the current backoff wait (e.g. app returned to the foreground or the network came back). */
    fun reconnectNow() {
        kick.trySend(Unit)
    }

    private suspend fun runConnectionLoop(out: ProducerScope<ChatEvent>) {
        var attempt = 0
        // After a rejected socket one reconnect skips the backoff; a second rejection in a row waits like any failure.
        var skipBackoffOnReject = true
        val networkWatch = out.launch {
            networkAvailable
                .catch { } // no monitor (e.g. a missing permission): the backoff alone still reconnects
                .collect { if (_status.value == Status.Reconnecting) reconnectNow() }
        }
        try {
            while (true) {
                val token = try {
                    api.freshAccessToken()
                } catch (e: ApiError) {
                    _status.value = Status.Reconnecting
                    backoff(attempt++)
                    continue
                }
                if (token == null) {
                    // Signed out: park until someone signs in again.
                    _status.value = Status.Idle
                    tokens.session.first { it != null }
                    attempt = 0
                    continue
                }

                if (_status.value != Status.Reconnecting) _status.value = Status.Connecting
                val connection = try {
                    connector.open(token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                var rejectedToken: String? = null
                if (connection != null) {
                    attempt = 0
                    val openedAt = nowMillis()
                    rejectedToken = serve(out, connection, token)
                    if (nowMillis() - openedAt >= STABLE_MS) skipBackoffOnReject = true
                }

                _status.value = Status.Reconnecting
                if (rejectedToken != null) {
                    val refreshed = try {
                        api.refreshedAccessToken(replacing = rejectedToken) != null
                    } catch (e: ApiError) {
                        false // network trouble: backoff and retry
                    }
                    if (refreshed && skipBackoffOnReject) {
                        skipBackoffOnReject = false
                        continue
                    }
                }
                backoff(attempt++)
            }
        } finally {
            networkWatch.cancel()
            _status.value = Status.Idle
        }
    }

    /**
     * Forwards events from [first] and from every socket that replaces it ahead of its token's expiry. Returns once
     * the current socket has closed: its token when the server rejected it (expired or revoked), else null.
     */
    private suspend fun serve(out: ProducerScope<ChatEvent>, first: ChatConnection, firstToken: String): String? {
        var current = first
        var token = firstToken
        val relay = EventRelay(out)
        // Sockets that were replaced but may still hold an event sent just before the new one subscribed.
        val retiring = ArrayList<ChatConnection>()
        try {
            return coroutineScope {
                val closed = Channel<ChatConnection>(Channel.UNLIMITED)
                fun pump(connection: ChatConnection, alive: CompletableDeferred<Unit>? = null): Job = launch {
                    try {
                        while (true) {
                            val text = connection.receive() ?: break
                            alive?.complete(Unit)
                            val event = decode(text) ?: continue
                            if (event !is ChatEvent.Pong) relay.emit(event)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // dropped: same as closed
                    } finally {
                        closed.trySend(connection)
                    }
                }

                suspend fun awaitCurrentClosed() {
                    while (closed.receive() !== current) Unit
                }

                pump(current)
                _status.value = Status.Connected
                val pinger = launch {
                    while (true) {
                        delay(PING_INTERVAL_MS)
                        runCatching { current.send(PING) }
                    }
                }
                try {
                    while (true) {
                        val closedFirst = withTimeoutOrNull(renewalDelay(token)) { awaitCurrentClosed() } != null
                        if (closedFirst) break

                        // The token is about to expire: move to a socket opened with a new one before the server
                        // closes this one. If that can't be done now, the server's close takes the reconnect path.
                        val newToken = try {
                            api.refreshedAccessToken(replacing = token)
                        } catch (e: ApiError) {
                            null
                        }
                        val next = if (newToken == null) null else openAndConfirm(newToken, ::pump)
                        if (next == null) {
                            awaitCurrentClosed()
                            break
                        }
                        val old = current
                        current = next
                        token = newToken!!
                        retiring += old
                        launch {
                            delay(HANDOVER_GRACE_MS)
                            old.close()
                        }
                    }
                    token.takeIf { current.wasRejected() }
                } finally {
                    pinger.cancel()
                    coroutineContext.cancelChildren()
                }
            }
        } finally {
            withContext(NonCancellable) { (retiring + current).forEach { runCatching { it.close() } } }
        }
    }

    /**
     * Opens a socket with [token] and waits until the server answers a ping on it: only then is it subscribed to my
     * events, so the socket it replaces can go. Null when that doesn't happen in time.
     */
    private suspend fun CoroutineScope.openAndConfirm(
        token: String,
        pump: (ChatConnection, CompletableDeferred<Unit>?) -> Job,
    ): ChatConnection? {
        val connection = try {
            connector.open(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val alive = CompletableDeferred<Unit>()
        val job = pump(connection, alive)
        val confirmed = try {
            connection.send(PING)
            withTimeoutOrNull(HANDOVER_TIMEOUT_MS) { alive.await() } != null
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { connection.close() } }
            throw e
        } catch (e: Exception) {
            false
        }
        if (!confirmed) {
            job.cancel()
            runCatching { connection.close() }
            return null
        }
        return connection
    }

    /** How long a socket opened with [token] may run before it should be replaced. */
    private fun renewalDelay(token: String): Long {
        val expiresAt = tokens.current()?.takeIf { it.accessToken == token }?.accessTokenExpiresAt ?: return MIN_RENEWAL_MS
        return (expiresAt - RENEW_BEFORE_EXPIRY_MS - nowMillis()).coerceAtLeast(MIN_RENEWAL_MS)
    }

    /** Passes events on, once each: during a handover both sockets deliver the same message. */
    private class EventRelay(private val out: ProducerScope<ChatEvent>) {
        private val mutex = Mutex()
        private val recentMessageIds = LinkedHashSet<String>()

        suspend fun emit(event: ChatEvent) {
            if (event is ChatEvent.MessageCreated) {
                val fresh = mutex.withLock {
                    recentMessageIds.add(event.message.id).also {
                        if (recentMessageIds.size > RECENT_IDS) recentMessageIds.remove(recentMessageIds.first())
                    }
                }
                if (!fresh) return
            }
            out.send(event)
        }
    }

    private suspend fun backoff(attempt: Int) {
        val base = (MIN_BACKOFF_MS shl attempt.coerceAtMost(5)).coerceAtMost(MAX_BACKOFF_MS)
        val wait = base + Random.nextLong(0, 500)
        withTimeoutOrNull(wait) { kick.receive() }
    }

    private fun decode(text: String): ChatEvent? =
        try {
            ApiJson.decodeFromString(ChatEvent.serializer(), text)
        } catch (e: Exception) {
            null
        }

    internal companion object {
        const val PING_INTERVAL_MS = 25_000L
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L

        /** A socket is replaced this long before its token expires (the REST client refreshes 30 s before). */
        const val RENEW_BEFORE_EXPIRY_MS = 60_000L

        /** Never replace a socket sooner than this (a device clock far ahead makes every token look expired). */
        const val MIN_RENEWAL_MS = 30_000L
        const val HANDOVER_TIMEOUT_MS = 10_000L
        const val HANDOVER_GRACE_MS = 2_000L
        const val STABLE_MS = 5_000L
        const val RECENT_IDS = 64
        val PING = ApiJson.encodeToString(ChatCommand.serializer(), ChatCommand.Ping)
    }
}

private class KtorChatConnector(private val http: HttpClient, private val server: ServerConfig) : ChatConnector {
    override suspend fun open(token: String): ChatConnection =
        KtorChatConnection(http.webSocketSession(urlString = server.wsUrl(ApiRoutes.WS)) { parameter("token", token) })
}

private class KtorChatConnection(private val session: DefaultClientWebSocketSession) : ChatConnection {
    override suspend fun receive(): String? {
        while (true) {
            val frame = session.incoming.receiveCatching().getOrNull() ?: return null
            if (frame is Frame.Text) return frame.readText()
        }
    }

    override suspend fun send(text: String) = session.send(Frame.Text(text))

    override suspend fun close() {
        try {
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) { session.close() }
        } finally {
            session.cancel()
        }
    }

    override suspend fun wasRejected(): Boolean =
        withTimeoutOrNull(CLOSE_TIMEOUT_MS) { session.closeReason.await() }?.code == CloseReason.Codes.VIOLATED_POLICY.code

    private companion object {
        const val CLOSE_TIMEOUT_MS = 2_000L
    }
}
