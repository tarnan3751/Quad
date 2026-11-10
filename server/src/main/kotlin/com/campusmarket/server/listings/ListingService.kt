package com.campusmarket.server.listings

import com.campusmarket.core.CreateListingRequest
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.Photo
import com.campusmarket.core.UpdateListingRequest
import com.campusmarket.server.AppConfig
import com.campusmarket.server.AppDeps
import com.campusmarket.server.Sql
import com.campusmarket.server.UserPrincipal
import com.campusmarket.server.UserThrottle
import com.campusmarket.server.conflict
import com.campusmarket.server.forbidden
import com.campusmarket.server.intOrNull
import com.campusmarket.server.loadUserPublic
import com.campusmarket.server.millis
import com.campusmarket.server.notFound
import com.campusmarket.server.photoUrl
import com.campusmarket.server.query
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import com.campusmarket.server.uuid
import java.sql.Connection
import java.util.UUID

/** Statuses anyone on the listing's campus may see; REMOVED/DELETED are seller- and moderator-only. */
internal val PUBLIC_STATUSES = setOf(ListingStatus.ACTIVE, ListingStatus.RESERVED, ListingStatus.SOLD)

/**
 * Browse-side filter on the seller (`users s`): hides listings of deleted accounts and of currently
 * suspended sellers (a timed suspension that has expired no longer counts).
 */
internal const val SELLER_IN_GOOD_STANDING =
    "s.deleted_at IS NULL AND NOT (s.suspended AND (s.suspended_until IS NULL OR s.suspended_until > now()))"

internal data class ListingRow(
    val id: UUID,
    val sellerId: UUID,
    val campusDomain: String,
    val title: String,
    val description: String,
    val category: ListingCategory,
    val condition: ListingCondition,
    val priceCents: Int,
    val currency: String,
    val status: ListingStatus,
    val pickupLocation: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** GET /listings/{id} visibility: same campus for public statuses; the seller and moderators see everything. */
    fun isVisibleTo(viewer: UserPrincipal): Boolean = when {
        sellerId == viewer.id -> true
        viewer.isModerator -> true
        campusDomain != viewer.campusDomain -> false
        else -> status in PUBLIC_STATUSES
    }
}

internal fun Connection.findListing(id: UUID, forUpdate: Boolean = false): ListingRow? = queryOne(
    """SELECT id, seller_id, campus_domain, title, description, category, condition, price_cents, currency, status,
              pickup_location, created_at, updated_at
       FROM listings WHERE id = ?""" + if (forUpdate) " FOR UPDATE" else "",
    id,
) { rs ->
    ListingRow(
        id = rs.uuid("id"),
        sellerId = rs.uuid("seller_id"),
        campusDomain = rs.getString("campus_domain"),
        title = rs.getString("title"),
        description = rs.getString("description"),
        category = ListingCategory.valueOf(rs.getString("category")),
        condition = ListingCondition.valueOf(rs.getString("condition")),
        priceCents = rs.getInt("price_cents"),
        currency = rs.getString("currency"),
        status = ListingStatus.valueOf(rs.getString("status")),
        pickupLocation = rs.getString("pickup_location"),
        createdAt = rs.millis("created_at"),
        updatedAt = rs.millis("updated_at"),
    )
}

internal fun Connection.renderDetail(config: AppConfig, row: ListingRow, viewerId: UUID): ListingDetail {
    val photos = query(
        """SELECT p.id, p.storage_key, p.width, p.height, p.thumb_key, p.display_key
           FROM listing_photos lp JOIN photos p ON p.id = lp.photo_id
           WHERE lp.listing_id = ? ORDER BY lp.position""",
        row.id,
    ) { rs ->
        Photo(
            id = rs.uuid("id").toString(),
            url = config.photoUrl(rs.getString("storage_key")),
            width = rs.intOrNull("width"),
            height = rs.intOrNull("height"),
            thumbnailUrl = rs.getString("thumb_key")?.let(config::photoUrl),
            displayUrl = rs.getString("display_key")?.let(config::photoUrl),
        )
    }
    val seller = loadUserPublic(config, row.sellerId, viewerId) ?: error("Seller ${row.sellerId} of listing ${row.id} is missing")
    val (favoriteCount, favorited, sellerBlockedViewer) = queryOne(
        """SELECT count(*), coalesce(bool_or(user_id = ?), false), EXISTS (SELECT 1 FROM blocks WHERE blocker_id = ? AND blocked_id = ?)
           FROM favorites WHERE listing_id = ?""",
        viewerId, row.sellerId, viewerId, row.id,
    ) { Triple(it.getInt(1), it.getBoolean(2), it.getBoolean(3)) }!!
    return ListingDetail(
        id = row.id.toString(),
        title = row.title,
        description = row.description,
        priceCents = row.priceCents,
        currency = row.currency,
        category = row.category,
        condition = row.condition,
        status = row.status,
        pickupLocation = row.pickupLocation,
        photos = photos,
        seller = seller,
        favorited = favorited,
        favoriteCount = favoriteCount,
        isMine = row.sellerId == viewerId,
        createdAt = row.createdAt,
        updatedAt = row.updatedAt,
        blocked = row.sellerId != viewerId && (seller.blockedByMe || sellerBlockedViewer),
    )
}

/** Listing writes and single-listing reads. */
internal class ListingService(private val deps: AppDeps) {
    private val config get() = deps.config

    suspend fun create(viewer: UserPrincipal, req: CreateListingRequest): ListingDetail = deps.db.tx { c ->
        val input = ListingValidation.forCreate(c, viewer.id, req)
        deps.throttle.acquire(UserThrottle.Action.LISTING, viewer.id, "You've posted a lot of listings recently. Try again later.")
        val id = UUID.randomUUID()
        c.update(
            """INSERT INTO listings (id, seller_id, campus_domain, title, description, category, condition, price_cents,
                                     pickup_location, status)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')""",
            id, viewer.id, viewer.campusDomain, input.title, input.description, input.category, input.condition,
            input.priceCents, input.pickupLocation,
        )
        c.insertPhotos(id, input.photoIds!!)
        c.renderDetail(config, c.findListing(id)!!, viewer.id)
    }

    suspend fun get(viewer: UserPrincipal, id: UUID): ListingDetail = deps.db.tx { c ->
        val row = c.findListing(id)?.takeIf { it.isVisibleTo(viewer) } ?: notFound("Listing")
        c.renderDetail(config, row, viewer.id)
    }

    suspend fun update(viewer: UserPrincipal, id: UUID, req: UpdateListingRequest): ListingDetail = deps.db.tx { c ->
        val row = c.lockOwnListing(viewer, id)
        if (row.status != ListingStatus.ACTIVE) conflict("Only active listings can be edited")
        val input = ListingValidation.forUpdate(c, viewer.id, req)
        if (!input.isEmpty) {
            val sql = Sql("UPDATE listings SET updated_at = now()")
            input.title?.let { sql.add(", title = ?", it) }
            input.description?.let { sql.add(", description = ?", it) }
            input.category?.let { sql.add(", category = ?", it) }
            input.condition?.let { sql.add(", condition = ?", it) }
            input.priceCents?.let { sql.add(", price_cents = ?", it) }
            input.pickupLocation?.let { sql.add(", pickup_location = ?", it) }
            sql.add(" WHERE id = ? AND status = 'ACTIVE'", id)
            check(c.update(sql.toString(), *sql.paramsArray()) == 1) { "Listing $id changed while locked" }
            input.photoIds?.let { photoIds ->
                c.update("DELETE FROM listing_photos WHERE listing_id = ?", id)
                c.insertPhotos(id, photoIds)
            }
        }
        c.renderDetail(config, c.findListing(id)!!, viewer.id)
    }

    suspend fun delete(viewer: UserPrincipal, id: UUID): Unit = deps.db.tx { c ->
        val row = c.lockOwnListing(viewer, id)
        when (row.status) {
            ListingStatus.RESERVED -> conflict("This listing has an order in progress and can't be deleted")
            ListingStatus.SOLD -> conflict("Sold listings can't be deleted")
            ListingStatus.DELETED -> Unit // idempotent
            ListingStatus.ACTIVE, ListingStatus.REMOVED -> {
                c.update("UPDATE listings SET status = 'DELETED', updated_at = now() WHERE id = ? AND status IN ('ACTIVE', 'REMOVED')", id)
            }
        }
    }

    /** Favorites are for listings the caller can browse to: same campus and not taken down. */
    suspend fun favorite(viewer: UserPrincipal, id: UUID): Unit = deps.db.tx { c ->
        val row = c.findListing(id) ?: notFound("Listing")
        if (row.campusDomain != viewer.campusDomain || row.status !in PUBLIC_STATUSES) notFound("Listing")
        c.update("INSERT INTO favorites (user_id, listing_id) VALUES (?, ?) ON CONFLICT DO NOTHING", viewer.id, id)
    }

    /** Un-favoriting works whatever the listing's status, so stale favorites can always be cleared. */
    suspend fun unfavorite(viewer: UserPrincipal, id: UUID): Unit = deps.db.tx { c ->
        val row = c.findListing(id) ?: notFound("Listing")
        if (row.campusDomain != viewer.campusDomain) notFound("Listing")
        c.update("DELETE FROM favorites WHERE user_id = ? AND listing_id = ?", viewer.id, id)
    }

    /** Locks the listing for a seller-only change: 404 if the caller can't see it, 403 if it isn't theirs. */
    private fun Connection.lockOwnListing(viewer: UserPrincipal, id: UUID): ListingRow {
        val row = findListing(id, forUpdate = true)?.takeIf { it.isVisibleTo(viewer) } ?: notFound("Listing")
        if (row.sellerId != viewer.id) forbidden("Only the seller can change this listing")
        return row
    }

    private fun Connection.insertPhotos(listingId: UUID, photoIds: List<UUID>) {
        update(
            """INSERT INTO listing_photos (listing_id, photo_id, position)
               SELECT ?, p.id, (p.ord - 1)::int FROM unnest(?::uuid[]) WITH ORDINALITY AS p(id, ord)""",
            listingId, photoIds,
        )
    }
}
