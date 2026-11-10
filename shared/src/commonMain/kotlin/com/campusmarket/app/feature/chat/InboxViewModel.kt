package com.campusmarket.app.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.data.ChatSocket
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.ui.state.Paginator
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.core.ChatEvent
import com.campusmarket.core.Conversation
import com.campusmarket.core.Limits
import com.campusmarket.core.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Inbox: conversations, most recent activity first.
 *
 * Live updates from [LiveChat]: a new message updates (or fetches) its conversation and moves it to the top, bumping
 * the unread badge when it's from the other person; a read event from me (any device) clears the badge. While the
 * socket is down the first page is re-synced over REST every [CHAT_POLL_INTERVAL_MS], and once more when it reconnects.
 */
class InboxViewModel(
    private val api: ApiClient,
    private val live: LiveChat,
    private val session: SessionManager,
    private val userMessages: UserMessages,
) : ViewModel() {

    val conversations = Paginator(viewModelScope, key = { it.id }) { cursor, limit -> api.conversations(cursor, limit) }

    val connection: StateFlow<ChatSocket.Status> = live.status

    private val myId: String? get() = session.currentUser?.id
    private val seenMessageIds = LinkedHashSet<String>()
    private val fetching = mutableSetOf<String>()
    private var resumedOnce = false

    init {
        conversations.onRefreshError = userMessages::showError
        conversations.refresh()
        viewModelScope.launch { live.events.collect(::onEvent) }
        viewModelScope.launch { fallbackSync() }
    }

    /** Called by the screen on every resume after the first: catch up on anything missed in the background. */
    fun onResume() {
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        live.reconnectNow()
        viewModelScope.launch { syncFirstPage() }
    }

    internal fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.MessageCreated -> onMessage(event.message)
            is ChatEvent.ConversationRead -> if (event.readerId == myId) clearUnread(event.conversationId)
            ChatEvent.Pong -> Unit
        }
    }

    private fun onMessage(message: Message) {
        if (!rememberMessage(message.id)) return
        val known = conversations.state.value.items.any { it.id == message.conversationId }
        if (!known) {
            fetchConversation(message.conversationId)
            return
        }
        val incoming = message.senderId != myId
        conversations.update { list ->
            list.map { c ->
                if (c.id != message.conversationId) return@map c
                val last = c.lastMessageAt
                // Already reflected (a REST sync saw it first): don't count it twice.
                if (last != null && message.createdAt <= last) return@map c
                c.copy(
                    lastMessagePreview = ChatFormat.preview(message.body),
                    lastMessageAt = message.createdAt,
                    unreadCount = if (incoming) c.unreadCount + 1 else c.unreadCount,
                )
            }.sortedByActivity()
        }
    }

    private fun clearUnread(conversationId: String) {
        conversations.update { list -> list.map { if (it.id == conversationId && it.unreadCount != 0) it.copy(unreadCount = 0) else it } }
    }

    /** A message in a conversation we don't have yet (new buyer, or beyond the loaded pages): fetch and insert it. */
    private fun fetchConversation(id: String) {
        if (!fetching.add(id)) return
        viewModelScope.launch {
            try {
                val conversation = api.conversation(id)
                conversations.update { list -> (list.filterNot { it.id == id } + conversation).sortedByActivity() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                // Not critical: the next sync / pull-to-refresh shows it.
            } finally {
                fetching.remove(id)
            }
        }
    }

    /** Re-fetches the first page and merges it in place (no spinner, keeps deeper pages). */
    internal suspend fun syncFirstPage() {
        if (!conversations.state.value.loaded) return
        try {
            val fresh = api.conversations(limit = Limits.PAGE_DEFAULT).items
            conversations.update { list -> mergeConversations(list, fresh) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            // Offline: keep what we have; the next attempt or a pull-to-refresh will catch up.
        }
    }

    private suspend fun fallbackSync() {
        var wasDown = false
        live.status.collectLatest { status ->
            when (status) {
                ChatSocket.Status.Connected -> if (wasDown) {
                    // Events sent while we were disconnected are gone: catch up once.
                    wasDown = false
                    syncFirstPage()
                }
                ChatSocket.Status.Connecting, ChatSocket.Status.Reconnecting -> {
                    if (status == ChatSocket.Status.Reconnecting) wasDown = true
                    while (true) {
                        delay(CHAT_POLL_INTERVAL_MS)
                        syncFirstPage()
                    }
                }
                ChatSocket.Status.Idle -> Unit
            }
        }
    }

    private fun rememberMessage(id: String): Boolean {
        if (!seenMessageIds.add(id)) return false
        if (seenMessageIds.size > SEEN_CAP) seenMessageIds.remove(seenMessageIds.first())
        return true
    }

    private companion object {
        const val SEEN_CAP = 500
    }
}

/**
 * Merges a freshly fetched first page into the loaded list. The server copy wins, except where a live event already
 * moved a conversation past it (the response was computed before that message): then the newer last message and
 * unread count are kept.
 */
internal fun mergeConversations(current: List<Conversation>, fresh: List<Conversation>): List<Conversation> {
    val local = current.associateBy { it.id }
    val freshIds = fresh.mapTo(HashSet()) { it.id }
    val merged = fresh.map { f ->
        val l = local[f.id]
        if (l != null && l.activityAt > f.activityAt) l.copy(listing = f.listing, otherUser = f.otherUser) else f
    }
    return (merged + current.filterNot { it.id in freshIds }).sortedByActivity()
}

internal val Conversation.activityAt: Long get() = lastMessageAt ?: createdAt

internal fun List<Conversation>.sortedByActivity(): List<Conversation> = sortedByDescending { it.activityAt }
