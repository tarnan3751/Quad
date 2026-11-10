package com.campusmarket.server.listings

import com.campusmarket.core.Limits
import com.campusmarket.server.invalidField
import io.ktor.server.application.ApplicationCall
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** `?limit=&cursor=` of a paged request. */
internal data class PageParams(val limit: Int, val cursor: String?)

internal fun ApplicationCall.pageParams(): PageParams {
    val qp = request.queryParameters
    val limit = qp["limit"]?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        raw.toIntOrNull()?.coerceIn(1, Limits.PAGE_MAX) ?: invalidField("limit", "limit must be a whole number")
    } ?: Limits.PAGE_DEFAULT
    val cursor = qp["cursor"]?.trim()?.takeIf { it.isNotEmpty() }
    return PageParams(limit, cursor)
}

/**
 * Position of the last row of a page in a keyset ordering: every column of that ordering's ORDER BY.
 * [createdAt] keeps Postgres' microsecond precision so rows created within the same millisecond still
 * order (and page) deterministically; [id] breaks any remaining ties.
 */
internal data class Keyset(
    val ordering: String,
    val createdAt: Instant,
    val id: UUID,
    val priceCents: Int = 0,
    val rank: Float = 0f,
)

/**
 * Opaque page cursors: base64url of a versioned, `|`-separated keyset. A cursor is only accepted by the
 * ordering that produced it (400 otherwise). Cursors need no signature: they only choose a start position
 * and every visibility rule is re-applied by the query.
 */
internal object Cursors {
    private const val VERSION = "1"
    private const val MAX_LENGTH = 512

    fun encode(k: Keyset): String {
        val raw = listOf(VERSION, k.ordering, k.createdAt.toString(), k.id.toString(), k.priceCents.toString(), k.rank.toRawBits().toString())
            .joinToString("|")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    fun decode(cursor: String, ordering: String): Keyset {
        if (cursor.length > MAX_LENGTH) invalid()
        val parts = runCatching { String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8).split('|') }.getOrNull()
        if (parts == null || parts.size != 6 || parts[0] != VERSION || parts[1] != ordering) invalid()
        return runCatching {
            Keyset(
                ordering = parts[1],
                createdAt = Instant.parse(parts[2]),
                id = UUID.fromString(parts[3]),
                priceCents = parts[4].toInt(),
                rank = Float.fromBits(parts[5].toInt()),
            )
        }.getOrNull()?.takeIf { it.rank.isFinite() } ?: invalid()
    }

    private fun invalid(): Nothing = invalidField("cursor", "Invalid cursor; start again from the first page")
}

/** Trims a `limit + 1` fetch to a page and computes the cursor for the next one. */
internal fun <R> paginate(rows: List<R>, limit: Int, keyOf: (R) -> Keyset): Pair<List<R>, String?> =
    if (rows.size > limit) rows.subList(0, limit) to Cursors.encode(keyOf(rows[limit - 1])) else rows to null

/** Accumulates SQL text and its positional parameters in lock step. */
internal class Sql(initial: String = "", vararg params: Any?) {
    private val text = StringBuilder(initial)
    val params: MutableList<Any?> = params.toMutableList()

    fun add(fragment: String, vararg ps: Any?): Sql {
        text.append(fragment)
        params.addAll(ps)
        return this
    }

    fun paramsArray(): Array<Any?> = params.toTypedArray()

    override fun toString(): String = text.toString()
}
