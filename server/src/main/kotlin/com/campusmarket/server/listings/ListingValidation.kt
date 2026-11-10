package com.campusmarket.server.listings

import com.campusmarket.core.CreateListingRequest
import com.campusmarket.core.Limits
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.UpdateListingRequest
import com.campusmarket.core.formatPrice
import com.campusmarket.server.badRequest
import com.campusmarket.server.query
import com.campusmarket.server.withoutInvisibleFormatChars
import java.sql.Connection
import java.text.Normalizer
import java.util.UUID

/** A create/update request after normalisation and validation; null fields mean "unchanged" (update only). */
internal data class ListingInput(
    val title: String? = null,
    val description: String? = null,
    val category: ListingCategory? = null,
    val condition: ListingCondition? = null,
    val priceCents: Int? = null,
    val pickupLocation: String? = null,
    val photoIds: List<UUID>? = null,
) {
    val isEmpty: Boolean
        get() = title == null && description == null && category == null && condition == null &&
            priceCents == null && pickupLocation == null && photoIds == null
}

/**
 * Validation against [Limits]. All problems are collected and reported together as a 400 `VALIDATION`
 * with one message per field (keys are the request's JSON property names).
 */
internal object ListingValidation {
    private val whitespaceRun = Regex("[\\s\\p{Cc}]+")
    private val controlExceptNewlineTab = Regex("[\\p{Cc}&&[^\\n\\t]]")

    fun forCreate(c: Connection, sellerId: UUID, r: CreateListingRequest): ListingInput =
        validate(c, sellerId, r.title, r.description, r.category, r.condition, r.priceCents, r.pickupLocation, r.photoIds)

    fun forUpdate(c: Connection, sellerId: UUID, r: UpdateListingRequest): ListingInput =
        validate(c, sellerId, r.title, r.description, r.category, r.condition, r.priceCents, r.pickupLocation, r.photoIds)

    private fun validate(
        c: Connection,
        sellerId: UUID,
        rawTitle: String?,
        rawDescription: String?,
        category: ListingCategory?,
        condition: ListingCondition?,
        priceCents: Int?,
        rawPickup: String?,
        rawPhotoIds: List<String>?,
    ): ListingInput {
        val errors = linkedMapOf<String, String>()

        val title = rawTitle?.let(::singleLine)
        if (title != null && title.length !in Limits.TITLE_MIN..Limits.TITLE_MAX) {
            errors["title"] = "Title must be ${Limits.TITLE_MIN}–${Limits.TITLE_MAX} characters"
        }
        val description = rawDescription?.let(::multiLine)
        if (description != null && description.length > Limits.DESCRIPTION_MAX) {
            errors["description"] = "Description must be at most ${Limits.DESCRIPTION_MAX} characters"
        }
        if (priceCents != null && priceCents !in Limits.PRICE_MIN_CENTS..Limits.PRICE_MAX_CENTS) {
            errors["priceCents"] = "Price must be between ${formatPrice(Limits.PRICE_MIN_CENTS)} and ${formatPrice(Limits.PRICE_MAX_CENTS)}"
        }
        val pickup = rawPickup?.let(::singleLine)
        if (pickup != null && pickup.length > Limits.PICKUP_MAX) {
            errors["pickupLocation"] = "Pickup location must be at most ${Limits.PICKUP_MAX} characters"
        }
        val photoIds = rawPhotoIds?.let { validatePhotos(c, sellerId, it, errors) }

        if (errors.isNotEmpty()) badRequest(errors.values.first(), errors)
        return ListingInput(title, description, category, condition, priceCents, pickup, photoIds)
    }

    private fun validatePhotos(c: Connection, sellerId: UUID, raw: List<String>, errors: MutableMap<String, String>): List<UUID>? {
        if (raw.size !in Limits.PHOTOS_MIN..Limits.PHOTOS_MAX) {
            errors["photoIds"] = "Add ${Limits.PHOTOS_MIN}–${Limits.PHOTOS_MAX} photos"
            return null
        }
        val ids = raw.map { s -> runCatching { UUID.fromString(s.trim()) }.getOrNull() }
        if (ids.any { it == null }) {
            errors["photoIds"] = "Some photos could not be found; upload them again"
            return null
        }
        val uuids = ids.filterNotNull()
        if (uuids.toSet().size != uuids.size) {
            errors["photoIds"] = "Each photo can only be added once"
            return null
        }
        val owned = c.query("SELECT id FROM photos WHERE owner_id = ? AND id = ANY(?)", sellerId, uuids) { it.getObject(1, UUID::class.java) }.toSet()
        if (owned.size != uuids.size) {
            errors["photoIds"] = "Some photos could not be found; upload them again"
            return null
        }
        return uuids
    }

    /**
     * Titles and pickup locations: one line, single spaces, no control characters, and no invisible formatting
     * characters (bidi overrides, zero-width spaces…) that could disguise what other people see. NFC, so that
     * searches typed on any keyboard match.
     */
    fun singleLine(s: String): String = visible(s).replace(whitespaceRun, " ").trim()

    /** Descriptions: keep line breaks and tabs, normalise CRLF, drop other control and invisible formatting characters. */
    fun multiLine(s: String): String = visible(s).replace("\r\n", "\n").replace('\r', '\n').replace(controlExceptNewlineTab, "").trim()

    private fun visible(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC).withoutInvisibleFormatChars()
}
