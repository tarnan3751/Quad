package com.campusmarket.app.data

import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ChatCommand
import com.campusmarket.core.ChatEvent
import com.campusmarket.core.Message
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.parameter
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * Live chat over `WS /ws?token=…`.
 *
 * - [events] is hot and connection-on-demand: the socket opens when the first collector subscribes and closes
 *   5 s after the last one leaves (so it only runs while a chat screen is visible). Collect it from a view model.
 * - Reconnects automatically with exponential backoff (1 s → 30 s, jittered). A policy-violation close (bad/expired
 *   token) forces a token refresh before reconnecting; if the session has ended it waits for the next sign-in.
 * - Server `pong`s are swallowed. Unknown event types are ignored (forward compatible).
 * - Messages sent via REST are echoed here too (`MessageCreated`), so de-duplicate by message id.
 *
 * Recommended: send with `ApiClient.sendMessage` (typed errors such as `BLOCKED`), use [send] only as an optimisation.
 */
class ChatSocket(
    private val http: HttpClient,
    private val server: ServerConfig,
    private val api: ApiClient,
    private val tokens: TokenStore,
    scope: CoroutineScope,
) {
    enum class Status { Idle, Connecting, Connected, Reconnecting }

    private val _status = MutableStateFlow(Status.Idle)

    /** Connection state, for a "Reconnecting…" hint. Idle when nobody collects [events]. */
    val status: StateFlow<Status> = _status.asStateFlow()

    private val activeSession = MutableStateFlow<DefaultClientWebSocketSession?>(null)
    private val kick = Channel<Unit>(Channel.CONFLATED)

    val events: SharedFlow<ChatEvent> = channelFlow { runConnectionLoop(this) }
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), replay = 0)

    /** Only the [ChatEvent.MessageCreated] events, unwrapped. */
    val messages: Flow<Message> get() = events.filterIsInstance<ChatEvent.MessageCreated>().map { it.message }

    /** Sends a command if connected. Returns false when there's no open socket (fall back to REST). */
    suspend fun send(command: ChatCommand): Boolean {
        val session = activeSession.value ?: return false
        return try {
            session.send(Frame.Text(ApiJson.encodeToString(ChatCommand.serializer(), command)))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    suspend fun sendMessage(conversationId: String, body: String): Boolean = send(ChatCommand.Send(conversationId, body))

    suspend fun markRead(conversationId: String): Boolean = send(ChatCommand.MarkRead(conversationId))

    /** Skip the current backoff wait (e.g. app returned to the foreground or the network came back). */
    fun reconnectNow() {
        kick.trySend(Unit)
    }

    private suspend fun runConnectionLoop(out: ProducerScope<ChatEvent>) {
        var attempt = 0
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

                _status.value = if (attempt == 0) Status.Connecting else Status.Reconnecting
                var rejected = false
                try {
                    http.webSocket(urlString = server.wsUrl(ApiRoutes.WS), request = { parameter("token", token) }) {
                        activeSession.value = this
                        _status.value = Status.Connected
                        attempt = 0
                        val pinger = launch {
                            while (true) {
                                delay(PING_INTERVAL_MS)
                                send(Frame.Text(ApiJson.encodeToString(ChatCommand.serializer(), ChatCommand.Ping)))
                            }
                        }
                        try {
                            for (frame in incoming) {
                                if (frame !is Frame.Text) continue
                                val event = decode(frame.readText()) ?: continue
                                if (event !is ChatEvent.Pong) out.send(event)
                            }
                        } finally {
                            pinger.cancel()
                            activeSession.value = null
                        }
                        val reason = closeReason.await()
                        rejected = reason?.code == CloseReason.Codes.VIOLATED_POLICY.code
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // connect failed or dropped; retry below
                }
                activeSession.value = null

                if (rejected) {
                    try {
                        api.forceRefresh()
                    } catch (e: ApiError) {
                        // network trouble: backoff and retry
                    }
                }
                _status.value = Status.Reconnecting
                backoff(attempt++)
            }
        } finally {
            activeSession.value = null
            _status.value = Status.Idle
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

    private companion object {
        const val PING_INTERVAL_MS = 25_000L
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
