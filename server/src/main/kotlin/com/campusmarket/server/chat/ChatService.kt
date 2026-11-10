package com.campusmarket.server.chat

import com.campusmarket.core.ChatEvent
import com.campusmarket.core.Conversation
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Limits
import com.campusmarket.core.Message
import com.campusmarket.core.Page
import com.campusmarket.server.AppDeps
import com.campusmarket.server.KeysetCursor
import com.campusmarket.server.PageParams
import com.campusmarket.server.Sql
import com.campusmarket.server.UserPrincipal
import com.campusmarket.server.UserThrottle
import com.campusmarket.server.conflict
import com.campusmarket.server.forbidden
import com.campusmarket.server.invalidField
import com.campusmarket.server.isBlockedEitherWay
import com.campusmarket.server.keyset
import com.campusmarket.server.loadListingSummaries
import com.campusmarket.server.loadUsersPublic
import com.campusmarket.server.millis
import com.campusmarket.server.notFound
import com.campusmarket.server.page
import com.campusmarket.server.pageOfIds
import com.campusmarket.server.query
import com.campusmarket.server.queryOne
import com.campusmarket.server.toUuidOrNull
import com.campusmarket.server.update
import com.campusmarket.server.uuid
import com.campusmarket.server.withoutControlChars
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val PREVIEW_MAX = 120

/** Chat business rules. Every operation authorises the caller against the database; events are published only after commit. */
internal class ChatService(private val deps: AppDeps, private val hub: ChatHub) {
    private val db get() = deps.db
    private val config get() = deps.config

    private class Participation(val id: UUID, val listingId: UUID, val buyerId: UUID, val sellerId: UUID, val campus: String) {
        fun other(me: UUID) = if (me == buyerId) sellerId else buyerId
        val both get() = listOf(buyerId, sellerId)

        /** [user]'s read-mark column. */
        fun readColumn(user: UUID) = if (user == buyerId) "buyer_last_read_at" else "seller_last_read_at"
    }

    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    /** Loads the conversation if [user] participates in it and is on its campus; otherwise 404 (never reveals existence). */
    private fun Connection.requireParticipation(user: UserPrincipal, id: UUID): Participation =
        queryOne(
            """SELECT c.id, c.listing_id, c.buyer_id, c.seller_id, l.campus_domain
               FROM conversations c JOIN listings l ON l.id = c.listing_id
               WHERE c.id = ? AND (c.buyer_id = ? OR c.seller_id = ?)""",
            id, user.id, user.id,
        ) { Participation(it.uuid("id"), it.uuid("listing_id"), it.uuid("buyer_id"), it.uuid("seller_id"), it.getString("campus_domain")) }
            ?.takeIf { it.campus == user.campusDomain }
            ?: notFound("Conversation")

    private fun normalizeBody(raw: String, field: String): String {
        val body = raw.withoutControlChars().trim()
        if (body.isEmpty()) invalidField(field, "Message can't be empty")
        if (body.codePointCount(0, body.length) > Limits.MESSAGE_MAX) invalidField(field, "Message is too long (max ${Limits.MESSAGE_MAX} characters)")
        return body
    }

    private val clientIdPattern = Regex("[A-Za-z0-9_-]{1,${Limits.MESSAGE_CLIENT_ID_MAX}}")

    private fun normalizeClientId(raw: String?): String? {
        if (raw == null) return null
        if (!clientIdPattern.matches(raw)) {
            invalidField("clientId", "Use 1 to ${Limits.MESSAGE_CLIENT_ID_MAX} letters, digits, hyphens or underscores")
        }
        return raw
    }

    private fun ResultSet.message() = Message(
        uuid("id").toString(), uuid("conversation_id").toString(), uuid("sender_id").toString(), getString("body"),
        millis("created_at"), getString("client_id"),
    )

    /** The message [senderId] already sent with [clientId], if any. One sender never uses an id for two conversations. */
    private fun Connection.messageByClientId(p: Participation, senderId: UUID, clientId: String): Message? {
        val existing = queryOne(
            "SELECT id, conversation_id, sender_id, body, created_at, client_id FROM messages WHERE sender_id = ? AND client_id = ?",
            senderId, clientId,
        ) { it.message() } ?: return null
        if (existing.conversationId != p.id.toString()) invalidField("clientId", "This id was already used for another message")
        return existing
    }

    private fun preview(body: String): String {
        val flat = body.replace(Regex("\\s+"), " ")
        return if (flat.length <= PREVIEW_MAX) flat else flat.take(PREVIEW_MAX - 1).trimEnd() + "…"
    }

    /**
     * Inserts a message, enforcing block and availability rules, and updates the conversation summary + sender read mark.
     * Returns null when a concurrent send with the same [clientId] won the insert (it has committed by then).
     */
    private fun Connection.insertMessage(p: Participation, senderId: UUID, body: String, clientId: String? = null): Message? {
        val other = p.other(senderId)
        val (blocked, otherGone) = queryOne(
            """SELECT EXISTS (SELECT 1 FROM blocks WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)),
                      EXISTS (SELECT 1 FROM users WHERE id = ? AND deleted_at IS NOT NULL)""",
            senderId, other, other, senderId, other,
        ) { it.getBoolean(1) to it.getBoolean(2) }!!
        if (blocked) forbidden("You can't message this user", ErrorCodes.BLOCKED)
        if (otherGone) conflict("This user is no longer available")
        val id = UUID.randomUUID()
        val at = now()
        val inserted = update(
            """INSERT INTO messages (id, conversation_id, sender_id, body, created_at, client_id) VALUES (?, ?, ?, ?, ?, ?)
               ON CONFLICT (sender_id, client_id) WHERE client_id IS NOT NULL DO NOTHING""",
            id, p.id, senderId, body, at, clientId,
        )
        if (inserted == 0) return null
        // The preview follows the newest message (a concurrent send may have committed a later one); the sender has read it.
        val readCol = p.readColumn(senderId)
        update(
            """UPDATE conversations SET last_message_preview = CASE WHEN last_message_at IS NULL OR last_message_at <= ? THEN ? ELSE last_message_preview END,
                      last_message_at = GREATEST(last_message_at, ?), $readCol = GREATEST($readCol, ?)
               WHERE id = ?""",
            at, preview(body), at, at, p.id,
        )
        return Message(id.toString(), p.id.toString(), senderId.toString(), body, at.toEpochMilli(), clientId)
    }

    // ---------- operations ----------

    suspend fun start(user: UserPrincipal, listingIdRaw: String, initialMessage: String?): Conversation {
        val listingId = listingIdRaw.toUuidOrNull() ?: notFound("Listing")
        val body = initialMessage?.takeIf { it.isNotBlank() }?.let { normalizeBody(it, "initialMessage") }
        if (body != null) throttleMessage(user)
        val (conversation, message, participants) = db.tx { c ->
            val listing = c.queryOne(
                "SELECT seller_id, campus_domain, status FROM listings WHERE id = ?", listingId,
            ) { Triple(it.uuid("seller_id"), it.getString("campus_domain"), it.getString("status")) }
            if (listing == null || listing.second != user.campusDomain) notFound("Listing")
            val (sellerId, _, status) = listing
            val visible = status in setOf("ACTIVE", "RESERVED", "SOLD") || user.isModerator || sellerId == user.id
            val existingId = c.queryOne("SELECT id FROM conversations WHERE listing_id = ? AND buyer_id = ?", listingId, user.id) { it.uuid("id") }
            if (!visible && existingId == null) notFound("Listing")
            if (sellerId == user.id) invalidField("listingId", "You can't message your own listing")
            if (c.isBlockedEitherWay(user.id, sellerId)) forbidden("You can't message this user", ErrorCodes.BLOCKED)

            val convId = existingId ?: run {
                deps.throttle.acquire(UserThrottle.Action.CONVERSATION, user.id, "You've started a lot of conversations recently. Try again later.")
                val newId = UUID.randomUUID()
                c.update(
                    """INSERT INTO conversations (id, listing_id, buyer_id, seller_id, created_at) VALUES (?, ?, ?, ?, ?)
                       ON CONFLICT (listing_id, buyer_id) DO NOTHING""",
                    newId, listingId, user.id, sellerId, now(),
                )
                c.queryOne("SELECT id FROM conversations WHERE listing_id = ? AND buyer_id = ?", listingId, user.id) { it.uuid("id") }!!
            }
            val p = c.requireParticipation(user, convId)
            val msg = body?.let { c.insertMessage(p, user.id, it) }
            Triple(c.loadConversations(user, listOf(convId)).getValue(convId), msg, p.both)
        }
        if (message != null) hub.publish(participants, ChatEvent.MessageCreated(message))
        return conversation
    }

    suspend fun list(user: UserPrincipal, page: PageParams): Page<Conversation> = db.tx { c ->
        val sql = Sql("SELECT c.id, COALESCE(c.last_message_at, c.created_at) FROM conversations c WHERE (c.buyer_id = ? OR c.seller_id = ?)", user.id, user.id)
            .keysetPage("COALESCE(c.last_message_at, c.created_at)", "c.id", KeysetCursor.decode(page.cursor), page.limit)
        c.pageOfIds(sql, page.limit) { ids -> loadConversations(user, ids).let { byId -> ids.mapNotNull(byId::get) } }
    }

    suspend fun get(user: UserPrincipal, id: UUID): Conversation = db.tx { c ->
        c.requireParticipation(user, id)
        c.loadConversations(user, listOf(id)).getValue(id)
    }

    suspend fun messages(user: UserPrincipal, id: UUID, page: PageParams): Page<Message> = db.tx { c ->
        c.requireParticipation(user, id)
        val sql = Sql("SELECT id, created_at, conversation_id, sender_id, body, client_id FROM messages WHERE conversation_id = ?", id)
            .keysetPage("created_at", "id", KeysetCursor.decode(page.cursor), page.limit)
        val rows = c.page(sql, page.limit, { it.second }) { it.message() to it.keyset() }
        Page(rows.items.map { it.first }, rows.nextCursor)
    }

    /** REST and websocket sends share one budget. */
    private fun throttleMessage(user: UserPrincipal) =
        deps.throttle.acquire(UserThrottle.Action.MESSAGE, user.id, "You're sending messages too fast. Wait a moment and try again.")

    /**
     * Sends a message. With a [rawClientId] the send is idempotent per (sender, client id): a repeat (a retry after a
     * lost response, over REST or the websocket) returns the stored message and neither creates nor publishes another.
     */
    suspend fun send(user: UserPrincipal, conversationId: UUID, rawBody: String, rawClientId: String? = null): Message {
        val body = normalizeBody(rawBody, "body")
        val clientId = normalizeClientId(rawClientId)
        val (message, participants) = db.tx { c ->
            val p = c.requireParticipation(user, conversationId)
            // A repeat costs nothing from the message budget and still succeeds if the two have blocked each other since.
            val existing = clientId?.let { c.messageByClientId(p, user.id, it) }
            if (existing != null) return@tx existing to null
            throttleMessage(user)
            val created = c.insertMessage(p, user.id, body, clientId)
            if (created != null) created to p.both else c.messageByClientId(p, user.id, clientId!!)!! to null
        }
        if (participants != null) hub.publish(participants, ChatEvent.MessageCreated(message))
        return message
    }

    suspend fun markRead(user: UserPrincipal, conversationId: UUID) {
        val at = now()
        val participants = db.tx { c ->
            val p = c.requireParticipation(user, conversationId)
            val col = p.readColumn(user.id)
            c.update("UPDATE conversations SET $col = GREATEST($col, ?) WHERE id = ?", at, conversationId)
            p.both
        }
        hub.publish(participants, ChatEvent.ConversationRead(conversationId.toString(), user.id.toString(), at.toEpochMilli()))
    }

    // ---------- rendering ----------

    /** Renders conversations for [user] (ids that aren't theirs are simply absent from the result). */
    private fun Connection.loadConversations(user: UserPrincipal, ids: List<UUID>): Map<UUID, Conversation> {
        if (ids.isEmpty()) return emptyMap()
        class Row(val id: UUID, val listingId: UUID, val otherId: UUID, val preview: String?, val lastAt: Long?, val unread: Int, val createdAt: Long)
        val rows = query(
            """SELECT x.*, (SELECT count(*) FROM messages m
                             WHERE m.conversation_id = x.id AND m.sender_id <> ?
                               AND m.created_at > COALESCE(x.my_read, '-infinity'::timestamptz)) AS unread
               FROM (SELECT c.id, c.listing_id, c.last_message_at, c.last_message_preview, c.created_at,
                            CASE WHEN c.buyer_id = ? THEN c.seller_id ELSE c.buyer_id END AS other_id,
                            CASE WHEN c.buyer_id = ? THEN c.buyer_last_read_at ELSE c.seller_last_read_at END AS my_read
                     FROM conversations c
                     WHERE c.id = ANY(?) AND (c.buyer_id = ? OR c.seller_id = ?)) x""",
            user.id, user.id, user.id, ids, user.id, user.id,
        ) {
            Row(
                it.uuid("id"), it.uuid("listing_id"), it.uuid("other_id"), it.getString("last_message_preview"),
                it.getTimestamp("last_message_at")?.time, it.getInt("unread"), it.millis("created_at"),
            )
        }
        val listings = loadListingSummaries(config, rows.map { it.listingId }, user.id)
        val users = loadUsersPublic(config, rows.map { it.otherId }, user.id)
        return rows.mapNotNull { r ->
            val listing = listings[r.listingId] ?: return@mapNotNull null
            val other = users[r.otherId] ?: return@mapNotNull null
            r.id to Conversation(r.id.toString(), listing, other, r.preview, r.lastAt, r.unread, r.createdAt)
        }.toMap()
    }
}
