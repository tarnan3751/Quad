package com.campusmarket.server.listings

import com.campusmarket.core.CategoryInfo
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingSort
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.Page
import com.campusmarket.server.AppDeps
import com.campusmarket.server.UserPrincipal
import com.campusmarket.server.invalidField
import com.campusmarket.server.loadListingSummaries
import com.campusmarket.server.notFound
import com.campusmarket.server.query
import com.campusmarket.server.queryOne
import com.campusmarket.server.uuid
import io.ktor.http.Parameters
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** Parsed `GET /listings` filters. */
internal data class SearchParams(
    val text: String? = null,
    val categories: Set<ListingCategory> = emptySet(),
    val conditions: Set<ListingCondition> = emptySet(),
    val minPriceCents: Int? = null,
    val maxPriceCents: Int? = null,
    val sort: ListingSort? = null,
) {
    companion object {
        fun parse(qp: Parameters): SearchParams {
            val min = qp.cents("minPrice")
            val max = qp.cents("maxPrice")
            if (min != null && max != null && min > max) invalidField("maxPrice", "Maximum price must not be below the minimum price")
            return SearchParams(
                text = SearchText.normalize(qp["q"]),
                categories = qp.enumSet("category"),
                conditions = qp.enumSet("condition"),
                minPriceCents = min,
                maxPriceCents = max,
                sort = qp["sort"]?.trim()?.takeIf { it.isNotEmpty() }?.let { s ->
                    ListingSort.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
                        ?: invalidField("sort", "sort must be one of ${ListingSort.entries.joinToString()}")
                },
            )
        }

        private fun Parameters.cents(name: String): Int? = this[name]?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            raw.toIntOrNull()?.takeIf { it >= 0 } ?: invalidField(name, "$name must be a whole number of cents")
        }
    }
}

/** Comma-separated and/or repeated enum values (`?condition=GOOD,LIKE_NEW&condition=FAIR`), case-insensitive. */
internal inline fun <reified E : Enum<E>> Parameters.enumSet(name: String): Set<E> =
    getAll(name).orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.mapTo(linkedSetOf()) { v ->
        enumValues<E>().firstOrNull { it.name.equals(v, ignoreCase = true) }
            ?: invalidField(name, "Unknown $name '${v.take(40)}'; expected one of ${enumValues<E>().joinToString { it.name }}")
    }

/** One row of a keyset-paged listing query, before rendering. */
private data class Hit(val id: UUID, val key: Keyset)

/** Paged / aggregated listing reads. */
internal class ListingBrowse(private val deps: AppDeps) {

    /** GET /listings: ACTIVE listings on the caller's campus, minus blocked and suspended sellers. */
    suspend fun search(viewer: UserPrincipal, p: SearchParams, page: PageParams): Page<ListingSummary> = deps.db.tx { c ->
        val match = p.text?.let { SearchText.prepare(c, it) }
        val sort = when {
            p.sort == null -> if (match != null) ListingSort.RELEVANCE else ListingSort.NEWEST
            p.sort == ListingSort.RELEVANCE && match == null -> ListingSort.NEWEST
            else -> p.sort
        }
        val after = page.cursor?.let { Cursors.decode(it, sort.name) }

        val sql = Sql("SELECT x.id, x.price_cents, x.created_at, x.rank FROM (SELECT l.id, l.price_cents, l.created_at, ")
        when {
            sort != ListingSort.RELEVANCE -> sql.add("0::real")
            match is TextMatch.FullText -> sql.add("ts_rank(l.search, ?::tsquery)", match.tsquery)
            match is TextMatch.Substring -> sql.add(
                "(CASE WHEN l.title ILIKE ? ESCAPE '!' THEN 1.0 WHEN l.title ILIKE ? ESCAPE '!' THEN 0.6 ELSE 0.2 END)::real",
                match.prefixPattern, match.containsPattern,
            )
        }
        sql.add(
            """ AS rank
               FROM listings l JOIN users s ON s.id = l.seller_id
               WHERE l.campus_domain = ? AND l.status = 'ACTIVE' AND $SELLER_IN_GOOD_STANDING
                 AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.blocker_id = ? AND b.blocked_id = l.seller_id)""",
            viewer.campusDomain, viewer.id,
        )
        when (match) {
            is TextMatch.FullText -> sql.add(" AND l.search @@ ?::tsquery", match.tsquery)
            is TextMatch.Substring -> sql.add(
                " AND (l.title ILIKE ? ESCAPE '!' OR l.description ILIKE ? ESCAPE '!')",
                match.containsPattern, match.containsPattern,
            )
            null -> Unit
        }
        if (p.categories.isNotEmpty()) sql.add(" AND l.category = ANY(?)", p.categories.toList())
        if (p.conditions.isNotEmpty()) sql.add(" AND l.condition = ANY(?)", p.conditions.toList())
        p.minPriceCents?.let { sql.add(" AND l.price_cents >= ?", it) }
        p.maxPriceCents?.let { sql.add(" AND l.price_cents <= ?", it) }
        sql.add(") x")

        if (after != null) {
            val ts = after.createdAt.toString()
            when (sort) {
                ListingSort.NEWEST -> sql.add(" WHERE (x.created_at, x.id) < (?::timestamptz, ?)", ts, after.id)
                ListingSort.PRICE_ASC -> sql.add(
                    " WHERE (x.price_cents > ? OR (x.price_cents = ? AND (x.created_at, x.id) < (?::timestamptz, ?)))",
                    after.priceCents, after.priceCents, ts, after.id,
                )
                ListingSort.PRICE_DESC -> sql.add(" WHERE (x.price_cents, x.created_at, x.id) < (?, ?::timestamptz, ?)", after.priceCents, ts, after.id)
                ListingSort.RELEVANCE -> sql.add(" WHERE (x.rank, x.created_at, x.id) < (?::real, ?::timestamptz, ?)", after.rank.toDouble(), ts, after.id)
            }
        }
        sql.add(
            when (sort) {
                ListingSort.NEWEST -> " ORDER BY x.created_at DESC, x.id DESC"
                ListingSort.PRICE_ASC -> " ORDER BY x.price_cents ASC, x.created_at DESC, x.id DESC"
                ListingSort.PRICE_DESC -> " ORDER BY x.price_cents DESC, x.created_at DESC, x.id DESC"
                ListingSort.RELEVANCE -> " ORDER BY x.rank DESC, x.created_at DESC, x.id DESC"
            },
        )
        sql.add(" LIMIT ?", page.limit + 1)

        val hits = c.query(sql.toString(), *sql.paramsArray()) { rs ->
            Hit(
                rs.uuid("id"),
                Keyset(sort.name, rs.instantMicros("created_at"), rs.uuid("id"), rs.getInt("price_cents"), rs.getFloat("rank")),
            )
        }
        c.render(viewer, hits, page.limit)
    }

    /** GET /me/listings: the caller's own listings in any status but DELETED, newest first. */
    suspend fun mine(viewer: UserPrincipal, statuses: Set<ListingStatus>, page: PageParams): Page<ListingSummary> = deps.db.tx { c ->
        val sql = Sql("SELECT l.id, l.created_at FROM listings l WHERE l.seller_id = ? AND l.status <> 'DELETED'", viewer.id)
        if (statuses.isNotEmpty()) sql.add(" AND l.status = ANY(?)", statuses.toList())
        c.newestFirst(viewer, sql, page)
    }

    /** GET /users/{id}/listings: another user's ACTIVE listings; 404 unless they're on the caller's campus. */
    suspend fun bySeller(viewer: UserPrincipal, sellerId: UUID, page: PageParams): Page<ListingSummary> = deps.db.tx { c ->
        val campus = c.queryOne("SELECT campus_domain FROM users WHERE id = ?", sellerId) { it.getString(1) }
        if (campus == null || campus != viewer.campusDomain) notFound("User")
        val sql = Sql(
            """SELECT l.id, l.created_at FROM listings l JOIN users s ON s.id = l.seller_id
               WHERE l.seller_id = ? AND l.campus_domain = ? AND l.status = 'ACTIVE' AND $SELLER_IN_GOOD_STANDING""",
            sellerId, viewer.campusDomain,
        )
        c.newestFirst(viewer, sql, page)
    }

    /** GET /me/favorites: most recently favorited first; REMOVED/DELETED listings are hidden. */
    suspend fun favorites(viewer: UserPrincipal, page: PageParams): Page<ListingSummary> = deps.db.tx { c ->
        val ordering = "FAVORITES"
        val after = page.cursor?.let { Cursors.decode(it, ordering) }
        val sql = Sql(
            """SELECT f.listing_id, f.created_at FROM favorites f JOIN listings l ON l.id = f.listing_id
               WHERE f.user_id = ? AND l.campus_domain = ? AND l.status IN ('ACTIVE', 'RESERVED', 'SOLD')""",
            viewer.id, viewer.campusDomain,
        )
        after?.let { sql.add(" AND (f.created_at, f.listing_id) < (?::timestamptz, ?)", it.createdAt.toString(), it.id) }
        sql.add(" ORDER BY f.created_at DESC, f.listing_id DESC LIMIT ?", page.limit + 1)
        val hits = c.query(sql.toString(), *sql.paramsArray()) { rs ->
            val id = rs.uuid("listing_id")
            Hit(id, Keyset(ordering, rs.instantMicros("created_at"), id))
        }
        c.render(viewer, hits, page.limit)
    }

    /** GET /categories: every category, with the number of listings the caller would find by browsing it. */
    suspend fun categories(viewer: UserPrincipal): List<CategoryInfo> = deps.db.tx { c ->
        val counts = c.query(
            """SELECT l.category, count(*) FROM listings l JOIN users s ON s.id = l.seller_id
               WHERE l.campus_domain = ? AND l.status = 'ACTIVE' AND $SELLER_IN_GOOD_STANDING
                 AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.blocker_id = ? AND b.blocked_id = l.seller_id)
               GROUP BY l.category""",
            viewer.campusDomain, viewer.id,
        ) { it.getString(1) to it.getInt(2) }.toMap()
        ListingCategory.entries.map { CategoryInfo(it, it.label, counts[it.name] ?: 0) }
    }

    /** Appends newest-first keyset paging to a `SELECT l.id, l.created_at ... WHERE ...` query and runs it. */
    private fun Connection.newestFirst(viewer: UserPrincipal, sql: Sql, page: PageParams): Page<ListingSummary> {
        val ordering = ListingSort.NEWEST.name
        page.cursor?.let { Cursors.decode(it, ordering) }?.let {
            sql.add(" AND (l.created_at, l.id) < (?::timestamptz, ?)", it.createdAt.toString(), it.id)
        }
        sql.add(" ORDER BY l.created_at DESC, l.id DESC LIMIT ?", page.limit + 1)
        val hits = query(sql.toString(), *sql.paramsArray()) { rs ->
            val id = rs.uuid("id")
            Hit(id, Keyset(ordering, rs.instantMicros("created_at"), id))
        }
        return render(viewer, hits, page.limit)
    }

    private fun Connection.render(viewer: UserPrincipal, hits: List<Hit>, limit: Int): Page<ListingSummary> {
        val (pageHits, next) = paginate(hits, limit) { it.key }
        val summaries = loadListingSummaries(deps.config, pageHits.map { it.id }, viewer.id)
        return Page(pageHits.mapNotNull { summaries[it.id] }, next)
    }
}

/** Full-precision (microsecond) timestamp; [com.campusmarket.server.millis] would truncate and break keyset ties. */
private fun ResultSet.instantMicros(col: String) = getTimestamp(col).toInstant()
