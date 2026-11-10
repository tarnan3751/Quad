package com.campusmarket.server

import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.OkResponse
import com.campusmarket.server.auth.authRoutes
import com.campusmarket.server.chat.chatRoutes
import com.campusmarket.server.legal.legalRoutes
import com.campusmarket.server.listings.listingRoutes
import com.campusmarket.server.moderation.moderationRoutes
import com.campusmarket.server.payments.paymentRoutes
import com.campusmarket.server.photos.photoRoutes
import com.campusmarket.server.users.userRoutes
import com.campusmarket.core.ErrorCodes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.contentLength
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlin.time.Duration.Companion.seconds

/** Process-wide dependencies handed to every feature's route function. */
class AppDeps(val config: AppConfig, val db: Db) {
    val jwt = JwtService(config)

    /** Per-user abuse limits; see [UserRateLimits]. */
    val throttle = UserThrottle(config.userRateLimits)
}

/** Largest request body accepted anywhere but the photo upload, which enforces its own (larger) cap. */
const val MAX_REQUEST_BODY_BYTES = 1L shl 20

/**
 * JSON bodies are read into memory whole, and nothing else bounds them, so one oversized request could exhaust the
 * heap (the auth endpoints are public). Bodies must declare a Content-Length (no unbounded chunked streams) of at
 * most [MAX_REQUEST_BODY_BYTES].
 */
private val RequestSizeGuard = createApplicationPlugin("RequestSizeGuard") {
    onCall { call ->
        if (call.request.path() == ApiRoutes.PHOTOS) return@onCall
        val length = call.request.contentLength()
        if (length != null && length > MAX_REQUEST_BODY_BYTES) {
            throw ApiException(HttpStatusCode.PayloadTooLarge, ErrorCodes.VALIDATION, "Request body is too large")
        }
        if (length == null && call.request.headers[HttpHeaders.TransferEncoding] != null) {
            throw ApiException(HttpStatusCode.LengthRequired, ErrorCodes.VALIDATION, "Request bodies must declare a Content-Length")
        }
    }
}

fun main() {
    val config = AppConfig.fromEnv()
    val db = Db.connect(config.dbUrl, config.dbUser, config.dbPassword)
    Db.migrate(db)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(AppDeps(config, db)) }.start(wait = true)
}

/**
 * Wires plugins and feature routes. Each feature owns one `Route.xxxRoutes(deps)` function in its own
 * package and registers its own paths (and its own `authenticate(AUTH_JWT)` blocks) inside it.
 */
fun Application.module(deps: AppDeps) {
    install(ContentNegotiation) { json(ApiJson) }
    install(CallLogging)
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 60.seconds
        maxFrameSize = 64 * 1024
    }
    installErrorHandling()
    install(RequestSizeGuard)
    installAuth(deps)

    routing {
        get(ApiRoutes.HEALTH) { call.respond(OkResponse()) }
        authRoutes(deps)
        userRoutes(deps)
        photoRoutes(deps)
        listingRoutes(deps)
        chatRoutes(deps)
        paymentRoutes(deps)
        moderationRoutes(deps)
        legalRoutes(deps)
    }
}
