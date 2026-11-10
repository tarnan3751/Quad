package com.campusmarket.app.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.data.ChatSocket
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.ui.Validate
import com.campusmarket.app.ui.state.EventQueue
import com.campusmarket.app.ui.state.Paginator
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.app.ui.state.load
import com.campusmarket.app.ui.state.updateContent
import com.campusmarket.app.ui.state.valueOrNull
import com.campusmarket.core.ChatEvent
import com.campusmarket.core.Conversation
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Limits
import com.campusmarket.core.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

// ---------------------------------------------------------------------------------------------------- state

/** Why the composer is disabled. */
enum class SendRestriction {
    /** I blocked them (offer Unblock). */
    BlockedByMe,

    /** They blocked me (server said 403 `BLOCKED`). */
    Blocked,

    /** Their account was deleted (server said 409). */
    UserUnavailable,
}

enum class Delivery { Sent, Sending, Failed }

/** A message I sent that the server hasn't confirmed yet (or rejected). */
data class PendingMessage(
    val localId: String,
    val body: String,
    val createdAt: Long,
    val delivery: Delivery = Delivery.Sending,
    val error: String? = null,
)

data class ConversationUiState(
    val draft: String = "",
    val draftError: String? = null,
    /** Oldest first. */
    val pending: List<PendingMessage> = emptyList(),
    /** Latest time the other person is known to have read the conversation (live read events, their own replies). */
    val otherReadAt: Long? = null,
    val restriction: SendRestriction? = null,
)

/** One row of the message list, **newest first** (the list is drawn with `reverseLayout`). */
sealed interface ChatRow {
    val key: String

    data class Bubble(
        override val key: String,
        /** Server id; null while pending. */
        val messageId: String?,
        val localId: String?,
        val body: String,
        val createdAt: Long,
        val mine: Boolean,
        val delivery: Delivery,
        val error: String? = null,
        /** The message drawn above (older) is from the same person a moment earlier: tighter spacing. */
        val groupedWithOlder: Boolean = false,
        /** "Sent" / "Seen" under my newest message. */
        val receipt: Receipt? = null,
    ) : ChatRow

    /** Separator drawn above the first message of a day. */
    data class DayHeader(override val key: String, val dayOf: Long) : ChatRow
}

enum class Receipt { Sent, Seen }

// ---------------------------------------------------------------------------------------------------- conversation

/**
 * One conversation.
 *
 * - History: [history] pages `GET /conversations/{id}/messages` (newest first) backwards in time.
 * - Live: `MessageCreated` events for this conversation are merged by id (the server also echoes my own REST sends),
 *   `ConversationRead` from the other person drives the "Seen" receipt.
 * - Sending: REST with an optimistic bubble; failures stay in the list as "Not sent" with retry / discard.
 *   `BLOCKED` and deleted-user errors disable the composer with an explanation ([ConversationUiState.restriction]).
 * - Read: marked read when the screen becomes visible and on every new incoming message while visible.
 * - Fallback: while the socket is down the newest page is re-fetched every [CHAT_POLL_INTERVAL_MS], and once on reconnect.
 */
class ConversationViewModel(
    route: ConversationRoute,
    private val api: ApiClient,
    private val live: LiveChat,
    private val session: SessionManager,
    private val userMessages: UserMessages,
    private val unread: UnreadConversations? = null,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {

    val conversationId: String = route.conversationId
    val myId: String = session.currentUser?.id.orEmpty()

    private val _conversation = MutableStateFlow<UiState<Conversation>>(UiState.Loading)
    val conversation: StateFlow<UiState<Conversation>> = _conversation.asStateFlow()

    val history = Paginator(viewModelScope, pageSize = PAGE_SIZE, key = { it.id }) { cursor, limit ->
        api.messages(conversationId, cursor, limit)
    }

    private val _state = MutableStateFlow(ConversationUiState())
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    val connection: StateFlow<ChatSocket.Status> = live.status

    /** Everything to draw, newest first. */
    val rows: StateFlow<List<ChatRow>> = combine(history.state, _state) { paged, s ->
        buildChatRows(paged.items, paged.endReached, s, myId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Messages that arrived live or from my sends, re-merged after a (re)load replaces the list. */
    private val liveMessages = LinkedHashMap<String, Message>()
    private var localCounter = 0
    private var visible = false
    private var lastIncomingAt = 0L
    private var markedUpTo = NEVER
    private var markJob: Job? = null
    private var resumedOnce = false

    init {
        loadConversation()
        history.refresh()
        viewModelScope.launch { live.events.collect(::onEvent) }
        viewModelScope.launch { fallbackSync() }
        viewModelScope.launch {
            history.state.map { it.loaded }.distinctUntilChanged().collect { loaded -> if (loaded) mergeLiveMessages() }
        }
    }

    fun loadConversation() {
        load(_conversation) {
            api.conversation(conversationId).also { c ->
                if (c.otherUser.blockedByMe) _state.update { it.copy(restriction = SendRestriction.BlockedByMe) }
            }
        }
    }

    fun retryHistory() = history.retry()

    fun loadOlder() = history.loadMore()

    // ------------------------------------------------------------------------------------------------ visibility

    /** The screen is resumed (true) or paused (false). Resuming marks read and catches up. */
    fun setVisible(isVisible: Boolean) {
        visible = isVisible
        unread?.onConversationVisible(conversationId, isVisible)
        if (!isVisible) return
        if (resumedOnce) {
            live.reconnectNow()
            viewModelScope.launch { syncNewest() }
        }
        resumedOnce = true
        maybeMarkRead()
    }

    private fun maybeMarkRead() {
        if (!visible) return
        if (markedUpTo != NEVER && lastIncomingAt <= markedUpTo) return
        markedUpTo = lastIncomingAt
        markJob?.cancel()
        markJob = viewModelScope.launch {
            delay(MARK_READ_DEBOUNCE_MS)
            try {
                api.markConversationRead(conversationId)
                unread?.onRead(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                markedUpTo = NEVER // try again on the next trigger
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ composer

    fun onDraft(text: String) {
        _state.update { it.copy(draft = text.take(Limits.MESSAGE_MAX), draftError = null) }
    }

    fun send() {
        val s = _state.value
        if (s.restriction != null) return
        val body = s.draft.trim()
        if (body.isEmpty()) return
        Validate.message(body)?.let { error ->
            _state.update { it.copy(draftError = error) }
            return
        }
        val pending = PendingMessage(localId = "local-${localCounter++}", body = body, createdAt = nowMillis())
        _state.update { it.copy(draft = "", draftError = null, pending = it.pending + pending) }
        deliver(pending)
    }

    fun retry(localId: String) {
        if (_state.value.restriction != null) return
        val p = _state.value.pending.firstOrNull { it.localId == localId && it.delivery == Delivery.Failed } ?: return
        val again = p.copy(delivery = Delivery.Sending, error = null)
        updatePending(localId) { again }
        deliver(again)
    }

    fun discard(localId: String) {
        _state.update { s -> s.copy(pending = s.pending.filterNot { it.localId == localId }) }
    }

    private fun deliver(p: PendingMessage) {
        viewModelScope.launch {
            try {
                val sent = api.sendMessage(conversationId, p.body)
                discard(p.localId)
                addMessages(listOf(sent))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                val restriction = when {
                    e.code == ErrorCodes.BLOCKED -> SendRestriction.Blocked
                    e.status == 409 -> SendRestriction.UserUnavailable
                    else -> null
                }
                val reason = when {
                    restriction != null -> "Not delivered"
                    e.isNetwork -> "Not sent. Check your connection."
                    else -> e.field("body") ?: e.message
                }
                updatePending(p.localId) { it.copy(delivery = Delivery.Failed, error = reason) }
                if (restriction != null) {
                    _state.update { if (it.restriction == SendRestriction.BlockedByMe) it else it.copy(restriction = restriction) }
                }
            }
        }
    }

    private fun updatePending(localId: String, transform: (PendingMessage) -> PendingMessage) {
        _state.update { s -> s.copy(pending = s.pending.map { if (it.localId == localId) transform(it) else it }) }
    }

    // ------------------------------------------------------------------------------------------------ moderation

    /** Result of `BlockUserDialog` (which already confirms the change with a snackbar, so none here). */
    fun onBlockChanged(blocked: Boolean) {
        _conversation.updateContent { it.copy(otherUser = it.otherUser.copy(blockedByMe = blocked)) }
        _state.update { it.copy(restriction = if (blocked) SendRestriction.BlockedByMe else null) }
    }

    fun onReported() = userMessages.show("Thanks for reporting. Our moderators will take a look.")

    // ------------------------------------------------------------------------------------------------ live

    internal fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.MessageCreated -> if (event.message.conversationId == conversationId) onLiveMessage(event.message)
            is ChatEvent.ConversationRead -> if (event.conversationId == conversationId && event.readerId != myId) {
                noteOtherRead(event.readAt)
            }
            ChatEvent.Pong -> Unit
        }
    }

    private fun onLiveMessage(message: Message) {
        if (message.senderId == myId) {
            // Echo of my own REST send (often before the REST response): retire the matching optimistic bubble.
            _state.update { s ->
                val match = s.pending.firstOrNull { it.delivery == Delivery.Sending && it.body == message.body }
                if (match == null) s else s.copy(pending = s.pending - match)
            }
        }
        addMessages(listOf(message))
    }

    /** Merges server messages (de-duplicated by id) and reacts to new incoming ones. */
    private fun addMessages(messages: List<Message>) {
        if (messages.isEmpty()) return
        messages.forEach { rememberLive(it) }
        history.update { current -> mergeNewestFirst(current, messages) }
        val incoming = messages.filter { it.senderId != myId }
        val newestIncoming = incoming.maxOfOrNull { it.createdAt }
        if (newestIncoming != null) {
            // Sending a message marks the conversation read for the sender, so a reply means they've seen what came before.
            noteOtherRead(newestIncoming)
            if (newestIncoming > lastIncomingAt) {
                lastIncomingAt = newestIncoming
                maybeMarkRead()
            }
        }
    }

    private fun noteOtherRead(at: Long) {
        _state.update { s -> if ((s.otherReadAt ?: Long.MIN_VALUE) >= at) s else s.copy(otherReadAt = at) }
    }

    private fun rememberLive(message: Message) {
        liveMessages[message.id] = message
        if (liveMessages.size > LIVE_CAP) liveMessages.remove(liveMessages.keys.first())
    }

    private fun mergeLiveMessages() {
        if (liveMessages.isEmpty()) return
        history.update { current -> mergeNewestFirst(current, liveMessages.values) }
    }

    /** Fetches the newest page and merges it (catch-up after background / socket outage). */
    internal suspend fun syncNewest() {
        if (!history.state.value.loaded) return
        try {
            val page = api.messages(conversationId, limit = PAGE_SIZE)
            val known = history.state.value.items.mapTo(HashSet()) { it.id }
            addMessages(page.items.filter { it.id !in known })
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            // Offline: the next poll / resume retries.
        }
    }

    private suspend fun fallbackSync() {
        var wasDown = false
        live.status.collectLatest { status ->
            when (status) {
                ChatSocket.Status.Connected -> if (wasDown) {
                    wasDown = false
                    syncNewest()
                }
                ChatSocket.Status.Connecting, ChatSocket.Status.Reconnecting -> {
                    if (status == ChatSocket.Status.Reconnecting) wasDown = true
                    while (true) {
                        delay(CHAT_POLL_INTERVAL_MS)
                        syncNewest()
                    }
                }
                ChatSocket.Status.Idle -> Unit
            }
        }
    }

    /** The other participant (null until the conversation has loaded). */
    val otherUser get() = _conversation.value.valueOrNull?.otherUser

    private companion object {
        const val PAGE_SIZE = 30
        const val NEVER = Long.MIN_VALUE
        const val MARK_READ_DEBOUNCE_MS = 250L
        const val LIVE_CAP = 200
    }
}

// ---------------------------------------------------------------------------------------------------- pure helpers

/** Merges [incoming] into a newest-first list, dropping ids already present. Order: createdAt desc, id desc (server order). */
internal fun mergeNewestFirst(current: List<Message>, incoming: Collection<Message>): List<Message> {
    if (incoming.isEmpty()) return current
    val ids = current.mapTo(HashSet()) { it.id }
    val fresh = incoming.filter { ids.add(it.id) }
    if (fresh.isEmpty()) return current
    return (current + fresh).sortedWith(compareByDescending<Message> { it.createdAt }.thenByDescending { it.id })
}

private const val GROUP_WINDOW_MS = 5 * 60_000L

/**
 * Builds the rows to draw, newest first: pending bubbles (newest at the bottom), then server messages, with a day
 * separator above the first message of each day (above the oldest loaded one only once we know it's the very first).
 * My newest server message gets a "Sent" / "Seen" receipt.
 */
internal fun buildChatRows(
    history: List<Message>,
    endReached: Boolean,
    state: ConversationUiState,
    myId: String,
    sameDay: (Long, Long) -> Boolean = { a, b -> ChatFormat.sameDay(a, b) },
): List<ChatRow> {
    val bubbles = ArrayList<ChatRow.Bubble>(history.size + state.pending.size)
    state.pending.asReversed().forEach { p ->
        bubbles += ChatRow.Bubble(
            key = p.localId,
            messageId = null,
            localId = p.localId,
            body = p.body,
            createdAt = p.createdAt,
            mine = true,
            delivery = p.delivery,
            error = p.error,
        )
    }
    val newest = history.firstOrNull()
    history.forEach { m ->
        val mine = m.senderId == myId
        val receipt = if (m === newest && mine) {
            if ((state.otherReadAt ?: Long.MIN_VALUE) >= m.createdAt) Receipt.Seen else Receipt.Sent
        } else {
            null
        }
        bubbles += ChatRow.Bubble(
            key = m.id,
            messageId = m.id,
            localId = null,
            body = m.body,
            createdAt = m.createdAt,
            mine = mine,
            delivery = Delivery.Sent,
            receipt = receipt,
        )
    }

    val rows = ArrayList<ChatRow>(bubbles.size + 8)
    bubbles.forEachIndexed { i, bubble ->
        val older = bubbles.getOrNull(i + 1)
        val grouped = older != null && older.mine == bubble.mine && sameDay(older.createdAt, bubble.createdAt) &&
            bubble.createdAt - older.createdAt in 0..GROUP_WINDOW_MS
        rows += if (grouped) bubble.copy(groupedWithOlder = true) else bubble
        val dayStarts = if (older == null) endReached else !sameDay(older.createdAt, bubble.createdAt)
        if (dayStarts) rows += ChatRow.DayHeader(key = "day-${bubble.key}", dayOf = bubble.createdAt)
    }
    return rows
}

// ---------------------------------------------------------------------------------------------------- message seller

/** Why "Message seller" can't open a conversation. */
data class StartChatProblem(val title: String, val message: String, val canRetry: Boolean)

/**
 * Entry from a listing's "Message seller": `POST /conversations` (idempotent per listing + buyer), then the screen
 * replaces itself with [ConversationRoute].
 */
class MessageSellerViewModel(private val route: MessageSellerRoute, private val api: ApiClient) : ViewModel() {
    private val _problem = MutableStateFlow<StartChatProblem?>(null)
    val problem: StateFlow<StartChatProblem?> = _problem.asStateFlow()

    /** Emits the conversation id to open. */
    val events = EventQueue<String>()

    init {
        start()
    }

    fun start() {
        _problem.value = null
        viewModelScope.launch {
            try {
                events.send(api.startConversation(route.listingId).id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                _problem.value = startChatProblem(e)
            }
        }
    }
}

internal fun startChatProblem(e: ApiError): StartChatProblem = when {
    e.code == ErrorCodes.BLOCKED ->
        StartChatProblem("You can't message this seller", "One of you has blocked the other, so you can't chat or trade.", canRetry = false)
    e.isValidation && e.field("listingId") != null ->
        StartChatProblem("This is your listing", "Buyers will message you about it. Your conversations are in the Inbox.", canRetry = false)
    e.isNotFound ->
        StartChatProblem("Listing not available", "It may have been removed by the seller or a moderator.", canRetry = false)
    e.isNetwork -> StartChatProblem("You're offline", e.message, canRetry = true)
    else -> StartChatProblem("Couldn't start the chat", e.message, canRetry = true)
}
