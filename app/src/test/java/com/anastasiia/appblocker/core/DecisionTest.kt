package com.anastasiia.appblocker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionTest {
    private val state = BlockerState(
        enabled = true,
        blockedPackages = setOf("com.instagram.android"),
        pausedUntil = 0L,
    )

    @Test fun blocksListedAppWhenEnabled() =
        assertTrue(shouldBlock("com.instagram.android", state, now = 1000L))

    @Test fun ignoresUnlistedApp() =
        assertFalse(shouldBlock("com.spotify.music", state, now = 1000L))

    @Test fun ignoresWhenDisabled() =
        assertFalse(shouldBlock("com.instagram.android", state.copy(enabled = false), 1000L))

    @Test fun ignoresWhilePaused() =
        assertFalse(shouldBlock("com.instagram.android", state.copy(pausedUntil = 2000L), now = 1000L))

    @Test fun blocksAgainWhenPauseExpires() =
        assertTrue(shouldBlock("com.instagram.android", state.copy(pausedUntil = 2000L), now = 2000L))

    @Test fun neverBlocksSelf() =
        assertFalse(shouldBlock(SELF_PACKAGE, state.copy(blockedPackages = setOf(SELF_PACKAGE)), 1000L))

    @Test fun ignoresNullPackage() =
        assertFalse(shouldBlock(null, state, 1000L))

    @Test fun formatsRemainingRoundingUp() {
        assertEquals("4:32", formatRemaining(271_001L))
        assertEquals("0:01", formatRemaining(1L))
        assertEquals("0:00", formatRemaining(0L))
        assertEquals("0:00", formatRemaining(-5_000L))
        assertEquals("60:00", formatRemaining(3_600_000L))
    }

    private val toronto = java.time.ZoneId.of("America/Toronto")
    private fun torontoMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        java.time.ZonedDateTime.of(y, mo, d, h, mi, 0, 0, toronto).toInstant().toEpochMilli()
    private val morning = ScheduleWindow(setOf(1, 2, 3, 4, 5, 6, 7), 8 * 60, 11 * 60)
    private val scheduled = BlockerState(
        enabled = true,
        blockedPackages = emptySet(),
        schedules = mapOf("com.facebook.katana" to listOf(morning)),
    )

    @Test fun blocksScheduledAppInsideItsWindow() =
        assertTrue(shouldBlock("com.facebook.katana", scheduled, torontoMillis(2026, 10, 1, 9, 30), toronto))

    @Test fun allowsScheduledAppOutsideItsWindow() =
        assertFalse(shouldBlock("com.facebook.katana", scheduled, torontoMillis(2026, 10, 1, 11, 0), toronto))

    @Test fun scheduleRespectsPauseAndToggle() {
        val inside = torontoMillis(2026, 10, 1, 9, 30)
        assertFalse(shouldBlock("com.facebook.katana", scheduled.copy(pausedUntil = inside + 60_000L), inside, toronto))
        assertFalse(shouldBlock("com.facebook.katana", scheduled.copy(enabled = false), inside, toronto))
    }

    @Test fun alwaysWinsOverSchedule() {
        val both = scheduled.copy(blockedPackages = setOf("com.facebook.katana"))
        assertTrue(shouldBlock("com.facebook.katana", both, torontoMillis(2026, 10, 1, 15, 0), toronto))
    }

    @Test fun scheduleIsEvaluatedInTheGivenZone() {
        // 13:30 UTC = 09:30 Toronto (inside) but 13:30 in UTC (outside).
        val utc = java.time.ZoneId.of("UTC")
        val millis = java.time.ZonedDateTime.of(2026, 10, 1, 13, 30, 0, 0, utc).toInstant().toEpochMilli()
        assertTrue(shouldBlock("com.facebook.katana", scheduled, millis, toronto))
        assertFalse(shouldBlock("com.facebook.katana", scheduled, millis, utc))
    }

    @Test fun unscheduledAppIsUnaffectedBySchedules() =
        assertFalse(shouldBlock("com.spotify.music", scheduled, torontoMillis(2026, 10, 1, 9, 30), toronto))
}
