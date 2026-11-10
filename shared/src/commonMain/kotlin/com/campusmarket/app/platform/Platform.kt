package com.campusmarket.app.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/** Facts about the running build, supplied by each platform's entry point (see `initCampusMarket*`). */
data class AppEnvironment(
    /** Debug build: enables the server-URL override and HTTP logging. */
    val isDebug: Boolean,
    /** API origin used when no debug override is set, e.g. `http://10.0.2.2:8080`. */
    val defaultBaseUrl: String,
    val platform: PlatformKind,
)

enum class PlatformKind { ANDROID, IOS, DESKTOP }

/** HttpClient with the platform's engine (OkHttp on Android/desktop, Darwin on iOS). */
expect fun createPlatformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient

/** Opens a URL outside the app (system browser). Implemented per platform; use [ExternalLinks] from app code. */
fun interface PlatformUrlLauncher {
    /** Returns false if nothing could handle the URL. */
    fun open(url: String): Boolean
}
