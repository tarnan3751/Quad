package com.campusmarket.server.chat

import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ChatCommand
import com.campusmarket.core.ChatEvent
import com.campusmarket.core.Conversation
import com.campusmarket.core.Limits
import com.campusmarket.core.Message
import com.campusmarket.core.Page
import com.campusmarket.core.SendMessageRequest
import com.campusmarket.server.ApiTestScope
import com.campusmarket.server.TestEnv
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import com.campusmarket.server.json
import com.campusmarket.server.update
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private suspend fun ApiTestScope.sendWithId(user: TestEnv.TestUser, convId: String, text: String, clientId: String?): HttpResponse =
    client.post(ApiRoutes.conversationMessages(convId)) { auth(user); json(SendMessageRequest(text, clientId)) }

private suspend fun ApiTestScope.history(user: TestEnv.TestUser, convId: String): List<Message> =
    client.get(ApiRoutes.conversationMessages(convId)) { auth(user) }.body<Page<Message>>().items

private suspend fun DefaultClientWebSocketSession.event(): ChatEvent = withTimeout(5_000) {
    ApiJson.decodeFromString(ChatEvent.serializer(), (incoming.receive() as Frame.Text).readText())
}

private suspend fun DefaultClientWebSocketSession.command(c: ChatCommand) =
    send(Frame.Text(ApiJson.encodeToString(ChatCommand.serializer(), c)))

private suspend fun DefaultClientWebSocketSession.ping() {
    command(ChatCommand.Ping)
    assertEquals(ChatEvent.Pong, event())
}

class ChatIdempotentSendTest {
    @Test
    fun repeatingAClientIdReturnsTheStoredMessage() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val conv: Conversation = startConv(buyer, env.listing(seller)).body()

        val first: Message = sendWithId(buyer, conv.id, "Is it still available?", "msg-1").body()
        assertEquals("msg-1", first.clientId)
        val again = sendWithId(buyer, conv.id, "Is it still available?", "msg-1")
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(first, again.body<Message>())
        // The stored message wins even if the retry carries other text.
        assertEquals(first, sendWithId(buyer, conv.id, "edited", "msg-1").body<Message>())

        assertEquals(listOf(first), history(seller, conv.id))
        val seen: Conversation = client.get(ApiRoutes.conversation(conv.id)) { auth(seller) }.body()
        assertEquals(1, seen.unreadCount)
        assertEquals("Is it still available?", seen.lastMessagePreview)
    }

    @Test
    fun clientIdsAreScopedToTheSender() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val conv: Conversation = startConv(buyer, env.listing(seller)).body()

        val fromBuyer: Message = sendWithId(buyer, conv.id, "hi", "same-id").body()
        val fromSeller: Message = sendWithId(seller, conv.id, "hello", "same-id").body()
        assertTrue(fromBuyer.id != fromSeller.id)
        assertEquals(2, history(buyer, conv.id).size)

        // Without an id every send is a new message, and the field stays absent.
        val a: Message = sendMsg(buyer, conv.id, "twice").body()
        val b: Message = sendMsg(buyer, conv.id, "twice").body()
        assertTrue(a.id != b.id)
        assertNull(a.clientId)
    }

    @Test
    fun clientIdIsValidatedAndCannotMoveBetweenConversations() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val conv: Conversation = startConv(buyer, env.listing(seller)).body()
        val other: Conversation = startConv(buyer, env.listing(seller, title = "Chair")).body()

        for (bad in listOf("", "has space", "x".repeat(Limits.MESSAGE_CLIENT_ID_MAX + 1), "semi;colon")) {
            assertTrue("clientId" in sendWithId(buyer, conv.id, "hi", bad).error(HttpStatusCode.BadRequest).fields, bad)
        }
        assertEquals(HttpStatusCode.OK, sendWithId(buyer, conv.id, "hi", "x".repeat(Limits.MESSAGE_CLIENT_ID_MAX)).status)

        sendWithId(buyer, conv.id, "hi", "used")
        assertTrue("clientId" in sendWithId(buyer, other.id, "hi", "used").error(HttpStatusCode.BadRequest).fields)
        assertTrue(history(buyer, other.id).isEmpty())
    }

    @Test
    fun aRetryStillSucceedsAfterABlock() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val conv: Conversation = startConv(buyer, env.listing(seller)).body()

        val sent: Message = sendWithId(buyer, conv.id, "hi", "before-block").body()
        env.db.tx { c -> c.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", seller.id, buyer.id) }
        assertEquals(sent, sendWithId(buyer, conv.id, "hi", "before-block").body<Message>())
        assertEquals(HttpStatusCode.Forbidden, sendWithId(buyer, conv.id, "hi", "after-block").status)
    }

    @Test
    fun concurrentSendsWithOneClientIdCreateOneMessage() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val conv: Conversation = startConv(buyer, env.listing(seller)).body()

        val results = coroutineScope {
            (1..8).map { async { sendWithId(buyer, conv.id, "tap tap tap", "racing").body<Message>() } }.awaitAll()
        }
        assertEquals(1, results.map { it.id }.distinct().size)
        assertEquals(1, history(seller, conv.id).size)
    }

    @Test
    fun socketAndRestShareTheClientId() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val conv: Conversation = startConv(buyer, env.listing(seller)).body()

        client.webSocket("${ApiRoutes.WS}?token=${seller.token}") {
            val s = this
            s.ping()
            client.webSocket("${ApiRoutes.WS}?token=${buyer.token}") {
                val b = this
                b.ping()
                b.command(ChatCommand.Send(conv.id, "over the wire", "ws-1"))
                val echo = assertIs<ChatEvent.MessageCreated>(b.event()).message
                assertEquals("ws-1", echo.clientId)
                assertEquals(echo, assertIs<ChatEvent.MessageCreated>(s.event()).message)

                // The same id again, on the socket and over REST: no new message, no second event.
                b.command(ChatCommand.Send(conv.id, "over the wire", "ws-1"))
                b.ping()
                assertEquals(echo, sendWithId(buyer, conv.id, "over the wire", "ws-1").body<Message>())
                assertNull(withTimeoutOrNull(300) { s.incoming.receive() }, "no second event")
                assertEquals(listOf(echo), history(seller, conv.id))
            }
        }
    }
}
