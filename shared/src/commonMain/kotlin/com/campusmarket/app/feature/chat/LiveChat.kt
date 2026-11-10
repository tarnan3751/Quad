package com.campusmarket.app.feature.chat

import com.campusmarket.app.data.ChatSocket
import com.campusmarket.core.ChatEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * What the chat screens need from the live connection. In the app it's [SocketLiveChat] over the shared
 * [ChatSocket]; tests substitute a fake so they can push events and flip the connection status.
 */
interface LiveChat {
    /** Hot server events; collecting keeps the websocket open (see [ChatSocket.events]). */
    val events: Flow<ChatEvent>
    val status: StateFlow<ChatSocket.Status>

    /** Skip the socket's backoff wait (screen resumed). */
    fun reconnectNow()
}

class SocketLiveChat(private val socket: ChatSocket) : LiveChat {
    override val events: Flow<ChatEvent> get() = socket.events
    override val status: StateFlow<ChatSocket.Status> get() = socket.status
    override fun reconnectNow() = socket.reconnectNow()
}

/** How often chat screens re-fetch over REST while the websocket is down. */
internal const val CHAT_POLL_INTERVAL_MS = 15_000L

/**
 * Keeps a chat screen current while the websocket is down: [poll] every [CHAT_POLL_INTERVAL_MS] while it reconnects
 * (and while it first connects, with [pollWhileConnecting]), then [onReconnected] once it's back, since events sent
 * during the outage are gone. Runs until cancelled.
 */
internal suspend fun LiveChat.syncWhileDown(pollWhileConnecting: Boolean, onReconnected: suspend () -> Unit, poll: suspend () -> Unit) {
    var wasDown = false
    status.collectLatest { status ->
        when {
            status == ChatSocket.Status.Connected -> if (wasDown) {
                wasDown = false
                onReconnected()
            }
            status == ChatSocket.Status.Reconnecting || (pollWhileConnecting && status == ChatSocket.Status.Connecting) -> {
                if (status == ChatSocket.Status.Reconnecting) wasDown = true
                while (true) {
                    delay(CHAT_POLL_INTERVAL_MS)
                    poll()
                }
            }
        }
    }
}
