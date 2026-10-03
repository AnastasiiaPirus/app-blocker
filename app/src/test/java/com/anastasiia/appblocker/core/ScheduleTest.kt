package com.anastasiia.appblocker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTest {
    private val weekdays = setOf(1, 2, 3, 4, 5)
    private val morning = ScheduleWindow(days = weekdays, startMinute = 8 * 60, endMinute = 11 * 60)
    // Listed on Monday; runs Mon 21:30 -> Tue 01:00.
    private val night = ScheduleWindow(days = setOf(1), startMinute = 21 * 60 + 30, endMinute = 60)

    @Test fun startIsInclusiveEndIsExclusive() {
        assertTrue(windowContains(morning, day = 1, minuteOfDay = 8 * 60))
        assertTrue(windowContains(morning, day = 1, minuteOfDay = 11 * 60 - 1))
        assertFalse(windowContains(morning, day = 1, minuteOfDay = 11 * 60))
        assertFalse(windowContains(morning, day = 1, minuteOfDay = 8 * 60 - 1))
    }

    @Test fun dayNotListedIsOutside() {
        assertFalse(windowContains(morning, day = 6, minuteOfDay = 9 * 60))
        assertFalse(windowContains(morning, day = 7, minuteOfDay = 9 * 60))
    }

    @Test fun crossingMidnightCoversLateStartDayAndEarlyNextDay() {
        assertTrue(windowContains(night, day = 1, minuteOfDay = 21 * 60 + 30))
        assertTrue(windowContains(night, day = 1, minuteOfDay = 23 * 60 + 59))
        assertTrue(windowContains(night, day = 2, minuteOfDay = 0))
        assertTrue(windowContains(night, day = 2, minuteOfDay = 59))
        assertFalse(windowContains(night, day = 2, minuteOfDay = 60))
        assertFalse(windowContains(night, day = 2, minuteOfDay = 22 * 60)) // Tue night not listed
        assertFalse(windowContains(night, day = 1, minuteOfDay = 30)) // Mon 00:30 belongs to Sunday's window
    }

    @Test fun sundayWindowWrapsIntoMonday() {
        val sunNight = night.copy(days = setOf(7))
        assertTrue(windowContains(sunNight, day = 7, minuteOfDay = 22 * 60))
        assertTrue(windowContains(sunNight, day = 1, minuteOfDay = 30))
        assertFalse(windowContains(sunNight, day = 1, minuteOfDay = 22 * 60))
    }

    @Test fun zeroLengthWindowCoversNothing() {
        val empty = ScheduleWindow(days = setOf(1, 2, 3, 4, 5, 6, 7), startMinute = 600, endMinute = 600)
        for (day in 1..7) for (m in listOf(0, 599, 600, 601, 1439)) assertFalse(windowContains(empty, day, m))
    }

    @Test fun noDaysCoversNothing() {
        val noDays = ScheduleWindow(days = emptySet(), startMinute = 0, endMinute = 1439)
        for (day in 1..7) assertFalse(windowContains(noDays, day, 720))
    }

    @Test fun previousAndNextDayWrap() {
        assertEquals(7, previousDay(1))
        assertEquals(1, previousDay(2))
        assertEquals(1, nextDay(7))
        assertEquals(2, nextDay(1))
    }

    @Test fun isScheduledNowResolvesLocalWallClock() {
        val toronto = ZoneId.of("America/Toronto")
        // Thu 2026-10-01 09:30 Toronto
        val inside = ZonedDateTime.of(2026, 10, 1, 9, 30, 0, 0, toronto)
        val outside = ZonedDateTime.of(2026, 10, 1, 11, 0, 0, 0, toronto)
        val saturday = ZonedDateTime.of(2026, 10, 3, 9, 30, 0, 0, toronto)
        assertTrue(isScheduledNow(listOf(morning), inside))
        assertFalse(isScheduledNow(listOf(morning), outside))
        assertFalse(isScheduledNow(listOf(morning), saturday))
        assertFalse(isScheduledNow(emptyList(), inside))
    }

    @Test fun isScheduledNowAnyWindowMatches() {
        val toronto = ZoneId.of("America/Toronto")
        // Tue 2026-10-06 00:30 Toronto: inside Monday's night window, outside morning.
        val lateMonday = ZonedDateTime.of(2026, 10, 6, 0, 30, 0, 0, toronto)
        assertTrue(isScheduledNow(listOf(morning, night), lateMonday))
        assertFalse(isScheduledNow(listOf(morning), lateMonday))
    }

    @Test fun isScheduledNowUsesTheGivenZoneNotUtc() {
        // 2026-10-01 13:30 UTC is 09:30 in Toronto (inside) but 13:30 in UTC (outside).
        val utcInstant = ZonedDateTime.of(2026, 10, 1, 13, 30, 0, 0, ZoneId.of("UTC"))
        assertFalse(isScheduledNow(listOf(morning), utcInstant))
        assertTrue(isScheduledNow(listOf(morning), utcInstant.withZoneSameInstant(ZoneId.of("America/Toronto"))))
    }
}
