package com.campusmarket.app.feature.browse

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The last few searches, newest first. Kept only on this device, per account (another account signing in on the same
 * phone doesn't see them).
 */
class RecentSearches(private val read: () -> String?, private val write: (String?) -> Unit) {
    private val _items = MutableStateFlow(decode(read()))
    val items: StateFlow<List<String>> = _items.asStateFlow()

    fun add(query: String) {
        val q = query.trim().replace(WHITESPACE, " ")
        if (q.length < 2) return
        save(listOf(q) + _items.value.filterNot { it.equals(q, ignoreCase = true) })
    }

    fun remove(query: String) = save(_items.value - query)

    fun clear() = save(emptyList())

    private fun save(list: List<String>) {
        val trimmed = list.take(MAX)
        _items.value = trimmed
        write(trimmed.joinToString(SEPARATOR).ifEmpty { null })
    }

    companion object {
        const val MAX = 8
        private const val SEPARATOR = "\n"
        private val WHITESPACE = Regex("\\s+")

        private fun decode(raw: String?): List<String> = raw?.split(SEPARATOR)?.filter { it.isNotBlank() }?.take(MAX).orEmpty()

        fun inMemory(): RecentSearches {
            var value: String? = null
            return RecentSearches({ value }, { value = it })
        }

        fun stored(settings: Settings, userId: String?): RecentSearches {
            val key = "browse.recentSearches.${userId ?: "signedOut"}"
            return RecentSearches({ settings.getStringOrNull(key) }, { if (it == null) settings.remove(key) else settings.putString(key, it) })
        }
    }
}
