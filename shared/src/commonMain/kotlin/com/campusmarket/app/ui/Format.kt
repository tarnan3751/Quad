package com.campusmarket.app.ui

import com.campusmarket.core.formatPrice
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** Shared display formatting. Keep all date / money formatting here so screens agree. */
object Format {
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun price(cents: Int, currency: String = "usd"): String = formatPrice(cents, currency)

    /** "just now", "5m", "3h", "2d", then "Mar 4" (this year) or "Mar 4, 2025". */
    fun relativeTime(epochMillis: Long, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): String {
        val diff = (nowMillis - epochMillis).coerceAtLeast(0)
        val minutes = diff / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m"
            minutes < 60 * 24 -> "${minutes / 60}h"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d"
            else -> date(epochMillis, nowMillis)
        }
    }

    /** "Mar 4" this year, "Mar 4, 2025" otherwise. */
    fun date(epochMillis: Long, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): String {
        val tz = TimeZone.currentSystemDefault()
        val d = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(tz)
        val thisYear = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(tz).year
        val base = "${MONTHS[d.month.ordinal]} ${d.day}"
        return if (d.year == thisYear) base else "$base, ${d.year}"
    }

    /** "Mar 4, 2025 · 14:05" */
    fun dateTime(epochMillis: Long): String {
        val d = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
        val time = "${d.hour.toString().padStart(2, '0')}:${d.minute.toString().padStart(2, '0')}"
        return "${MONTHS[d.month.ordinal]} ${d.day}, ${d.year} · $time"
    }

    /** "Joined Mar 2025" */
    fun memberSince(epochMillis: Long): String {
        val d = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
        return "Joined ${MONTHS[d.month.ordinal]} ${d.year}"
    }

    /** "4.8" or null when there are no ratings. */
    fun rating(average: Double?): String? = average?.let {
        val tenths = kotlin.math.round(it * 10).toInt()
        "${tenths / 10}.${tenths % 10}"
    }

    /**
     * Parses user price input ("12", "12.5", "$1,200.00") to cents. Null if not a valid non-negative amount with
     * at most 2 decimals. Range checks against `Limits.PRICE_*` are the caller's job.
     */
    fun parsePriceCents(input: String): Int? {
        val clean = input.trim().removePrefix("$").replace(",", "").trim()
        if (clean.isEmpty()) return null
        val match = Regex("^(\\d{1,7})(?:\\.(\\d{0,2}))?$").matchEntire(clean) ?: return null
        val whole = match.groupValues[1].toLong()
        val frac = match.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong()
        val cents = whole * 100 + frac
        return if (cents > Int.MAX_VALUE) null else cents.toInt()
    }

    /** Cents → editable text without symbol: 1250 → "12.50", 1200 → "12". */
    fun priceInput(cents: Int): String =
        if (cents % 100 == 0) (cents / 100).toString() else "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}
