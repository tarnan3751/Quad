package com.campusmarket.app.feature.chat

/**
 * What I typed but the server doesn't have yet, per conversation: the composer draft and messages that are still
 * sending or failed. Koin single in [chatModule], in memory only: it outlives the conversation screen (leave a chat with
 * an unsent message and it is still there, marked "Not sent", when you come back) but not the process.
 *
 * Entries are kept per signed-in user: the other person in a conversation may sign in on the same device.
 */
class ChatOutbox {
    data class Entry(val draft: String = "", val pending: List<PendingMessage> = emptyList())

    private val entries = HashMap<String, Entry>()

    fun load(userId: String, conversationId: String): Entry = entries[key(userId, conversationId)] ?: Entry()

    fun save(userId: String, conversationId: String, draft: String, pending: List<PendingMessage>) {
        val key = key(userId, conversationId)
        if (draft.isBlank() && pending.isEmpty()) entries.remove(key) else entries[key] = Entry(draft, pending)
    }

    private fun key(userId: String, conversationId: String) = "$userId/$conversationId"
}
