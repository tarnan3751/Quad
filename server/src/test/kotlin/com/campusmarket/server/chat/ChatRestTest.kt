package com.campusmarket.server.chat

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.Conversation
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.Limits
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.Message
import com.campusmarket.core.OkResponse
import com.campusmarket.core.Page
import com.campusmarket.core.SendMessageRequest
import com.campusmarket.core.StartConversationRequest
import com.campusmarket.server.ApiTestScope
import com.campusmarket.server.TestEnv
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import com.campusmarket.server.json
import com.campusmarket.server.update
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal suspend fun ApiTestScope.startConv(user: TestEnv.TestUser, listingId: Any, initial: String? = null): HttpResponse =
    client.post(ApiRoutes.CONVERSATIONS) { auth(user); json(StartConversationRequest(listingId.toString(), initial)) }

internal suspend fun ApiTestScope.sendMsg(user: TestEnv.TestUser, convId: String, text: String): HttpResponse =
    client.post(ApiRoutes.conversationMessages(convId)) { auth(user); json(SendMessageRequest(text)) }

internal suspend fun HttpResponse.error(expected: HttpStatusCode): ErrorResponse {
    assertEquals(expected, status)
    return body()
}

class ChatRestTest {
    @Test
    fun startConversationIsIdempotentAndRendersCounterpart() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val listing = env.listing(seller, title = "Desk lamp")

        val first = startConv(buyer, listing)
        assertEquals(HttpStatusCode.OK, first.status)
        val conv: Conversation = first.body()
        assertEquals("Desk lamp", conv.listing.title)
        assertEquals("sally", conv.otherUser.displayName)
        assertEquals(seller.id.toString(), conv.otherUser.id)
        assertNull(conv.lastMessagePreview)
        assertEquals(0, conv.unreadCount)

        val second: Conversation = startConv(buyer, listing).body()
        assertEquals(conv.id, second.id)

        // seller sees the same conversation with the buyer as counterpart
        val asSeller: Conversation = client.get(ApiRoutes.conversation(conv.id)) { auth(seller) }.body()
        assertEquals("bob", asSeller.otherUser.displayName)
        val count = env.db.tx { c -> c.prepareStatement("SELECT count(*) FROM conversations").use { ps -> ps.executeQuery().use { it.next(); it.getInt(1) } } }
        assertEquals(1, count)
    }

    @Test
    fun initialMessageIsSentAndUpdatesPreview() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val listing = env.listing(seller)

        val conv: Conversation = startConv(buyer, listing, "  Is this still available?  ").body()
        assertEquals("Is this still available?", conv.lastMessagePreview)
        assertNotNull(conv.lastMessageAt)
        assertEquals(0, conv.unreadCount) // sender's own message isn't unread

        val msgs: Page<Message> = client.get(ApiRoutes.conversationMessages(conv.id)) { auth(seller) }.body()
        assertEquals(listOf("Is this still available?"), msgs.items.map { it.body })
        assertEquals(buyer.id.toString(), msgs.items.single().senderId)
        val asSeller: Conversation = client.get(ApiRoutes.conversation(conv.id)) { auth(seller) }.body()
        assertEquals(1, asSeller.unreadCount)

        // blank initial message = no message; repeat start with a message appends another one
        val again: Conversation = startConv(buyer, listing, "   ").body()
        assertEquals(conv.id, again.id)
        startConv(buyer, listing, "second")
        val after: Page<Message> = client.get(ApiRoutes.conversationMessages(conv.id)) { auth(buyer) }.body()
        assertEquals(listOf("second", "Is this still available?"), after.items.map { it.body })
    }

    @Test
    fun startValidationRules() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val listing = env.listing(seller)

        assertEquals(ErrorCodes.VALIDATION, startConv(seller, listing).error(HttpStatusCode.BadRequest).code)
        assertEquals(ErrorCodes.NOT_FOUND, startConv(buyer, UUID.randomUUID()).error(HttpStatusCode.NotFound).code)
        assertEquals(ErrorCodes.NOT_FOUND, startConv(buyer, "not-a-uuid").error(HttpStatusCode.NotFound).code)
        val long = startConv(buyer, listing, "x".repeat(Limits.MESSAGE_MAX + 1)).error(HttpStatusCode.BadRequest)
        assertTrue("initialMessage" in long.fields)
        // nothing was created by the failed attempts
        val list: Page<Conversation> = client.get(ApiRoutes.CONVERSATIONS) { auth(buyer) }.body()
        assertTrue(list.items.isEmpty())
        // exactly MESSAGE_MAX is fine
        assertEquals(HttpStatusCode.OK, startConv(buyer, listing, "x".repeat(Limits.MESSAGE_MAX)).status)
    }

    @Test
    fun malformedBodyIsRejectedAndAuthIsRequired() = apiTest { env ->
        val buyer = env.user("bob")
        val res = client.post(ApiRoutes.CONVERSATIONS) { auth(buyer); contentType(ContentType.Application.Json); setBody("{nope") }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(ApiRoutes.CONVERSATIONS).status)
        assertEquals(HttpStatusCode.Unauthorized, client.post(ApiRoutes.conversationRead(UUID.randomUUID().toString())).status)
    }

    @Test
    fun listingVisibilityAndCampusScopingOnStart() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val otherCampus = env.user("carl", domain = "other.edu")
        val moderator = env.user("mod", role = com.campusmarket.core.UserRole.MODERATOR)

        val removed = env.listing(seller, status = ListingStatus.REMOVED)
        val deleted = env.listing(seller, status = ListingStatus.DELETED)
        val sold = env.listing(seller, status = ListingStatus.SOLD)
        val reserved = env.listing(seller, status = ListingStatus.RESERVED)
        val active = env.listing(seller)

        assertEquals(HttpStatusCode.NotFound, startConv(buyer, removed).status)
        assertEquals(HttpStatusCode.NotFound, startConv(buyer, deleted).status)
        assertEquals(HttpStatusCode.OK, startConv(buyer, sold).status)
        assertEquals(HttpStatusCode.OK, startConv(buyer, reserved).status)
        assertEquals(HttpStatusCode.NotFound, startConv(otherCampus, active).status)
        assertEquals(HttpStatusCode.OK, startConv(moderator, removed).status)
    }

    @Test
    fun existingConversationSurvivesListingRemoval() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val listing = env.listing(seller)
        val conv: Conversation = startConv(buyer, listing).body()
        env.db.tx { c -> c.update("UPDATE listings SET status = 'REMOVED' WHERE id = ?", UUID.fromString(listing.toString())) }
        val again: Conversation = startConv(buyer, listing).body()
        assertEquals(conv.id, again.id)
        assertEquals(ListingStatus.REMOVED, again.listing.status)
    }

    @Test
    fun participantsOnlyAndCampusScoping() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val stranger = env.user("eve")
        val conv: Conversation = startConv(buyer, env.listing(seller), "hi").body()

        for (u in listOf(stranger)) {
            assertEquals(HttpStatusCode.NotFound, client.get(ApiRoutes.conversation(conv.id)) { auth(u) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get(ApiRoutes.conversationMessages(conv.id)) { auth(u) }.status)
            assertEquals(HttpStatusCode.NotFound, sendMsg(u, conv.id, "hello").status)
            assertEquals(HttpStatusCode.NotFound, client.post(ApiRoutes.conversationRead(conv.id)) { auth(u) }.status)
        }
        assertEquals(HttpStatusCode.NotFound, client.get(ApiRoutes.conversation("garbage")) { auth(buyer) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get(ApiRoutes.conversation(UUID.randomUUID().toString())) { auth(buyer) }.status)
        val strangerList: Page<Conversation> = client.get(ApiRoutes.CONVERSATIONS) { auth(stranger) }.body()
        assertTrue(strangerList.items.isEmpty())

        // a participant whose campus no longer matches the listing's campus is locked out too
        env.db.tx { c -> c.update("UPDATE users SET campus_domain = 'moved.edu' WHERE id = ?", buyer.id) }
        // (token still authenticates; principal campus is re-read from the database)
        assertEquals(HttpStatusCode.NotFound, client.get(ApiRoutes.conversation(conv.id)) { auth(buyer) }.status)
    }

    @Test
    fun sendMessageValidationAndSummaryUpdate() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val conv: Conversation = startConv(buyer, env.listing(seller)).body()

        val msg: Message = sendMsg(buyer, conv.id, "  hello there  ").body()
        assertEquals("hello there", msg.body)
        assertEquals(conv.id, msg.conversationId)
        assertEquals(buyer.id.toString(), msg.senderId)

        assertTrue("body" in sendMsg(buyer, conv.id, "   ").error(HttpStatusCode.BadRequest).fields)
        assertTrue("body" in sendMsg(buyer, conv.id, "").error(HttpStatusCode.BadRequest).fields)
        assertTrue("body" in sendMsg(buyer, conv.id, "y".repeat(Limits.MESSAGE_MAX + 1)).error(HttpStatusCode.BadRequest).fields)
        // padding doesn't count towards the limit
        assertEquals(HttpStatusCode.OK, sendMsg(seller, conv.id, "  " + "y".repeat(Limits.MESSAGE_MAX) + "  ").status)

        val long = "word ".repeat(100)
        sendMsg(seller, conv.id, long)
        val updated: Conversation = client.get(ApiRoutes.conversation(conv.id)) { auth(buyer) }.body()
        val preview = assertNotNull(updated.lastMessagePreview)
        assertTrue(preview.length <= 120 && preview.startsWith("word word"), preview)
        assertNotNull(updated.lastMessageAt)
    }

    @Test
    fun unreadCountsAndMarkRead() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val conv: Conversation = startConv(buyer, env.listing(seller)).body()
        repeat(3) { sendMsg(buyer, conv.id, "m$it") }

        suspend fun unread(u: TestEnv.TestUser): Int =
            client.get(ApiRoutes.CONVERSATIONS) { auth(u) }.body<Page<Conversation>>().items.single().unreadCount
        assertEquals(3, unread(seller))
        assertEquals(0, unread(buyer))

        assertEquals(OkResponse(), client.post(ApiRoutes.conversationRead(conv.id)) { auth(seller) }.body())
        assertEquals(0, unread(seller))
        // idempotent
        assertEquals(HttpStatusCode.OK, client.post(ApiRoutes.conversationRead(conv.id)) { auth(seller) }.status)

        sendMsg(buyer, conv.id, "again")
        assertEquals(1, unread(seller))
        // replying marks the sender's side read as well
        sendMsg(seller, conv.id, "reply")
        assertEquals(0, unread(seller))
        assertEquals(1, unread(buyer))
    }

    @Test
    fun messageHistoryPagesNewestFirstWithoutGapsOrDuplicates() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val conv: Conversation = startConv(buyer, env.listing(seller)).body()
        repeat(25) { sendMsg(if (it % 2 == 0) buyer else seller, conv.id, "m$it") }

        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page: Page<Message> = client.get(ApiRoutes.conversationMessages(conv.id) + "?limit=10" + (cursor?.let { "&cursor=$it" } ?: "")) { auth(buyer) }.body()
            seen += page.items.map { it.body }
            cursor = page.nextCursor
            pages++
            if (cursor != null) assertEquals(10, page.items.size)
        } while (cursor != null)
        assertEquals(3, pages)
        assertEquals((24 downTo 0).map { "m$it" }, seen)

        // limit is clamped to PAGE_MAX; default page size applies when omitted
        val def: Page<Message> = client.get(ApiRoutes.conversationMessages(conv.id)) { auth(buyer) }.body()
        assertEquals(Limits.PAGE_DEFAULT, def.items.size)
        val huge: Page<Message> = client.get(ApiRoutes.conversationMessages(conv.id) + "?limit=9999") { auth(buyer) }.body()
        assertEquals(25, huge.items.size)
        assertNull(huge.nextCursor)

        assertEquals(HttpStatusCode.BadRequest, client.get(ApiRoutes.conversationMessages(conv.id) + "?cursor=bogus") { auth(buyer) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get(ApiRoutes.conversationMessages(conv.id) + "?limit=abc") { auth(buyer) }.status)
    }

    @Test
    fun messagesWithIdenticalTimestampsStillPageCleanly() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val conv: Conversation = startConv(buyer, env.listing(seller)).body()
        val convId = UUID.fromString(conv.id)
        val at = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val ids = (1..7).map { UUID.randomUUID() }
        env.db.tx { c ->
            ids.forEach { c.update("INSERT INTO messages (id, conversation_id, sender_id, body, created_at) VALUES (?, ?, ?, 'same', ?)", it, convId, buyer.id, at) }
        }
        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page: Page<Message> = client.get(ApiRoutes.conversationMessages(conv.id) + "?limit=3" + (cursor?.let { "&cursor=$it" } ?: "")) { auth(seller) }.body()
            seen += page.items.map { it.id }
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(ids.map { it.toString() }.sortedDescending(), seen)
    }

    @Test
    fun conversationListIsOrderedByRecentActivityAndPaged() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val listings = (1..5).map { env.listing(seller, title = "Item $it") }
        val convs = listings.map { startConv(buyer, it).body<Conversation>() }
        // activity on #2 (oldest-but-one) bumps it to the top; #1..5 otherwise by creation time
        sendMsg(seller, convs[1].id, "ping")

        val all: Page<Conversation> = client.get(ApiRoutes.CONVERSATIONS) { auth(buyer) }.body()
        assertEquals(listOf(1, 4, 3, 2, 0), all.items.map { convs.indexOfFirst { c -> c.id == it.id } })
        assertEquals(1, all.items.first().unreadCount)

        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page: Page<Conversation> = client.get(ApiRoutes.CONVERSATIONS + "?limit=2" + (cursor?.let { "&cursor=$it" } ?: "")) { auth(seller) }.body()
            seen += page.items.map { it.id }
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(all.items.map { it.id }, seen)

        val other = env.user("zed")
        val own: Page<Conversation> = client.get(ApiRoutes.CONVERSATIONS) { auth(other) }.body()
        assertTrue(own.items.isEmpty())
    }

    @Test
    fun blocksPreventStartAndSendBothWays() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val listing = env.listing(seller)
        val conv: Conversation = startConv(buyer, listing, "hi").body()

        env.db.tx { c -> c.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", seller.id, buyer.id) }
        assertEquals(ErrorCodes.BLOCKED, sendMsg(buyer, conv.id, "hello?").error(HttpStatusCode.Forbidden).code)
        assertEquals(ErrorCodes.BLOCKED, sendMsg(seller, conv.id, "go away").error(HttpStatusCode.Forbidden).code)
        assertEquals(ErrorCodes.BLOCKED, startConv(buyer, listing).error(HttpStatusCode.Forbidden).code)
        assertEquals(ErrorCodes.BLOCKED, startConv(buyer, listing, "x").error(HttpStatusCode.Forbidden).code)
        // history stays readable and the failed sends weren't stored
        val msgs: Page<Message> = client.get(ApiRoutes.conversationMessages(conv.id)) { auth(buyer) }.body()
        assertEquals(1, msgs.items.size)

        // reverse direction: buyer blocks seller; a brand-new buyer-side start is blocked too
        env.db.tx { c ->
            c.update("DELETE FROM blocks")
            c.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", buyer.id, seller.id)
        }
        assertEquals(ErrorCodes.BLOCKED, sendMsg(seller, conv.id, "hi").error(HttpStatusCode.Forbidden).code)
        val buyer2 = env.user("bea")
        env.db.tx { c -> c.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", seller.id, buyer2.id) }
        assertEquals(ErrorCodes.BLOCKED, startConv(buyer2, listing).error(HttpStatusCode.Forbidden).code)

        // unblocked again: works
        env.db.tx { c -> c.update("DELETE FROM blocks") }
        assertEquals(HttpStatusCode.OK, sendMsg(buyer, conv.id, "thanks").status)
    }

    @Test
    fun cannotMessageADeletedUser() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val conv: Conversation = startConv(buyer, env.listing(seller)).body()
        env.db.tx { c -> c.update("UPDATE users SET deleted_at = now() WHERE id = ?", seller.id) }
        assertEquals(ErrorCodes.CONFLICT, sendMsg(buyer, conv.id, "hi").error(HttpStatusCode.Conflict).code)
        val seen: Conversation = client.get(ApiRoutes.conversation(conv.id)) { auth(buyer) }.body()
        assertEquals("Deleted user", seen.otherUser.displayName)
        assertEquals("Deleted user", seen.listing.sellerName)
    }
}
