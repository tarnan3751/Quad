package com.campusmarket.server.listings

import com.campusmarket.server.queryOne
import java.sql.Connection
import java.text.Normalizer
import java.util.Locale

/** How a free-text query is matched against listings. */
internal sealed interface TextMatch {
    /** Full-text match against `listings.search`; [tsquery] is Postgres' own canonical rendering of the query. */
    data class FullText(val tsquery: String) : TextMatch

    /** Case-insensitive substring match on title/description, for queries too short (or too stop-wordy) for full text. */
    data class Substring(val text: String) : TextMatch {
        val containsPattern: String get() = "%" + escapeLike(text) + "%"
        val prefixPattern: String get() = escapeLike(text) + "%"
    }
}

/**
 * Turns arbitrary user input into a safe text match. Raw input never reaches `to_tsquery`:
 *  1. the query is NFC-normalised and split into terms made only of letters and digits (so no tsquery
 *     operators, quotes or backslashes can survive);
 *  2. all terms but the last go through `plainto_tsquery` (stemming, stop words, AND-ed);
 *  3. the last term becomes a quoted prefix lexeme `'term':*`, so results update while the user is still typing;
 *  4. Postgres renders the combined tsquery to text, which the search then uses as a bound `?::tsquery` parameter.
 * Very short queries (fewer than [MIN_FULL_TEXT_CHARS] letters/digits) and queries made only of stop words
 * ("the", "it") fall back to ILIKE on the trimmed input, with LIKE wildcards escaped.
 */
internal object SearchText {
    const val MAX_QUERY_CHARS = 200
    const val MAX_TERMS = 12
    const val MIN_FULL_TEXT_CHARS = 3

    private val term = Regex("[\\p{L}\\p{N}]+")
    private val spaceOrControl = Regex("[\\s\\p{Cc}]+") // NUL etc. can't even be stored in Postgres text

    /** Trimmed, NFC-normalised, control-character-free, length-capped query, or null if nothing is left. */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val s = Normalizer.normalize(raw, Normalizer.Form.NFC).replace(spaceOrControl, " ").trim()
        return s.take(MAX_QUERY_CHARS).trim().ifEmpty { null }
    }

    fun terms(q: String): List<String> = term.findAll(q.lowercase(Locale.ROOT)).map { it.value }.take(MAX_TERMS).toList()

    fun prepare(c: Connection, q: String): TextMatch {
        val terms = terms(q)
        if (terms.sumOf { it.length } < MIN_FULL_TEXT_CHARS) return TextMatch.Substring(q)
        val head = terms.dropLast(1).joinToString(" ")
        val prefixLexeme = "'" + terms.last() + "':*" // safe: the term is letters/digits only
        val (text, nodes) = c.queryOne(
            "SELECT q::text, numnode(q) FROM (SELECT plainto_tsquery('english', ?) && to_tsquery('english', ?) AS q) x",
            head, prefixLexeme,
        ) { it.getString(1) to it.getInt(2) }!!
        return if (nodes == 0) TextMatch.Substring(q) else TextMatch.FullText(text)
    }
}

/** Escapes LIKE wildcards using `!` as the escape character (pair with `ESCAPE '!'`). */
internal fun escapeLike(s: String): String = buildString(s.length + 8) {
    for (ch in s) {
        if (ch == '!' || ch == '%' || ch == '_') append('!')
        append(ch)
    }
}
