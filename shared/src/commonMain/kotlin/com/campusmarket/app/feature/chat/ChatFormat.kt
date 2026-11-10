package com.campusmarket.app.feature.chat

import com.campusmarket.app.ui.Format
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** Chat-specific formatting (day separators, bubble times, inbox previews). */
internal object ChatFormat {
    private const val PREVIEW_MAX = 120

    private fun localDate(epochMillis: Long, tz: TimeZone): LocalDate =
        Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(tz).date

    fun sameDay(a: Long, b: Long, tz: TimeZone = TimeZone.currentSystemDefault()): Boolean = localDate(a, tz) == localDate(b, tz)

    /** "14:05" */
    fun time(epochMillis: Long, tz: TimeZone = TimeZone.currentSystemDefault()): String {
        val t = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(tz)
        return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    }

    /** "Today", "Yesterday", "Wednesday" (last week), then "Mar 4" / "Mar 4, 2025". */
    fun dayLabel(
        epochMillis: Long,
        nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
        tz: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val day = localDate(epochMillis, tz)
        val today = localDate(nowMillis, tz)
        return when {
            day == today -> "Today"
            day == today.minus(DatePeriod(days = 1)) -> "Yesterday"
            day > today.minus(DatePeriod(days = 7)) && day < today -> day.dayOfWeek.displayName()
            else -> Format.date(epochMillis, nowMillis)
        }
    }

    /** Same shape as the server's `lastMessagePreview`: whitespace collapsed, at most 120 characters. */
    fun preview(body: String): String {
        val flat = body.trim().replace(Regex("\\s+"), " ")
        return if (flat.length <= PREVIEW_MAX) flat else flat.take(PREVIEW_MAX - 1).trimEnd() + "…"
    }

    private fun DayOfWeek.displayName(): String = name.lowercase().replaceFirstChar { it.uppercase() }
}
