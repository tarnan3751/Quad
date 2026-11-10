package com.campusmarket.server.listings

import com.campusmarket.core.ApiRoutes
import com.campusmarket.server.TestEnv
import com.campusmarket.server.apiTest
import com.campusmarket.server.query
import com.campusmarket.server.update
import com.campusmarket.server.uuid
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Text search on a campus with more listings than a search reads in order, so that rare texts are found through the
 * indexes: every query must return exactly what a plain scan of the campus would, in the same order, on every page.
 */
class ListingSearchScaleTest {

    private val titles = listOf(
        "Desk lamp", "IKEA desk", "Calculus textbook", "Mini fridge", "TV stand", "Road bike", "Office chair", "LG television",
        "Winter jacket", "Standing desk", "Bookshelf", "The Hobbit", "Graphing calculator", "Monitor arm", "Couch",
    )
    private val descriptions = listOf(
        "Barely used, pickup at the library", "Works with any tv mount", "Oak, goes with a desk", "obo, moving out",
        "IKEA, assembled", "Like new", "Fits a dorm room, with a lamp", "Smoke free home", "Comes with the box", "8th edition",
        "Bright and warm", "Sturdy frame",
    )

    /** [count] listings, newest first by number, three to a timestamp; a few rare ones sit among the oldest. */
    private fun seed(env: TestEnv, sellers: List<TestEnv.TestUser>, count: Int = 2200) = env.db.txBlocking { c ->
        c.update(
            """INSERT INTO listings (id, seller_id, campus_domain, title, description, category, condition, price_cents, status, created_at)
               SELECT gen_random_uuid(), (?::uuid[])[1 + g % ?], 'state.edu',
                      CASE WHEN g IN (2000, 2003, 2050, 2082) THEN 'Zq zebra widget' WHEN g = 2060 THEN '100% cotton shirt'
                           WHEN g = 2070 THEN 'Lamp with a zebra shade' ELSE (?::text[])[1 + g % ?] END,
                      CASE WHEN g IN (2010, 2094) THEN 'Has a zq sticker and a zebra print, size_m' ELSE (?::text[])[1 + g % ?] END,
                      (ARRAY['TEXTBOOKS','ELECTRONICS','FURNITURE','BIKES'])[1 + g % 4], (ARRAY['NEW','GOOD','FAIR'])[1 + g % 3],
                      100 + (g * 37) % 900, CASE WHEN g % 10 = 9 THEN 'SOLD' ELSE 'ACTIVE' END,
                      timestamptz '2026-10-01 12:00:00+00' - (g / 3) * interval '1 minute'
               FROM generate_series(1, ?) g""",
            sellers.map { it.id }, sellers.size, titles, titles.size, descriptions, descriptions.size, count,
        )
    }

    /** What the search must return, computed without paging, indexes or the production query text. */
    private fun reference(env: TestEnv, q: String, fullText: Boolean, sort: String, hidden: List<UUID>, filter: String = "true"): List<UUID> = env.db.txBlocking { c ->
        val terms = SearchText.terms(q)
        val (match, rank) = if (fullText) {
            val tq = "(plainto_tsquery('english', '${terms.dropLast(1).joinToString(" ")}') && to_tsquery('english', '${terms.last()}:*'))"
            val any = "to_tsquery('english', '${(terms.dropLast(1) + "${terms.last()}:*").joinToString(" | ")}')"
            val title = "to_tsvector('english', l.title)"
            "l.search @@ $tq" to "CASE WHEN $title @@ $tq THEN 3 WHEN $title @@ $any THEN 2 ELSE 1 END"
        } else {
            val inTitle = "strpos(lower(l.title), lower(?))"
            "($inTitle > 0 OR strpos(lower(l.description), lower(?)) > 0)" to "CASE WHEN $inTitle = 1 THEN 3 WHEN $inTitle > 0 THEN 2 ELSE 1 END"
        }
        val order = when (sort) {
            "RELEVANCE" -> "$rank DESC, l.created_at DESC, l.id DESC"
            "NEWEST" -> "l.created_at DESC, l.id DESC"
            "PRICE_ASC" -> "l.price_cents ASC, l.created_at DESC, l.id DESC"
            else -> "l.price_cents DESC, l.created_at DESC, l.id DESC"
        }
        val sql = """SELECT l.id FROM listings l
                     WHERE l.campus_domain = 'state.edu' AND l.status = 'ACTIVE' AND NOT l.seller_id = ANY(?) AND $match AND $filter
                     ORDER BY $order"""
        val params = buildList<Any?> {
            add(hidden)
            if (!fullText) {
                add(q); add(q)
                if (sort == "RELEVANCE") { add(q); add(q) }
            }
        }
        c.query(sql, *params.toTypedArray()) { it.uuid("id") }
    }

    @Test
    fun everyTextSearchEqualsAScanOfTheCampus() = apiTest { env ->
        val viewer = env.user()
        val sellers = (1..5).map { env.user() }
        val blocked = sellers[1]
        val suspended = sellers[3]
        env.block(viewer, blocked)
        env.db.tx { c -> c.update("UPDATE users SET suspended = true WHERE id = ?", suspended.id) }
        seed(env, sellers)
        env.listing(env.user(domain = "other.edu"), title = "Zq zebra desk", description = "tv")
        val hidden = listOf(blocked.id, suspended.id)

        // text to whether it is a full-text query (three or more letters that aren't all stop words)
        val queries = listOf(
            "a" to false, "tv" to false, "zq" to false, "%" to false, "_" to false, "the" to false, "ob" to false, "x9" to false,
            "desk" to true, "ikea desk" to true, "zebra" to true, "lamp zeb" to true, "calc" to true, "unicycle" to true, "zq zebra" to true,
        )
        for ((q, fullText) in queries) {
            for (sort in listOf("RELEVANCE", "NEWEST", "PRICE_ASC", "PRICE_DESC")) {
                val want = reference(env, q, fullText, sort, hidden)
                val got = allPages(viewer, ApiRoutes.LISTINGS, 50, mapOf("q" to q, "sort" to sort)).ids()
                assertEquals(want, got, "q=$q sort=$sort")
            }
        }
        // the fixture has both kinds of text: some fill a page from the newest listings, some only exist among the oldest
        assertTrue(reference(env, "a", false, "NEWEST", hidden).size > 500)
        assertEquals(5, reference(env, "zq", false, "NEWEST", hidden).size)
        assertEquals(1, reference(env, "%", false, "NEWEST", hidden).size)
        assertEquals(0, reference(env, "x9", false, "NEWEST", hidden).size)

        // filters narrow every path the same way
        val filters = mapOf("category" to "BIKES,FURNITURE", "condition" to "GOOD", "minPrice" to "300", "maxPrice" to "800")
        val filterSql = "l.category IN ('BIKES', 'FURNITURE') AND l.condition = 'GOOD' AND l.price_cents BETWEEN 300 AND 800"
        for ((q, fullText) in listOf("tv" to false, "zq" to false, "desk" to true, "zebra" to true)) {
            for (sort in listOf("RELEVANCE", "PRICE_ASC")) {
                val want = reference(env, q, fullText, sort, hidden, filterSql)
                val got = allPages(viewer, ApiRoutes.LISTINGS, 7, filters + mapOf("q" to q, "sort" to sort)).ids()
                assertEquals(want, got, "q=$q sort=$sort filtered")
            }
        }
    }

    @Test
    fun everyIndexedFormIsServedByItsIndex() = apiTest { env ->
        // Enough rows that reading a whole index costs visibly more than looking something up in the right one, and
        // vacuumed, as a GIN index whose entries are all still in its pending list is costed as a full read too.
        seed(env, listOf(env.user()))
        env.db.readBlocking { c -> c.createStatement().use { it.execute("VACUUM ANALYZE listings") } }
        val matches = env.db.tx { c -> listOf(SearchText.prepare(c, "tv"), SearchText.prepare(c, "the of"), SearchText.prepare(c, "ikea desk")) }
        assertTrue(matches[0] is TextMatch.Substring && matches[1] is TextMatch.Substring && matches[2] is TextMatch.FullText)
        env.db.tx { c ->
            c.createStatement().use { it.execute("SET LOCAL enable_seqscan = off; SET LOCAL enable_indexscan = off") }
            for (match in matches) {
                for (tier in match.ranked("state.edu") + match.any("state.edu")) {
                    // as in production: the campus and status conditions of the query itself are of no use to an index
                    val plan = c.query(
                        "EXPLAIN SELECT 1 FROM listings l WHERE l.campus_domain || '' = 'state.edu' AND l.status || '' = 'ACTIVE' AND ${tier.indexed.sql}",
                        *tier.indexed.params,
                    ) { it.getString(1) }.joinToString("\n")
                    assertTrue(Regex("Bitmap Index Scan on listings_(title_grams|description_grams|title_search|search)_idx.*\\n\\s*Index Cond").containsMatchIn(plan), "${tier.indexed.sql}\n$plan")
                }
            }
        }
    }
}
