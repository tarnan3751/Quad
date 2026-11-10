package com.campusmarket.app.feature.chat

import com.campusmarket.app.data.SecretStorage
import com.campusmarket.app.data.SignOutListener
import com.campusmarket.core.ApiJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * What I typed but the server doesn't have yet, per conversation: the composer draft and messages that are still
 * sending or failed. Koin single in [chatModule]. It outlives the conversation screen (leave a chat with an unsent
 * message and it is still there, marked "Not sent", when you come back; the inbox flags the conversation) and, stored
 * in [secrets], the process.
 *
 * - One user's outbox is kept at a time: the other person in a conversation may sign in on the same device, and must
 *   never see it. Saving for another user replaces it; signing out or deleting the account clears it.
 * - Message text is private, so it is stored like the session (Keystore-encrypted / Keychain), never in plain
 *   preferences. Writes are batched on [scope] a moment after the last change (typing a draft changes it per key);
 *   without a scope they happen at once.
 * - A message that was still sending when the process died is restored as "Not sent" and unconfirmed: the server may
 *   have it, which the conversation settles by the message's client id when it loads its history.
 */
class ChatOutbox(
    private val secrets: SecretStorage? = null,
    private val scope: CoroutineScope? = null,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) : SignOutListener {
    @Serializable
    data class Entry(val draft: String = "", val pending: List<PendingMessage> = emptyList()) {
        /** Messages that failed (or were cut off) and wait for Retry or Delete. */
        val notSent: List<PendingMessage> get() = pending.filter { it.delivery == Delivery.Failed }
    }

    @Serializable
    private data class Stored(val userId: String, val conversations: Map<String, Entry> = emptyMap())

    private val state = MutableStateFlow(restore())
    private val writes = Channel<Unit>(Channel.CONFLATED)

    init {
        scope?.launch {
            for (request in writes) {
                delay(WRITE_DELAY_MS)
                persist()
            }
        }
    }

    fun load(userId: String, conversationId: String): Entry = of(userId)[conversationId] ?: Entry()

    fun save(userId: String, conversationId: String, draft: String, pending: List<PendingMessage>) {
        val current = of(userId)
        val entry = Entry(draft, pending)
        val next = if (draft.isBlank() && pending.isEmpty()) current - conversationId else current + (conversationId to entry)
        if (next == current && state.value?.userId == userId) return
        state.value = if (next.isEmpty()) null else Stored(userId, next)
        if (scope == null) persist() else writes.trySend(Unit)
    }

    /** [userId]'s entries by conversation id, for the inbox. */
    fun entries(userId: String): Flow<Map<String, Entry>> = state.map { it.conversationsOf(userId) }.distinctUntilChanged()

    fun clear() {
        state.value = null
        persist()
        // A batched write may have been reading the old state just now: have it write once more.
        writes.trySend(Unit)
    }

    override fun onSignedOut() = clear()

    private fun of(userId: String) = state.value.conversationsOf(userId)

    private fun Stored?.conversationsOf(userId: String): Map<String, Entry> =
        if (this != null && this.userId == userId) conversations else emptyMap()

    private fun restore(): Stored? {
        val secrets = secrets ?: return null
        return try {
            val stored = ApiJson.decodeFromString(Stored.serializer(), secrets.read() ?: return null)
            stored.copy(conversations = stored.conversations.mapValues { (_, e) -> e.copy(pending = e.pending.map { it.interrupted() }) })
        } catch (e: Exception) {
            onError("stored outbox unreadable; clearing", e)
            runCatching { secrets.delete() }
            null
        }
    }

    private fun persist() {
        val secrets = secrets ?: return
        try {
            val stored = state.value
            if (stored == null) secrets.delete() else secrets.write(ApiJson.encodeToString(Stored.serializer(), stored))
        } catch (e: Exception) {
            onError("could not persist the outbox", e)
        }
    }

    private companion object {
        const val WRITE_DELAY_MS = 400L
    }
}

/** A send that was cut off (the chat was left, or the process died): "Not sent", though the server may have it. */
internal fun PendingMessage.interrupted(): PendingMessage =
    if (delivery == Delivery.Sending) copy(delivery = Delivery.Failed, error = "Not sent", unconfirmed = true) else this
