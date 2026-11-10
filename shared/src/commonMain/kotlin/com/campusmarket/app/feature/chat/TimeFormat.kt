package com.campusmarket.app.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** True when the device shows a 24-hour clock (the system setting where there is one, else the locale's convention). */
@Composable
internal expect fun deviceUses24HourClock(): Boolean

/** Overrides [deviceUses24HourClock] (tests, previews). Null follows the device. */
val LocalUse24HourClock = staticCompositionLocalOf<Boolean?> { null }

/** The [TimeFormat] for the device's clock preference. */
@Composable
fun rememberTimeFormat(): TimeFormat {
    val use24h = LocalUse24HourClock.current ?: deviceUses24HourClock()
    return remember(use24h) { TimeFormat(use24h) }
}

/**
 * Times and dates as people say them, on the device's 12- or 24-hour clock: "2:05 PM" / "14:05", "Yesterday",
 * "Thu, Nov 12 at 9:24 AM", "in 2 days, 4 hours". Pure; [now] and [tz] are injectable for tests.
 */
class TimeFormat(
    val use24h: Boolean,
    private val tz: TimeZone = TimeZone.currentSystemDefault(),
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private fun local(epochMillis: Long): LocalDateTime = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(tz)
    private fun today(): LocalDate = local(now()).date

    /** "2:05 PM" or "14:05". */
    fun time(epochMillis: Long): String = clock(local(epochMillis))

    private fun clock(t: LocalDateTime): String {
        val mm = t.minute.toString().padStart(2, '0')
        if (use24h) return "${t.hour.toString().padStart(2, '0')}:$mm"
        val h = if (t.hour % 12 == 0) 12 else t.hour % 12
        return "$h:$mm ${if (t.hour < 12) "AM" else "PM"}"
    }

    /** Inbox row stamp: the time today, "Yesterday", the weekday this week, then "Nov 4" / "Nov 4, 2025". */
    fun inboxStamp(epochMillis: Long): String {
        val t = local(epochMillis)
        val today = today()
        return when {
            t.date >= today -> clock(t)
            t.date == today.minus(DatePeriod(days = 1)) -> "Yesterday"
            t.date > today.minus(DatePeriod(days = 7)) -> WEEKDAYS_SHORT[t.date.dayOfWeek.ordinal]
            t.date.year == today.year -> monthDay(t.date)
            else -> "${monthDay(t.date)}, ${t.date.year}"
        }
    }

    /** Day separator in a conversation: "Today", "Yesterday", "Wednesday", then "Nov 4" / "Nov 4, 2025". */
    fun dayLabel(epochMillis: Long): String {
        val d = local(epochMillis).date
        val today = today()
        return when {
            d == today -> "Today"
            d == today.minus(DatePeriod(days = 1)) -> "Yesterday"
            d > today.minus(DatePeriod(days = 7)) && d < today -> WEEKDAYS[d.dayOfWeek.ordinal]
            d.year == today.year -> monthDay(d)
            else -> "${monthDay(d)}, ${d.year}"
        }
    }

    /** "Today at 2:05 PM", "Yesterday at 9:00 AM", "Tomorrow at 9:24 AM", "Thu, Nov 12 at 9:24 AM", "Nov 12, 2025 at 9:24 AM". */
    fun dateTime(epochMillis: Long): String {
        val t = local(epochMillis)
        val today = today()
        val day = when {
            t.date == today -> "Today"
            t.date == today.minus(DatePeriod(days = 1)) -> "Yesterday"
            t.date == today.minus(DatePeriod(days = -1)) -> "Tomorrow"
            t.date.year == today.year -> "${WEEKDAYS_SHORT[t.date.dayOfWeek.ordinal]}, ${monthDay(t.date)}"
            else -> "${monthDay(t.date)}, ${t.date.year}"
        }
        return "$day at ${clock(t)}"
    }

    /** "Nov 12" this year, "Nov 12, 2025" otherwise. */
    fun date(epochMillis: Long): String {
        val d = local(epochMillis).date
        return if (d.year == today().year) monthDay(d) else "${monthDay(d)}, ${d.year}"
    }

    /** Time left until [epochMillis]: "in 2 days, 4 hours", "in 5 hours", "in 1 hour, 20 min", "in 12 min", "any moment now". */
    fun countdown(epochMillis: Long): String {
        val left = epochMillis - now()
        if (left < MINUTE) return "any moment now"
        val days = left / DAY
        val hours = (left % DAY) / HOUR
        val minutes = (left % HOUR + MINUTE - 1) / MINUTE // round up so "in 0 min" never shows
        return when {
            days > 0 -> "in ${plural(days, "day")}" + if (hours > 0) ", ${plural(hours, "hour")}" else ""
            hours >= 3 -> "in ${plural(hours, "hour")}"
            hours > 0 -> "in ${plural(hours, "hour")}" + if (minutes in 1..59) ", $minutes min" else ""
            else -> "in ${minutes.coerceAtMost(59)} min"
        }
    }

    private fun monthDay(d: LocalDate) = "${MONTHS[d.month.ordinal]} ${d.day}"

    private fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        val WEEKDAYS_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }
}
