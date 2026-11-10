package com.campusmarket.app.ui

import com.campusmarket.core.formatPrice

/** Shared money and rating formatting, so screens agree. Dates and times are in [TimeFormat]. */
object Format {
    fun price(cents: Int, currency: String = "usd"): String = formatPrice(cents, currency)

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
