package com.campusmarket.app.feature.chat

import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.data.SessionState
import com.campusmarket.core.ChatEvent
import com.campusmarket.core.Conversation
import com.campusmarket.core.Limits
import com.campusmarket.core.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * App-wide unread total for the Inbox tab badge: how many conversations have messages I haven't read.
 * Koin single in [chatModule].
 *
 * ```
 * val unread by koinInject<UnreadConversations>().count.collectAsStateWithLifecycle()
 * // pass `unread` to the Inbox tab's badge; 0 means no badge
 * ```
 *
 * While [count] has a collector and someone is signed in it keeps itself current: the first page of
 * `GET /conversations` on start, after the live connection comes back and on [refresh]; live `MessageCreated` /
 * `ConversationRead` events in between (polling over REST while the socket is down). Collecting it keeps the chat
 * websocket open, like the chat screens do, and it stops 5 s after the last collector leaves (e.g. app in background).
 * The inbox and conversation screens also feed it what they know ([report], [onRead], [onConversationVisible]), so the
 * badge and the list always agree. Signing out resets it to 0.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnreadConversations(
    private val api: ApiClient,
    private val live: LiveChat,
    private val session: SessionManager,
    scope: CoroutineScope,
) {
    /**
     * Per conversation: unread messages, the newest activity this count reflects, and how far I've read on this device
     * (a stale copy, e.g. an inbox row from before I opened the chat, can't bring read messages back).
     */
    private data class Entry(val unread: Int, val activityAt: Long, val readThrough: Long = Long.MIN_VALUE)

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    private val refreshRequests = MutableStateFlow(0)

    @Volatile
    private var myId: String? = null

    /** The conversation on screen right now: its new messages are read as they arrive, so they don't count. */
    @Volatile
    private var openConversationId: String? = null

    /** Number of conversations with at least one unread message (0 when signed out or before the first sync). */
    val count: StateFlow<Int> = session.state
        .map { (it as? SessionState.SignedIn)?.user?.id }
        .distinctUntilChanged()
        .flatMapLatest { userId -> if (userId == null) signedOut() else track(userId) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Unread messages per conversation id, as far as this source knows (for tests and diagnostics). */
    val unreadByConversation: Map<String, Int> get() = entries.value.mapValues { it.value.unread }

    /** Re-sync from the server now (e.g. the app came back to the foreground). No-op while nobody collects [count]. */
    fun refresh() {
        refreshRequests.update { it + 1 }
    }

    /** Conversations the inbox just loaded or updated: their unread counts win unless we've seen newer activity. */
    fun report(conversations: List<Conversation>) {
        if (conversations.isEmpty()) return
        entries.update { current ->
            val next = current.toMutableMap()
            conversations.forEach { c ->
                val known = next[c.id]
                val activity = c.lastMessageAt ?: c.createdAt
                if (known == null || activity >= known.activityAt) {
                    val readThrough = known?.readThrough ?: Long.MIN_VALUE
                    val read = c.id == openConversationId || activity <= readThrough
                    next[c.id] = Entry(if (read) 0 else c.unreadCount, activity, readThrough)
                }
            }
            next
        }
    }

    /** I read [conversationId] (marked read on this device). */
    fun onRead(conversationId: String) {
        entries.update { current ->
            val e = current[conversationId] ?: return@update current
            current + (conversationId to e.copy(unread = 0, readThrough = e.activityAt))
        }
    }

    /** A conversation screen became visible or went away: while it's on screen its new messages don't count. */
    fun onConversationVisible(conversationId: String, visible: Boolean) {
        if (visible) {
            openConversationId = conversationId
            onRead(conversationId)
        } else if (openConversationId == conversationId) {
            openConversationId = null
        }
    }

    private fun signedOut(): Flow<Int> {
        myId = null
        entries.value = emptyMap()
        return flowOf(0)
    }

    private fun track(userId: String): Flow<Int> = channelFlow {
        if (myId != userId) entries.value = emptyMap()
        myId = userId
        launch { live.events.collect(::onEvent) }
        launch { refreshRequests.collectLatest { sync() } }
        launch { live.syncWhileDown(pollWhileConnecting = false, onReconnected = ::sync, poll = ::sync) }
        entries.map { map -> map.values.count { it.unread > 0 } }.distinctUntilChanged().collect { send(it) }
    }

    internal fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.MessageCreated -> onMessage(event.message)
            is ChatEvent.ConversationRead -> if (event.readerId == myId) onRead(event.conversationId)
            ChatEvent.Pong -> Unit
        }
    }

    private fun onMessage(m: Message) {
        entries.update { current ->
            val known = current[m.conversationId]
            // Already reflected (a duplicate event, or a sync / inbox report that saw it first).
            if (known != null && m.createdAt <= known.activityAt) return@update current
            // Sending marks the conversation read for the sender; the open conversation is read as messages arrive.
            val read = m.senderId == myId || m.conversationId == openConversationId
            val entry = if (read) Entry(0, m.createdAt, m.createdAt) else Entry((known?.unread ?: 0) + 1, m.createdAt, known?.readThrough ?: Long.MIN_VALUE)
            current + (m.conversationId to entry)
        }
    }

    private suspend fun sync() {
        try {
            report(api.conversations(limit = Limits.PAGE_MAX).items)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            // Keep the last known count; the next reconnect, poll or refresh catches up.
        }
    }
}
