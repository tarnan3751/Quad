package com.campusmarket.app.data

import com.campusmarket.core.ApiJson
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Duration.Companion.seconds

/**
 * Shared Ktor configuration: JSON via [ApiJson], websockets, timeouts, and (debug only) request-line logging with the
 * Authorization header redacted. `expectSuccess` stays false: [ApiClient] maps non-2xx responses itself.
 */
fun HttpClientConfig<*>.installCampusMarketDefaults(debugLogging: Boolean) {
    expectSuccess = false
    install(ContentNegotiation) { json(ApiJson) }
    install(WebSockets) {
        pingIntervalMillis = 20.seconds.inWholeMilliseconds
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 60_000 // photo uploads on slow campus Wi-Fi
        socketTimeoutMillis = 30_000
    }
    if (debugLogging) {
        install(Logging) {
            logger = Logger.SIMPLE
            level = LogLevel.INFO
            sanitizeHeader { it == HttpHeaders.Authorization }
        }
    }
}
