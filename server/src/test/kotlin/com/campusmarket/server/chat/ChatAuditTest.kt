package com.campusmarket.server.chat

import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ChatCommand
import com.campusmarket.core.ChatEvent
import com.campusmarket.core.Conversation
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Message
import com.campusmarket.core.Page
import com.campusmarket.server.ApiTestScope
import com.campusmarket.server.AppDeps
import com.campusmarket.server.Budget
import com.campusmarket.server.TestEnv
import com.campusmarket.server.UserRateLimits
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import com.campusmarket.server.module
import com.campusmarket.server.update
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class ChatAuditTest {

    /** The real application with [settings] for the chat feature. */
    private fun chatTest(settings: ChatSettings, block: suspend ApiTestScope.(TestEnv) -> Unit) = testApplication {
        TestEnv.db
        TestEnv.reset()
        application {
            attributes.put(ChatSettingsKey, settings)
            module(TestEnv.deps)
        }
        val client = createClient {
            install(ContentNegotiation) { json(ApiJson) }
            install(WebSockets)
        }
        ApiTestScope(this, client).block(TestEnv)
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.ping() {
        send(Frame.Text(ApiJson.encodeToString(ChatCommand.serializer(), ChatCommand.Ping)))
        val frame = withTimeout(5_000) { incoming.receive() } as Frame.Text
        assertEquals(ChatEvent.Pong, ApiJson.decodeFromString(ChatEvent.serializer(), frame.readText()))
    }

    private fun limited(limits: UserRateLimits) = AppDeps(TestEnv.config.copy(userRateLimits = limits), TestEnv.db)

    @Test
    fun openSocketOfASuspendedAccountIsClosed() = chatTest(ChatSettings(revalidateEvery = 100.milliseconds)) { env ->
        val u = env.user("sus")
        client.webSocket("${ApiRoutes.WS}?token=${u.token}") {
            ping()
            env.db.tx { c -> c.update("UPDATE users SET suspended = true, suspension_reason = 'spam' WHERE id = ?", u.id) }
            val reason = withTimeout(5_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }
    }

    @Test
    fun openSocketOfADeletedAccountIsClosed() = chatTest(ChatSettings(revalidateEvery = 100.milliseconds)) { env ->
        val u = env.user("gone")
        client.webSocket("${ApiRoutes.WS}?token=${u.token}") {
            ping()
            env.db.tx { c -> c.update("UPDATE users SET deleted_at = now() WHERE id = ?", u.id) }
            val reason = withTimeout(5_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }
    }

    @Test
    fun socketIsClosedWhenItsAccessTokenExpires() = chatTest(ChatSettings(revalidateEvery = Duration.INFINITE)) { env ->
        val u = env.user("brief")
        // a token issued long enough ago that it has about one second left
        val token = env.deps.jwt.issueAccessToken(u.id, Instant.now().minusSeconds(env.config.accessTokenTtlSeconds - 1)).token
        client.webSocket("${ApiRoutes.WS}?token=$token") {
            ping()
            val reason = withTimeout(5_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }
    }

    @Test
    fun messageSendingIsRateLimitedPerUserAcrossRestAndWebsocket() =
        apiTest(limited(UserRateLimits(messages = Budget(3, 1.hours)))) { env ->
            val seller = env.user("sally")
            val buyer = env.user("bob")
            val conv: Conversation = startConv(buyer, env.listing(seller), "hi").body() // 1
            assertEquals(HttpStatusCode.OK, sendMsg(buyer, conv.id, "two").status) // 2
            client.webSocket("${ApiRoutes.WS}?token=${buyer.token}") {
                send(Frame.Text(ApiJson.encodeToString(ChatCommand.serializer(), ChatCommand.Send(conv.id, "three")))) // 3
                val echo = withTimeout(5_000) { incoming.receive() } as Frame.Text
                assertIs<ChatEvent.MessageCreated>(ApiJson.decodeFromString(ChatEvent.serializer(), echo.readText()))
            }
            val err = sendMsg(buyer, conv.id, "four").error(HttpStatusCode.TooManyRequests)
            assertEquals(ErrorCodes.RATE_LIMITED, err.code)
            // the other participant has their own budget
            assertEquals(HttpStatusCode.OK, sendMsg(seller, conv.id, "reply").status)
            val history: Page<Message> = client.get(ApiRoutes.conversationMessages(conv.id)) { auth(seller) }.body()
            assertEquals(listOf("reply", "three", "two", "hi"), history.items.map { it.body })
        }

    @Test
    fun startingConversationsIsRateLimitedButReopeningOneIsNot() =
        apiTest(limited(UserRateLimits(conversations = Budget(2, 1.hours)))) { env ->
            val seller = env.user("sally")
            val buyer = env.user("bob")
            val listings = (1..3).map { env.listing(seller, title = "Item $it") }
            assertEquals(HttpStatusCode.OK, startConv(buyer, listings[0]).status)
            assertEquals(HttpStatusCode.OK, startConv(buyer, listings[1]).status)
            assertEquals(ErrorCodes.RATE_LIMITED, startConv(buyer, listings[2]).error(HttpStatusCode.TooManyRequests).code)
            assertEquals(HttpStatusCode.OK, startConv(buyer, listings[0]).status) // idempotent re-open is free
        }

    @Test
    fun controlCharactersInMessagesAreDroppedInsteadOfFailing() = apiTest { env ->
        val seller = env.user("sally")
        val buyer = env.user("bob")
        val conv: Conversation = startConv(buyer, env.listing(seller)).body()
        val res = sendMsg(buyer, conv.id, "hel\u0000lo\r\nthere")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("hello\nthere", res.body<Message>().body)
        assertEquals("body" in sendMsg(buyer, conv.id, "\u0000\u0001").error(HttpStatusCode.BadRequest).fields, true)
    }
}
