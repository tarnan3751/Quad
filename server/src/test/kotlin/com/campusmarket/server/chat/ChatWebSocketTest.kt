package com.campusmarket.server.chat

import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ChatCommand
import com.campusmarket.core.ChatEvent
import com.campusmarket.core.Conversation
import com.campusmarket.core.Message
import com.campusmarket.core.Page
import com.campusmarket.server.ApiTestScope
import com.campusmarket.server.TestEnv
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import com.campusmarket.server.update
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private suspend fun DefaultClientWebSocketSession.next(): ChatEvent = withTimeout(5_000) {
    val frame = incoming.receive()
    ApiJson.decodeFromString(ChatEvent.serializer(), (frame as Frame.Text).readText())
}

private suspend fun DefaultClientWebSocketSession.nothingWithin(ms: Long = 300) {
    assertNull(withTimeoutOrNull(ms) { incoming.receive() }, "expected no frame")
}

private suspend fun DefaultClientWebSocketSession.cmd(c: ChatCommand) =
    send(Frame.Text(ApiJson.encodeToString(ChatCommand.serializer(), c)))

/** Round-trips a ping; once it returns the server has registered this socket and processed everything sent before. */
private suspend fun DefaultClientWebSocketSession.sync() {
    cmd(ChatCommand.Ping)
    assertEquals(ChatEvent.Pong, next())
}

private suspend fun ApiTestScope.withSockets(
    users: List<TestEnv.TestUser>,
    opened: List<DefaultClientWebSocketSession> = emptyList(),
    block: suspend (List<DefaultClientWebSocketSession>) -> Unit,
) {
    if (users.isEmpty()) return block(opened)
    client.webSocket("${ApiRoutes.WS}?token=${users.first().token}") {
        sync()
        withSockets(users.drop(1), opened + this, block)
    }
}

class ChatWebSocketTest {
    @Test
    fun invalidTokensAreClosedWithPolicyViolation() = apiTest { env ->
        val suspended = env.user("sus")
        env.db.tx { c -> c.update("UPDATE users SET suspended = true WHERE id = ?", suspended.id) }
        for (url in listOf(ApiRoutes.WS, "${ApiRoutes.WS}?token=", "${ApiRoutes.WS}?token=garbage", "${ApiRoutes.WS}?token=${suspended.token}")) {
            client.webSocket(url) {
                val reason = withTimeout(5_000) { closeReason.await() }
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code, url)
            }
        }
    }

    @Test
    fun pingGetsPong() = apiTest { env ->
        val u = env.user("bob")
        client.webSocket("${ApiRoutes.WS}?token=${u.token}") {
            sync()
            sync()
        }
    }

    @Test
    fun restSendsArePushedToEveryDeviceOfBothParticipantsOnly() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val stranger = env.user("eve")
        val conv: Conversation = startConv(buyer, env.listing(seller)).body()

        withSockets(listOf(buyer, buyer, seller, stranger)) { (buyerA, buyerB, sellerSock, strangerSock) ->
            val sent: Message = sendMsg(buyer, conv.id, "hello").body()
            for (s in listOf(buyerA, buyerB, sellerSock)) {
                val e = assertIs<ChatEvent.MessageCreated>(s.next())
                assertEquals(sent, e.message)
            }
            strangerSock.nothingWithin()

            // initial message via start is pushed too
            val seller2 = env.user("sam")
            val other: Conversation = startConv(buyer, env.listing(seller2), "first!").body()
            assertEquals("first!", assertIs<ChatEvent.MessageCreated>(buyerA.next()).message.body)
            assertEquals("first!", assertIs<ChatEvent.MessageCreated>(buyerB.next()).message.body)
            assertEquals(other.id, other.id)
            sellerSock.nothingWithin()
        }
    }

    @Test
    fun wsSendPersistsAndFansOutIncludingEchoToSender() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val conv: Conversation = startConv(buyer, env.listing(seller)).body()

        withSockets(listOf(buyer, seller)) { (b, s) ->
            b.cmd(ChatCommand.Send(conv.id, "  over the wire  "))
            val echo = assertIs<ChatEvent.MessageCreated>(b.next()).message
            val received = assertIs<ChatEvent.MessageCreated>(s.next()).message
            assertEquals(echo, received)
            assertEquals("over the wire", echo.body)
            assertEquals(buyer.id.toString(), echo.senderId)

            val history: Page<Message> = client.get(ApiRoutes.conversationMessages(conv.id)) { auth(seller) }.body()
            assertEquals(listOf(echo), history.items)
        }
    }

    @Test
    fun readCommandAndRestReadBroadcastConversationRead() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val conv: Conversation = startConv(buyer, env.listing(seller), "hey").body()

        withSockets(listOf(buyer, seller)) { (b, s) ->
            s.cmd(ChatCommand.MarkRead(conv.id))
            for (sock in listOf(b, s)) {
                val e = assertIs<ChatEvent.ConversationRead>(sock.next())
                assertEquals(conv.id, e.conversationId)
                assertEquals(seller.id.toString(), e.readerId)
            }
            val after: Conversation = client.get(ApiRoutes.conversation(conv.id)) { auth(seller) }.body()
            assertEquals(0, after.unreadCount)

            client.post(ApiRoutes.conversationRead(conv.id)) { auth(buyer) }
            for (sock in listOf(b, s)) {
                assertEquals(buyer.id.toString(), assertIs<ChatEvent.ConversationRead>(sock.next()).readerId)
            }
        }
    }

    @Test
    fun unauthorisedOrInvalidCommandsAreDroppedAndSocketStaysUsable() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val stranger = env.user("eve")
        val conv: Conversation = startConv(buyer, env.listing(seller)).body()

        withSockets(listOf(stranger, seller)) { (st, s) ->
            st.cmd(ChatCommand.Send(conv.id, "intrusion"))
            st.cmd(ChatCommand.MarkRead(conv.id))
            st.cmd(ChatCommand.Send("not-a-uuid", "x"))
            st.cmd(ChatCommand.Send(java.util.UUID.randomUUID().toString(), "x"))
            st.cmd(ChatCommand.Send(conv.id, "   "))
            st.sync()
            s.nothingWithin()
            val history: Page<Message> = client.get(ApiRoutes.conversationMessages(conv.id)) { auth(seller) }.body()
            assertTrue(history.items.isEmpty())

            // too-long body from a legitimate participant is also dropped
            s.cmd(ChatCommand.Send(conv.id, "z".repeat(com.campusmarket.core.Limits.MESSAGE_MAX + 1)))
            s.sync()
            assertTrue(client.get(ApiRoutes.conversationMessages(conv.id)) { auth(seller) }.body<Page<Message>>().items.isEmpty())
        }
    }

    @Test
    fun blockedUsersCannotSendOverTheSocket() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val conv: Conversation = startConv(buyer, env.listing(seller)).body()
        env.db.tx { c -> c.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", seller.id, buyer.id) }
        withSockets(listOf(buyer, seller)) { (b, s) ->
            b.cmd(ChatCommand.Send(conv.id, "let me in"))
            b.sync()
            s.nothingWithin()
            assertTrue(client.get(ApiRoutes.conversationMessages(conv.id)) { auth(seller) }.body<Page<Message>>().items.isEmpty())
        }
    }

    @Test
    fun malformedFramesAreIgnored() = apiTest { env ->
        val u = env.user("bob")
        client.webSocket("${ApiRoutes.WS}?token=${u.token}") {
            sync()
            send(Frame.Text("not json"))
            send(Frame.Text("{}"))
            send(Frame.Text("""{"type":"explode"}"""))
            send(Frame.Text("""{"type":"send","conversationId":5}"""))
            send(Frame.Text("[1,2,3]"))
            send(Frame.Binary(true, byteArrayOf(1, 2, 3)))
            sync()
            nothingWithin()
        }
    }

    @Test
    fun floodOfMalformedFramesClosesTheSocket() = apiTest { env ->
        val u = env.user("bob")
        client.webSocket("${ApiRoutes.WS}?token=${u.token}") {
            sync()
            repeat(30) { runCatching { send(Frame.Text("junk $it")) } }
            val reason = withTimeout(5_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }
    }

    @Test
    fun commandsAfterSuspensionCloseTheSocket() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val conv: Conversation = startConv(buyer, env.listing(seller)).body()
        client.webSocket("${ApiRoutes.WS}?token=${buyer.token}") {
            sync()
            env.db.tx { c -> c.update("UPDATE users SET suspended = true WHERE id = ?", buyer.id) }
            cmd(ChatCommand.Send(conv.id, "still here?"))
            val reason = withTimeout(5_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }
        env.db.tx { c -> c.update("UPDATE users SET suspended = false WHERE id = ?", buyer.id) }
        val history: Page<Message> = client.get(ApiRoutes.conversationMessages(conv.id)) { auth(buyer) }.body()
        assertTrue(history.items.isEmpty())
    }

    @Test
    fun clientCanCloseAndReconnect() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val conv: Conversation = startConv(buyer, env.listing(seller)).body()
        client.webSocket("${ApiRoutes.WS}?token=${seller.token}") {
            sync()
            close()
        }
        client.webSocket("${ApiRoutes.WS}?token=${seller.token}") {
            sync()
            sendMsg(buyer, conv.id, "welcome back")
            assertEquals("welcome back", assertIs<ChatEvent.MessageCreated>(next()).message.body)
        }
        // sending with nobody listening must not fail
        assertEquals(io.ktor.http.HttpStatusCode.OK, sendMsg(buyer, conv.id, "offline delivery").status)
    }
}
