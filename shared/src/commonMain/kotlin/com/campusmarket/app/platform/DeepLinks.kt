package com.campusmarket.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App links we understand. The server sends users back with `{APP_DEEP_LINK_BASE}orders/{id}` and `...payouts`. */
sealed interface DeepLink {
    data class OrderDetail(val orderId: String) : DeepLink
    data object Payouts : DeepLink

    companion object {
        const val SCHEME = "campusmarket"

        /** Parses `campusmarket://orders/{id}` and `campusmarket://payouts` (query/fragment ignored). Null if unknown. */
        fun parse(url: String?): DeepLink? {
            if (url.isNullOrBlank()) return null
            val prefix = "$SCHEME://"
            if (!url.startsWith(prefix, ignoreCase = true)) return null
            val path = url.substring(prefix.length).substringBefore('?').substringBefore('#').trim('/')
            val parts = path.split('/').filter { it.isNotEmpty() }
            return when {
                parts.size == 2 && parts[0].equals("orders", ignoreCase = true) -> OrderDetail(parts[1])
                parts.size == 1 && parts[0].equals("payouts", ignoreCase = true) -> Payouts
                else -> null
            }
        }
    }
}

/**
 * Process-wide inbox for deep links. Platform entry points call [handle] (Android `MainActivity`, iOS `onOpenURL`);
 * the signed-in navigation shell [consume]s the pending link once a NavController exists. If the user is signed out,
 * the link waits until they sign in.
 */
object DeepLinks {
    private val _pending = MutableStateFlow<DeepLink?>(null)
    val pending: StateFlow<DeepLink?> = _pending.asStateFlow()

    /** Returns true if the URL was a recognised app link. */
    fun handle(url: String?): Boolean {
        val link = DeepLink.parse(url) ?: return false
        _pending.value = link
        return true
    }

    fun consume(link: DeepLink) {
        _pending.compareAndSet(link, null)
    }
}
