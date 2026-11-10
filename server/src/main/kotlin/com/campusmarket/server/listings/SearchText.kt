package com.campusmarket.server.listings

import com.campusmarket.server.queryOne
import java.sql.Connection
import java.text.Normalizer
import java.util.Locale

/** A condition on the listing row `l`, with its parameters. */
internal class Cond(val sql: String, vararg val params: Any?)

/**
 * One relevance class of a text match: every listing in it ranks the same ([rank]) and they are ordered newest first.
 * [cheap] is quick to test on a row; [indexed] selects the same rows in a form an index can find them by.
 */
internal class MatchTier(val rank: Float, val cheap: Cond, val indexed: Cond)

/** How a free-text query is matched against listings. */
internal sealed interface TextMatch {
    /** Every match, as one class (for the orderings that don't rank). */
    fun any(campus: String): MatchTier

    /** The matches by relevance, best first: the classes are disjoint and together equal [any]. */
    fun ranked(campus: String): List<MatchTier>

    /**
     * Full-text match against `listings.search`; [tsquery] is Postgres' own canonical rendering of the query.
     * [inTitle] is the same query restricted to title lexemes, [anyInTitle] matches a title with at least one of the
     * [terms] terms. Ranking: every term in the title, then some of them, then description only.
     */
    data class FullText(val tsquery: String, val inTitle: String, val anyInTitle: String, val terms: Int) : TextMatch {
        override fun any(campus: String): MatchTier = Cond("l.search @@ ?::tsquery", tsquery).let { MatchTier(0f, it, it) }

        override fun ranked(campus: String): List<MatchTier> = buildList {
            add(
                MatchTier(
                    RANK_TITLE,
                    Cond("l.search @@ ?::tsquery", inTitle),
                    // Title lexemes are the weight-A ones of l.search; the first condition is for listings_title_search_idx.
                    // COALESCE keeps the second from being looked up in listings_search_idx as well, where the same
                    // lexemes also bring every description that has them.
                    Cond("$ACTIVE AND to_tsvector('english', l.title) @@ ?::tsquery AND COALESCE(l.search @@ ?::tsquery, false)", tsquery, inTitle),
                ),
            )
            if (terms > 1) {
                val some = Cond("l.search @@ ?::tsquery AND l.search @@ ?::tsquery AND NOT l.search @@ ?::tsquery", tsquery, anyInTitle, inTitle)
                add(MatchTier(RANK_PARTIAL, some, some))
            }
            val rest = Cond("l.search @@ ?::tsquery AND NOT l.search @@ ?::tsquery", tsquery, anyInTitle)
            add(MatchTier(RANK_BODY, rest, rest))
        }
    }

    /**
     * Case-insensitive substring match on title/description, for queries too short (or too stop-wordy) for full text.
     * Ranking: title starts with the text, title contains it, description only. The indexed forms go through the
     * `search_grams` indexes (V6_listings_1), which hold each text's one- and two-character pieces per campus.
     */
    data class Substring(val text: String) : TextMatch {
        val containsPattern: String get() = "%" + escapeLike(text) + "%"
        val prefixPattern: String get() = escapeLike(text) + "%"

        override fun any(campus: String): MatchTier = MatchTier(
            0f,
            Cond("(l.title ILIKE ? ESCAPE '!' OR l.description ILIKE ? ESCAPE '!')", containsPattern, containsPattern),
            // Two index lookups rather than one OR of both: Postgres tests an OR on every candidate row, grams included,
            // and computing a row's grams costs far more than the ILIKE they stand in for.
            Cond(
                """l.id IN (SELECT l.id FROM listings l
                            WHERE $ACTIVE AND $TITLE_GRAMS @> search_pattern_grams(?, ?) AND l.title ILIKE ? ESCAPE '!'
                            UNION ALL
                            SELECT l.id FROM listings l
                            WHERE $ACTIVE AND $DESCRIPTION_GRAMS @> search_pattern_grams(?, ?) AND l.description ILIKE ? ESCAPE '!')""",
                campus, text, containsPattern, campus, text, containsPattern,
            ),
        )

        override fun ranked(campus: String): List<MatchTier> {
            val prefix = Cond("l.title ILIKE ? ESCAPE '!'", prefixPattern)
            val inTitle = Cond("l.title ILIKE ? ESCAPE '!' AND NOT l.title ILIKE ? ESCAPE '!'", containsPattern, prefixPattern)
            val inBody = Cond("l.description ILIKE ? ESCAPE '!' AND NOT l.title ILIKE ? ESCAPE '!'", containsPattern, containsPattern)
            return listOf(
                MatchTier(RANK_TITLE, prefix, Cond("$ACTIVE AND $TITLE_GRAMS @> ARRAY[? || '|^^' || left(lower(?), 2)] AND ${prefix.sql}", campus, text, *prefix.params)),
                MatchTier(RANK_PARTIAL, inTitle, Cond("$ACTIVE AND $TITLE_GRAMS @> search_pattern_grams(?, ?) AND ${inTitle.sql}", campus, text, *inTitle.params)),
                MatchTier(RANK_BODY, inBody, Cond("$ACTIVE AND $DESCRIPTION_GRAMS @> search_pattern_grams(?, ?) AND ${inBody.sql}", campus, text, *inBody.params)),
            )
        }
    }

    companion object {
        const val RANK_TITLE = 1.0f
        const val RANK_PARTIAL = 0.6f
        const val RANK_BODY = 0.2f

        // The indexed forms run in a query that hides its own campus and status conditions from the planner; the
        // indexes that cover ACTIVE listings only need this one to be usable.
        private const val ACTIVE = "l.status = 'ACTIVE'"

        // spelled exactly like the index expressions, or the indexes aren't used
        private const val TITLE_GRAMS = "search_grams(l.campus_domain, l.title)"
        private const val DESCRIPTION_GRAMS = "search_grams(l.campus_domain, l.description)"
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
        // safe to quote: the terms are letters/digits only
        val prefixLexeme = "'" + terms.last() + "':*"
        val titleLexemes = terms.dropLast(1).map { "'$it':A" } + "'${terms.last()}':*A"
        return c.queryOne(
            """SELECT q::text, numnode(q), to_tsquery('english', ?)::text, to_tsquery('english', ?)::text
               FROM (SELECT plainto_tsquery('english', ?) && to_tsquery('english', ?) AS q) x""",
            titleLexemes.joinToString(" & "), titleLexemes.joinToString(" | "), head, prefixLexeme,
        ) { rs ->
            val nodes = rs.getInt(2) // n lexemes joined by n - 1 operators
            if (nodes == 0) TextMatch.Substring(q)
            else TextMatch.FullText(rs.getString(1), inTitle = rs.getString(3), anyInTitle = rs.getString(4), terms = (nodes + 1) / 2)
        }!!
    }
}

/** Escapes LIKE wildcards using `!` as the escape character (pair with `ESCAPE '!'`). */
internal fun escapeLike(s: String): String = buildString(s.length + 8) {
    for (ch in s) {
        if (ch == '!' || ch == '%' || ch == '_') append('!')
        append(ch)
    }
}
