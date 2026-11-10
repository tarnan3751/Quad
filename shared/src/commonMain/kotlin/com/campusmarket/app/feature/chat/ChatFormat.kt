package com.campusmarket.app.feature.chat

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** Chat-specific formatting (day grouping, inbox previews). Clock times live in [TimeFormat]. */
internal object ChatFormat {
    private const val PREVIEW_MAX = 120

    private fun localDate(epochMillis: Long, tz: TimeZone): LocalDate =
        Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(tz).date

    fun sameDay(a: Long, b: Long, tz: TimeZone = TimeZone.currentSystemDefault()): Boolean = localDate(a, tz) == localDate(b, tz)

    /** "Today", "Yesterday", "Wednesday" (last week), then "Mar 4" / "Mar 4, 2025". */
    fun dayLabel(
        epochMillis: Long,
        nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
        tz: TimeZone = TimeZone.currentSystemDefault(),
    ): String = TimeFormat(use24h = false, tz = tz, now = { nowMillis }).dayLabel(epochMillis)

    /** Same shape as the server's `lastMessagePreview`: whitespace collapsed, at most 120 characters. */
    fun preview(body: String): String {
        val flat = body.trim().replace(Regex("\\s+"), " ")
        return if (flat.length <= PREVIEW_MAX) flat else flat.take(PREVIEW_MAX - 1).trimEnd() + "…"
    }
}
