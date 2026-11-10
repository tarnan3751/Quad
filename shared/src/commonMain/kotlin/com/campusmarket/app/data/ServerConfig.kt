package com.campusmarket.app.data

import com.campusmarket.app.platform.AppEnvironment
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the API lives. Defaults per platform (Android emulator `http://10.0.2.2:8080`, desktop / iOS simulator
 * `http://localhost:8080`); debug builds can override it at runtime (Welcome screen → "Server", or Settings),
 * and the override is persisted.
 */
class ServerConfig(
    private val environment: AppEnvironment,
    private val settings: Settings? = null,
) {
    private val _baseUrl = MutableStateFlow(initial())
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    val defaultBaseUrl: String get() = environment.defaultBaseUrl
    val canOverride: Boolean get() = environment.isDebug
    val isOverridden: Boolean get() = _baseUrl.value != normalize(environment.defaultBaseUrl)

    private fun initial(): String {
        val override = if (environment.isDebug) settings?.getStringOrNull(KEY) else null
        return normalize(override ?: environment.defaultBaseUrl)
    }

    /** Debug builds only. `null` or blank resets to the platform default. Takes effect for the next request. */
    fun setOverride(url: String?) {
        if (!environment.isDebug) return
        val clean = url?.trim().orEmpty()
        if (clean.isEmpty()) {
            settings?.remove(KEY)
            _baseUrl.value = normalize(environment.defaultBaseUrl)
        } else {
            val normalized = normalize(if ("://" in clean) clean else "http://$clean")
            settings?.putString(KEY, normalized)
            _baseUrl.value = normalized
        }
    }

    /** Absolute URL for an [com.campusmarket.core.ApiRoutes] path. */
    fun url(path: String): String = _baseUrl.value + path

    /** ws:// or wss:// URL for a path. */
    fun wsUrl(path: String): String {
        val base = _baseUrl.value
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        return wsBase + path
    }

    /**
     * Makes a server-provided URL (photo, avatar, fake-Stripe page) reachable from this device.
     *
     * The server builds absolute URLs from its `PUBLIC_BASE_URL` (default `http://localhost:8080`), which the Android
     * emulator can't reach. In dev we therefore rewrite loopback origins (`localhost` / `127.0.0.1`) to our base URL,
     * and resolve relative paths against it. Real (non-loopback) URLs pass through unchanged.
     */
    fun resolve(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("/")) return _baseUrl.value + url
        val match = LOOPBACK_ORIGIN.find(url) ?: return url
        val rest = url.substring(match.value.length)
        return _baseUrl.value + rest
    }

    private companion object {
        const val KEY = "debug.serverBaseUrl"
        val LOOPBACK_ORIGIN = Regex("^https?://(localhost|127\\.0\\.0\\.1)(:\\d+)?", RegexOption.IGNORE_CASE)
        fun normalize(url: String) = url.trim().trimEnd('/')
    }
}
