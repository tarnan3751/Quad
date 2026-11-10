package com.campusmarket.server.listings

import com.campusmarket.core.CategoryInfo
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingSort
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.Page
import com.campusmarket.server.AppDeps
import com.campusmarket.server.PageParams
import com.campusmarket.server.Sql
import com.campusmarket.server.UserPrincipal
import com.campusmarket.server.invalidField
import com.campusmarket.server.loadListingSummaries
import com.campusmarket.server.notFound
import com.campusmarket.server.query
import com.campusmarket.server.queryFreshPlan
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

/** Seller and viewer conditions of every browse query over `listings l JOIN users s`; one parameter, the viewer's id. */
private const val VISIBLE_TO_VIEWER =
    "$SELLER_IN_GOOD_STANDING AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.blocker_id = ? AND b.blocked_id = l.seller_id)"

/** How many listings a text search reads in order, per relevance class, before turning to the text indexes. */
private const val WALK_WINDOW = 1000

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
        val hits = if (match == null) c.browse(viewer, p, sort, after, page.limit + 1) else c.textSearch(viewer, p, match, sort, after, page.limit + 1)
        c.render(viewer, hits, page.limit)
    }

    /** The first [count] visible listings after [after] in [sort] order, no text query. */
    private fun Connection.browse(viewer: UserPrincipal, p: SearchParams, sort: ListingSort, after: Keyset?, count: Int): List<Hit> {
        val sql = Sql(
            """SELECT l.id, l.price_cents, l.created_at
               FROM listings l JOIN users s ON s.id = l.seller_id
               WHERE l.campus_domain = ? AND l.status = 'ACTIVE' AND $VISIBLE_TO_VIEWER""",
            viewer.campusDomain, viewer.id,
        )
        sql.filters(p).after(sort, after).add(" ORDER BY ${order(sort)} LIMIT ?", count)
        return queryFreshPlan(sql.toString(), *sql.paramsArray()) { it.hit(sort, 0f) }
    }

    /**
     * The first [count] visible listings matching [match] after [after]. With [ListingSort.RELEVANCE] the match's
     * classes come one after the other, each newest first; otherwise all matches are one class in [sort] order.
     *
     * No statistic tells how many listings a given text matches, and the two ways of finding them fail in opposite
     * cases: reading the campus in order until enough rows match is quick for a common text and reads everything for a
     * rare one; fetching all matches through an index and sorting them is the reverse. So each class is first looked
     * for among the next [WALK_WINDOW] listings in order, and only if that doesn't fill the page (the text is rare, at
     * least lately) are its matches fetched through the index.
     */
    private fun Connection.textSearch(viewer: UserPrincipal, p: SearchParams, match: TextMatch, sort: ListingSort, after: Keyset?, count: Int): List<Hit> {
        val ranked = sort == ListingSort.RELEVANCE
        val order = order(if (ranked) ListingSort.NEWEST else sort)
        val tiers = if (ranked) match.ranked(viewer.campusDomain) else listOf(match.any(viewer.campusDomain))
        val hits = ArrayList<Hit>(count)
        var windowIsPartOfCampus: Boolean? = null
        for (tier in tiers) {
            val need = count - hits.size
            if (need <= 0) break
            if (ranked && after != null && tier.rank > after.rank) continue
            val start = after?.takeIf { !ranked || it.rank == tier.rank }

            fun fetch(indexed: Boolean): List<Hit> {
                val sql = Sql("SELECT l.id, l.price_cents, l.created_at FROM (SELECT l.id, l.price_cents, l.created_at, l.seller_id")
                if (indexed) {
                    // `|| ''` keeps the planner off the campus and status indexes: this is for the rows the text index finds
                    sql.add(" FROM listings l WHERE l.campus_domain || '' = ? AND l.status || '' = 'ACTIVE'", viewer.campusDomain)
                    sql.filters(p).after(sort, start).add(" AND ${tier.indexed.sql} ORDER BY $order) l", *tier.indexed.params)
                } else {
                    sql.add(", l.title, l.description, l.search FROM listings l WHERE l.campus_domain = ? AND l.status = 'ACTIVE'", viewer.campusDomain)
                    sql.filters(p).after(sort, start).add(" ORDER BY $order LIMIT $WALK_WINDOW) l")
                }
                // the sellers are only looked up for the matches, in order, until the page is full
                sql.add(" JOIN users s ON s.id = l.seller_id WHERE $VISIBLE_TO_VIEWER", viewer.id)
                if (!indexed) sql.add(" AND ${tier.cheap.sql}", *tier.cheap.params)
                sql.add(" ORDER BY $order LIMIT $need")
                return queryFreshPlan(sql.toString(), *sql.paramsArray()) { it.hit(sort, tier.rank) }
            }

            var rows = fetch(indexed = false)
            if (rows.size < need) {
                // the walk stopped at the window, not at the page size; unless the window was the whole campus, it may have missed rows
                val partial = windowIsPartOfCampus ?: windowIsPartOfCampus(viewer, p).also { windowIsPartOfCampus = it }
                if (partial) rows = fetch(indexed = true)
            }
            hits += rows
        }
        return hits
    }

    /** False when the campus has fewer listings (passing the filters) than a walk reads, so that every walk saw them all. */
    private fun Connection.windowIsPartOfCampus(viewer: UserPrincipal, p: SearchParams): Boolean {
        val sql = Sql("SELECT count(*) FROM (SELECT 1 FROM listings l WHERE l.campus_domain = ? AND l.status = 'ACTIVE'", viewer.campusDomain)
        sql.filters(p).add(" LIMIT $WALK_WINDOW) w")
        return queryFreshPlan(sql.toString(), *sql.paramsArray()) { it.getInt(1) }.single() >= WALK_WINDOW
    }

    private fun Sql.filters(p: SearchParams): Sql {
        if (p.categories.isNotEmpty()) add(" AND l.category = ANY(?)", p.categories.toList())
        if (p.conditions.isNotEmpty()) add(" AND l.condition = ANY(?)", p.conditions.toList())
        p.minPriceCents?.let { add(" AND l.price_cents >= ?", it) }
        p.maxPriceCents?.let { add(" AND l.price_cents <= ?", it) }
        return this
    }

    /** Keyset condition for the rows after [after] in [sort] order ([ListingSort.RELEVANCE]: within one class, newest first). */
    private fun Sql.after(sort: ListingSort, after: Keyset?): Sql {
        if (after == null) return this
        val ts = after.createdAt.toString()
        return when (sort) {
            ListingSort.NEWEST, ListingSort.RELEVANCE -> add(" AND (l.created_at, l.id) < (?::timestamptz, ?)", ts, after.id)
            ListingSort.PRICE_ASC -> add(
                " AND (l.price_cents > ? OR (l.price_cents = ? AND (l.created_at, l.id) < (?::timestamptz, ?)))",
                after.priceCents, after.priceCents, ts, after.id,
            )
            ListingSort.PRICE_DESC -> add(" AND (l.price_cents, l.created_at, l.id) < (?, ?::timestamptz, ?)", after.priceCents, ts, after.id)
        }
    }

    private fun order(sort: ListingSort): String = when (sort) {
        ListingSort.NEWEST, ListingSort.RELEVANCE -> "l.created_at DESC, l.id DESC"
        ListingSort.PRICE_ASC -> "l.price_cents ASC, l.created_at DESC, l.id DESC"
        ListingSort.PRICE_DESC -> "l.price_cents DESC, l.created_at DESC, l.id DESC"
    }

    private fun ResultSet.hit(sort: ListingSort, rank: Float): Hit =
        Hit(uuid("id"), Keyset(sort.name, instantMicros("created_at"), uuid("id"), getInt("price_cents"), rank))

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
        val hits = c.query(sql) { rs ->
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
        val hits = query(sql) { rs ->
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
