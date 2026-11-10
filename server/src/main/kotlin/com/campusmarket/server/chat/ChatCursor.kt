package com.campusmarket.server.chat

import com.campusmarket.core.Limits
import com.campusmarket.server.invalidField
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Keyset position: a timestamp (microsecond precision) plus the row id as tie-break. Opaque to clients. */
internal data class KeysetCursor(val at: Instant, val id: UUID) {
    fun encode(): String {
        val micros = at.epochSecond * 1_000_000L + at.nano / 1000
        return Base64.getUrlEncoder().withoutPadding().encodeToString("$micros:$id".toByteArray())
    }

    companion object {
        fun decode(raw: String?): KeysetCursor? {
            if (raw.isNullOrEmpty()) return null
            val parsed = runCatching {
                val text = String(Base64.getUrlDecoder().decode(raw))
                val micros = text.substringBefore(':').toLong()
                val id = UUID.fromString(text.substringAfter(':'))
                KeysetCursor(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000), id)
            }.getOrNull()
            return parsed ?: invalidField("cursor", "Invalid cursor")
        }
    }
}

internal fun parseLimit(raw: String?): Int {
    if (raw.isNullOrEmpty()) return Limits.PAGE_DEFAULT
    val n = raw.toIntOrNull() ?: invalidField("limit", "limit must be a number")
    return n.coerceIn(1, Limits.PAGE_MAX)
}
