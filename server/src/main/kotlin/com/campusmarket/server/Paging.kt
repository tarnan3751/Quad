package com.campusmarket.server

import com.campusmarket.core.Limits
import com.campusmarket.core.Page
import io.ktor.server.application.ApplicationCall
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** `?limit=&cursor=` of a paged request. */
data class PageParams(val limit: Int, val cursor: String?)

/** `limit` is clamped to 1..[Limits.PAGE_MAX] (default [Limits.PAGE_DEFAULT]); a blank cursor is no cursor. */
fun ApplicationCall.pageParams(): PageParams {
    val qp = request.queryParameters
    val limit = qp["limit"]?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        raw.toIntOrNull()?.coerceIn(1, Limits.PAGE_MAX) ?: invalidField("limit", "limit must be a whole number")
    } ?: Limits.PAGE_DEFAULT
    return PageParams(limit, qp["cursor"]?.trim()?.takeIf { it.isNotEmpty() })
}

/** An optional enum query parameter given by exact name: [default] when absent or empty, else 400 (`fields.<name>`). */
inline fun <reified E : Enum<E>> ApplicationCall.enumParam(name: String, default: E): E {
    val raw = request.queryParameters[name]?.takeIf { it.isNotEmpty() } ?: return default
    return enumValues<E>().firstOrNull { it.name == raw } ?: invalidField(name, "Unknown $name '${raw.take(40)}'")
}

/**
 * Position after the last row of a page in a (timestamp, id) ordering. The timestamp keeps Postgres' microsecond
 * precision, so rows created within the same millisecond still page deterministically; the id breaks ties.
 * Opaque to clients: base64url of `<epoch micros>:<id>`. Cursors need no signature: they only choose a start
 * position, and every visibility rule is re-applied by the query.
 */
data class KeysetCursor(val at: Instant, val id: UUID) {
    fun encode(): String {
        val micros = at.epochSecond * 1_000_000L + at.nano / 1000
        return Base64.getUrlEncoder().withoutPadding().encodeToString("$micros:$id".toByteArray())
    }

    companion object {
        /** Null for no cursor; 400 (`fields.cursor`) for anything else that isn't a cursor. Also reads the older `<seconds>:<nanos>:<id>`. */
        fun decode(raw: String?): KeysetCursor? {
            if (raw.isNullOrEmpty()) return null
            return runCatching {
                val parts = String(Base64.getUrlDecoder().decode(raw)).split(':')
                val at = when (parts.size) {
                    2 -> parts[0].toLong().let { Instant.ofEpochSecond(Math.floorDiv(it, 1_000_000L), Math.floorMod(it, 1_000_000L) * 1000) }
                    3 -> Instant.ofEpochSecond(parts[0].toLong(), parts[1].toLong())
                    else -> null
                }
                at?.let { KeysetCursor(it, UUID.fromString(parts.last())) }
            }.getOrNull() ?: invalidField("cursor", "Invalid cursor; start again from the first page")
        }
    }
}

/** The keyset of a row whose first two columns are its id and its ordering timestamp. */
fun ResultSet.keyset(): KeysetCursor = KeysetCursor(getTimestamp(2).toInstant(), getObject(1, UUID::class.java))

/** Accumulates SQL text and its positional parameters in lock step. */
class Sql(initial: String = "", vararg params: Any?) {
    private val text = StringBuilder(initial)
    private val params: MutableList<Any?> = params.toMutableList()

    fun add(fragment: String, vararg ps: Any?): Sql {
        text.append(fragment)
        params.addAll(ps)
        return this
    }

    /**
     * Ends a query whose WHERE clause is open with keyset paging on ([at], [id]): the rows after [after], newest first
     * (oldest first when [ascending]), at most `limit + 1` of them (see [page]).
     */
    fun keysetPage(at: String, id: String, after: KeysetCursor?, limit: Int, ascending: Boolean = false): Sql {
        val (cmp, dir) = if (ascending) ">" to "ASC" else "<" to "DESC"
        after?.let { add(" AND ($at, $id) $cmp (?, ?)", it.at, it.id) }
        return add(" ORDER BY $at $dir, $id $dir LIMIT ?", limit + 1)
    }

    fun paramsArray(): Array<Any?> = params.toTypedArray()

    override fun toString(): String = text.toString()
}

fun <T> Connection.query(sql: Sql, map: (ResultSet) -> T): List<T> = query(sql.toString(), *sql.paramsArray(), map = map)

/** Runs a [Sql.keysetPage] query and trims it to a page; the next cursor is [keyOf] its last row. */
fun <T> Connection.page(sql: Sql, limit: Int, keyOf: (T) -> KeysetCursor, map: (ResultSet) -> T): Page<T> {
    val rows = query(sql, map)
    val items = rows.take(limit)
    return Page(items, if (rows.size > limit) keyOf(items.last()).encode() else null)
}

/** [page] for a query selecting `(id, timestamp)`, rendered in order by [render] (which may drop ids). */
fun <T> Connection.pageOfIds(sql: Sql, limit: Int, render: Connection.(List<UUID>) -> List<T>): Page<T> {
    val keys = page(sql, limit, { it }) { it.keyset() }
    return Page(render(keys.items.map { it.id }), keys.nextCursor)
}
