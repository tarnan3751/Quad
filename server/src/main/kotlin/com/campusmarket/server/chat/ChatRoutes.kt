package com.campusmarket.server.chat

import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ChatCommand
import com.campusmarket.core.ChatEvent
import com.campusmarket.core.OkResponse
import com.campusmarket.core.SendMessageRequest
import com.campusmarket.core.StartConversationRequest
import com.campusmarket.server.ApiException
import com.campusmarket.server.AppDeps
import com.campusmarket.server.AUTH_JWT
import com.campusmarket.server.asUuid
import com.campusmarket.server.authenticateToken
import com.campusmarket.server.loadPrincipal
import com.campusmarket.server.pageParams
import com.campusmarket.server.receiveBody
import com.campusmarket.server.user
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.util.AttributeKey
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import java.time.Duration as JDuration

private val log = LoggerFactory.getLogger("Chat")

/** Consecutive undecodable frames tolerated before the socket is closed as abusive. */
private const val MAX_CONSECUTIVE_MALFORMED = 20

/** Tunables of the chat feature. Tests (or an embedding application) can override them via [ChatSettingsKey]. */
data class ChatSettings(
    /**
     * How often an open websocket re-checks that its account may still use the app. A suspended or deleted account
     * stops receiving events within this interval; the socket is also closed when its access token expires.
     */
    val revalidateEvery: Duration = 30.seconds,
)

/** Application attribute that overrides the default [ChatSettings]; must be set before routing is built. */
val ChatSettingsKey = AttributeKey<ChatSettings>("campusmarket.ChatSettings")

/**
 * Entry point for the chat feature; called once from Application.module. See docs/CONTRACT.md.
 * [hub] is injectable so tests (or a future multi-node implementation) can substitute it.
 */
fun Route.chatRoutes(deps: AppDeps, hub: ChatHub = InProcessChatHub()) {
    val service = ChatService(deps, hub)
    val settings = application.attributes.getOrNull(ChatSettingsKey) ?: ChatSettings()

    authenticate(AUTH_JWT) {
        route(ApiRoutes.CONVERSATIONS) {
            post {
                val req = call.receiveBody<StartConversationRequest>()
                call.respond(service.start(call.user, req.listingId, req.initialMessage))
            }
            get { call.respond(service.list(call.user, call.pageParams())) }
            route("{id}") {
                get { call.respond(service.get(call.user, call.parameters["id"].asUuid("Conversation"))) }
                get("messages") {
                    call.respond(service.messages(call.user, call.parameters["id"].asUuid("Conversation"), call.pageParams()))
                }
                post("messages") {
                    val id = call.parameters["id"].asUuid("Conversation")
                    val req = call.receiveBody<SendMessageRequest>()
                    call.respond(service.send(call.user, id, req.body, req.clientId))
                }
                post("read") {
                    service.markRead(call.user, call.parameters["id"].asUuid("Conversation"))
                    call.respond(OkResponse())
                }
            }
        }
    }

    webSocket(ApiRoutes.WS) {
        val token = call.request.queryParameters["token"]
        val principal = token?.takeIf { it.isNotBlank() }?.let { withContext(Dispatchers.IO) { authenticateToken(deps, it) } }
        if (principal == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid or expired token"))
            return@webSocket
        }
        val userId = principal.id
        val tokenExpiresAt = runCatching { deps.jwt.verifier.verify(token.orEmpty()).expiresAt?.toInstant() }.getOrNull() ?: Instant.now()
        val sub = hub.subscribe(userId)

        // The token and the account were checked once, at connect time; events keep flowing for as long as the socket
        // stays open. So end it when the token expires (the client refreshes and reconnects) or the account is
        // suspended or deleted, as every other endpoint would.
        val watchdog = launch {
            while (true) {
                val untilExpiry = JDuration.between(Instant.now(), tokenExpiresAt).toKotlinDuration()
                if (!untilExpiry.isPositive()) {
                    sub.close()
                    runCatching { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Access token expired")) }
                    break
                }
                delay(minOf(settings.revalidateEvery, untilExpiry))
                if (Instant.now() >= tokenExpiresAt) continue
                val current = try {
                    withContext(Dispatchers.IO) { loadPrincipal(deps.db, userId) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("websocket re-validation failed for {}; will retry", userId, e)
                    continue
                }
                if (current.first == null) {
                    sub.close()
                    runCatching { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Account unavailable")) }
                    break
                }
            }
        }

        // Single writer per socket: serialises frames and keeps slow sockets from ever blocking publishers.
        val writer = launch {
            try {
                for (event in sub.events) send(Frame.Text(ApiJson.encodeToString(ChatEvent.serializer(), event)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("websocket write failed for {}: {}", userId, e.toString())
            } finally {
                sub.close()
            }
            if (sub.dropped) runCatching { close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too slow to keep up")) }
        }

        try {
            var malformed = 0
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val command = try {
                    ApiJson.decodeFromString(ChatCommand.serializer(), frame.readText())
                } catch (e: SerializationException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
                if (command == null) {
                    if (++malformed >= MAX_CONSECUTIVE_MALFORMED) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Too many malformed frames"))
                        break
                    }
                    continue
                }
                malformed = 0
                if (command is ChatCommand.Ping) {
                    sub.offer(ChatEvent.Pong)
                    continue
                }
                // Authorisation is re-checked for every command: the account may have been suspended or deleted meanwhile.
                val current = withContext(Dispatchers.IO) { loadPrincipal(deps.db, userId).first }
                if (current == null) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Account unavailable"))
                    break
                }
                try {
                    when (command) {
                        is ChatCommand.Send -> service.send(current, command.conversationId.asUuid("Conversation"), command.body, command.clientId)
                        is ChatCommand.MarkRead -> service.markRead(current, command.conversationId.asUuid("Conversation"))
                        ChatCommand.Ping -> {}
                    }
                } catch (e: ApiException) {
                    // The event protocol has no error frame; rejected commands are dropped (REST reports the reason).
                    log.debug("websocket command rejected for {}: {} {}", userId, e.code, e.message)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("websocket command failed for {}", userId, e)
                }
            }
        } finally {
            sub.close()
            writer.cancel()
            watchdog.cancel()
        }
    }
}
