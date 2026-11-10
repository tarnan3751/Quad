package com.campusmarket.server

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserPublic
import com.campusmarket.core.UserRole
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/*
 * Shared read-side mappers used by several features. Keep DTO assembly for users and listing
 * summaries here so every endpoint renders them identically. Each loader is one query, whatever the batch size.
 */

fun AppConfig.photoUrl(storageKey: String): String = "$publicBaseUrl${ApiRoutes.UPLOADS}/$storageKey"

private fun AppConfig.photoUrlOrNull(storageKey: String?): String? = storageKey?.let(::photoUrl)

fun ratingAverage(sum: Int, count: Int): Double? = if (count == 0) null else Math.round(sum * 10.0 / count) / 10.0

/** Users `u` with their avatar photo; select [USER_COLS]. */
private const val USERS_WITH_AVATAR = "users u LEFT JOIN photos av ON av.id = u.avatar_photo_id"

private const val USER_COLS = """u.id, u.email, u.display_name, u.bio, u.campus_domain, u.role, u.rating_sum, u.rating_count,
    u.payouts_enabled, u.created_at, u.deleted_at, av.storage_key AS avatar_key, av.thumb_key AS avatar_thumb_key"""

private fun ResultSet.toUserPublic(config: AppConfig): UserPublic {
    val deleted = getTimestamp("deleted_at") != null
    return UserPublic(
        id = uuid("id").toString(),
        displayName = if (deleted) "Deleted user" else getString("display_name"),
        bio = if (deleted) "" else getString("bio"),
        avatarUrl = if (deleted) null else config.photoUrlOrNull(getString("avatar_key")),
        campusDomain = getString("campus_domain"),
        ratingAverage = ratingAverage(getInt("rating_sum"), getInt("rating_count")),
        ratingCount = getInt("rating_count"),
        joinedAt = millis("created_at"),
        blockedByMe = getBoolean("blocked_by_me"),
        avatarThumbnailUrl = if (deleted) null else config.photoUrlOrNull(getString("avatar_thumb_key")),
    )
}

/** Public profiles for [ids] (including soft-deleted users, rendered as "Deleted user"). [viewerId] fills `blockedByMe`. */
fun Connection.loadUsersPublic(config: AppConfig, ids: Collection<UUID>, viewerId: UUID? = null): Map<UUID, UserPublic> {
    if (ids.isEmpty()) return emptyMap()
    return query(
        """SELECT $USER_COLS, EXISTS (SELECT 1 FROM blocks b WHERE b.blocker_id = ? AND b.blocked_id = u.id) AS blocked_by_me
           FROM $USERS_WITH_AVATAR WHERE u.id = ANY(?)""",
        viewerId, ids.distinct(),
    ) { rs -> rs.uuid("id") to rs.toUserPublic(config) }.toMap()
}

fun Connection.loadUserPublic(config: AppConfig, id: UUID, viewerId: UUID? = null): UserPublic? =
    loadUsersPublic(config, listOf(id), viewerId)[id]

fun Connection.loadUserPrivate(config: AppConfig, id: UUID): UserPrivate? =
    queryOne("SELECT $USER_COLS FROM $USERS_WITH_AVATAR WHERE u.id = ? AND u.deleted_at IS NULL", id) { rs ->
        UserPrivate(
            id = rs.uuid("id").toString(),
            email = rs.getString("email"),
            displayName = rs.getString("display_name"),
            bio = rs.getString("bio"),
            avatarUrl = config.photoUrlOrNull(rs.getString("avatar_key")),
            campusDomain = rs.getString("campus_domain"),
            role = UserRole.valueOf(rs.getString("role")),
            ratingAverage = ratingAverage(rs.getInt("rating_sum"), rs.getInt("rating_count")),
            ratingCount = rs.getInt("rating_count"),
            payoutsEnabled = rs.getBoolean("payouts_enabled"),
            joinedAt = rs.millis("created_at"),
            avatarThumbnailUrl = config.photoUrlOrNull(rs.getString("avatar_thumb_key")),
        )
    }

/**
 * Listing summaries for [ids] regardless of status, keyed by id (callers re-order / filter as needed).
 * [viewerId] fills `favorited`.
 */
fun Connection.loadListingSummaries(config: AppConfig, ids: Collection<UUID>, viewerId: UUID? = null): Map<UUID, ListingSummary> {
    if (ids.isEmpty()) return emptyMap()
    return query(
        """SELECT l.id, l.title, l.price_cents, l.currency, l.category, l.condition, l.status, l.seller_id, l.created_at,
                  CASE WHEN s.deleted_at IS NULL THEN s.display_name ELSE 'Deleted user' END AS seller_name,
                  ph.storage_key, ph.thumb_key, ph.width, ph.height,
                  EXISTS (SELECT 1 FROM favorites f WHERE f.user_id = ? AND f.listing_id = l.id) AS favorited
           FROM listings l JOIN users s ON s.id = l.seller_id
           LEFT JOIN LATERAL (SELECT p.storage_key, p.thumb_key, p.width, p.height
                              FROM listing_photos lp JOIN photos p ON p.id = lp.photo_id
                              WHERE lp.listing_id = l.id ORDER BY lp.position LIMIT 1) ph ON true
           WHERE l.id = ANY(?)""",
        viewerId, ids.distinct(),
    ) { rs ->
        val id = rs.uuid("id")
        id to ListingSummary(
            id = id.toString(),
            title = rs.getString("title"),
            priceCents = rs.getInt("price_cents"),
            currency = rs.getString("currency"),
            category = ListingCategory.valueOf(rs.getString("category")),
            condition = ListingCondition.valueOf(rs.getString("condition")),
            status = ListingStatus.valueOf(rs.getString("status")),
            thumbnailUrl = config.photoUrlOrNull(rs.getString("storage_key")),
            sellerId = rs.uuid("seller_id").toString(),
            sellerName = rs.getString("seller_name"),
            favorited = rs.getBoolean("favorited"),
            createdAt = rs.millis("created_at"),
            thumbnailSmallUrl = config.photoUrlOrNull(rs.getString("thumb_key")),
            thumbnailWidth = rs.intOrNull("width"),
            thumbnailHeight = rs.intOrNull("height"),
        )
    }.toMap()
}

fun Connection.loadListingSummary(config: AppConfig, id: UUID, viewerId: UUID? = null): ListingSummary? =
    loadListingSummaries(config, listOf(id), viewerId)[id]

/** True if either user has blocked the other. */
fun Connection.isBlockedEitherWay(a: UUID, b: UUID): Boolean =
    queryOne(
        "SELECT 1 FROM blocks WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?) LIMIT 1",
        a, b, b, a,
    ) { true } ?: false
