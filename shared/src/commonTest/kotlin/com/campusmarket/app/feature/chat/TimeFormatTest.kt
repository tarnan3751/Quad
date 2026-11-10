package com.campusmarket.app.feature.chat

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeFormatTest {
    private val tz = TimeZone.of("America/New_York")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime(y, mo, d, h, mi).toInstant(tz).toEpochMilliseconds()

    // Tue, Nov 10, 2026 at 3:30 PM
    private val now = at(2026, 11, 10, 15, 30)
    private val us = TimeFormat(use24h = false, tz = tz, now = { now })
    private val eu = TimeFormat(use24h = true, tz = tz, now = { now })

    @Test
    fun timeFollowsTheClockPreference() {
        assertEquals("2:05 PM", us.time(at(2026, 11, 10, 14, 5)))
        assertEquals("14:05", eu.time(at(2026, 11, 10, 14, 5)))
        assertEquals("12:00 AM", us.time(at(2026, 11, 10, 0, 0)))
        assertEquals("12:30 PM", us.time(at(2026, 11, 10, 12, 30)))
        assertEquals("09:07", eu.time(at(2026, 11, 10, 9, 7)))
    }

    @Test
    fun inboxStampsLikeAMessenger() {
        assertEquals("9:15 AM", us.inboxStamp(at(2026, 11, 10, 9, 15)))
        assertEquals("Yesterday", us.inboxStamp(at(2026, 11, 9, 22, 0)))
        assertEquals("Thu", us.inboxStamp(at(2026, 11, 5, 8, 0)))
        assertEquals("Nov 3", us.inboxStamp(at(2026, 11, 3, 8, 0)))
        assertEquals("Dec 30, 2025", us.inboxStamp(at(2025, 12, 30, 8, 0)))
    }

    @Test
    fun dayLabelsAndDateTimes() {
        assertEquals("Today", us.dayLabel(at(2026, 11, 10, 1, 0)))
        assertEquals("Yesterday", us.dayLabel(at(2026, 11, 9, 23, 59)))
        assertEquals("Saturday", us.dayLabel(at(2026, 11, 7, 12, 0)))
        assertEquals("Oct 2", us.dayLabel(at(2026, 10, 2, 12, 0)))
        assertEquals("Today at 9:24 AM", us.dateTime(at(2026, 11, 10, 9, 24)))
        assertEquals("Tomorrow at 21:00", eu.dateTime(at(2026, 11, 11, 21, 0)))
        assertEquals("Thu, Nov 12 at 9:24 AM", us.dateTime(at(2026, 11, 12, 9, 24)))
        assertEquals("Mar 4, 2025 at 8:00 PM", us.dateTime(at(2025, 3, 4, 20, 0)))
    }

    @Test
    fun countdowns() {
        val min = 60_000L
        val hour = 60 * min
        assertEquals("in 2 days, 4 hours", us.countdown(now + 52 * hour + 10 * min))
        assertEquals("in 3 days", us.countdown(now + 72 * hour))
        assertEquals("in 20 hours", us.countdown(now + 20 * hour + 59 * min))
        assertEquals("in 1 hour, 20 min", us.countdown(now + hour + 20 * min))
        assertEquals("in 12 min", us.countdown(now + 11 * min + 30_000))
        assertEquals("any moment now", us.countdown(now + 20_000))
        assertEquals("any moment now", us.countdown(now - hour))
        assertEquals("in 2 days", us.countdownShort(now + 52 * hour))
        assertEquals("in 1 hour", us.countdownShort(now + hour + 20 * min))
        assertEquals("in 12 min", us.countdownShort(now + 11 * min + 30_000))
    }

    @Test
    fun inlineDateTimesReadInASentence() {
        assertEquals("today at 9:24 AM", us.dateTimeInline(at(2026, 11, 10, 9, 24)))
        assertEquals("on Thu, Nov 12 at 9:24 AM", us.dateTimeInline(at(2026, 11, 12, 9, 24)))
        assertEquals("Nov 12", us.date(at(2026, 11, 12, 9, 24)))
        assertEquals("Jan 2, 2027", us.date(at(2027, 1, 2, 9, 24)))
    }
}
