package com.anastasiia.appblocker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BlockedListTest {
    private val toronto = ZoneId.of("America/Toronto")
    private val labels = mapOf("com.a.camera" to "Camera", "com.b.camera" to "Camera", "com.facebook.katana" to "Facebook")
    private fun labelOf(pkg: String) = labels[pkg] ?: pkg
    private val morning = ScheduleWindow(ALL_DAYS, 480, 660)

    @Test fun twoAppsWithTheSameLabelStayDistinctEntries() {
        val entries = blockedEntries(
            blocked = setOf("com.a.camera", "com.b.camera"),
            schedules = emptyMap(),
            labelOf = ::labelOf,
            at = ZonedDateTime.of(2026, 10, 2, 12, 0, 0, 0, toronto),
        )
        assertEquals(2, entries.size)
        assertEquals(setOf("com.a.camera", "com.b.camera"), entries.map { it.pkg }.toSet())
        assertTrue(entries.all { it.label == "Camera" && it.subtitle == "Always" && it.activeNow })
    }

    @Test fun scheduledAppOutsideItsWindowIsInactiveWithNextStart() {
        val entries = blockedEntries(
            blocked = emptySet(),
            schedules = mapOf("com.facebook.katana" to listOf(morning)),
            labelOf = ::labelOf,
            at = ZonedDateTime.of(2026, 10, 2, 12, 0, 0, 0, toronto),
        )
        val fb = entries.single()
        assertEquals("com.facebook.katana", fb.pkg)
        assertFalse(fb.activeNow)
        assertEquals("08:00–11:00 · daily · next: 08:00", fb.subtitle)
    }

    @Test fun scheduledAppInsideItsWindowIsActiveWithoutNext() {
        val fb = blockedEntries(
            blocked = emptySet(),
            schedules = mapOf("com.facebook.katana" to listOf(morning)),
            labelOf = ::labelOf,
            at = ZonedDateTime.of(2026, 10, 2, 9, 0, 0, 0, toronto),
        ).single()
        assertTrue(fb.activeNow)
        assertEquals("08:00–11:00 · daily", fb.subtitle)
    }

    @Test fun alwaysWinsAndEntriesAreSortedByLabel() {
        val entries = blockedEntries(
            blocked = setOf("com.facebook.katana"),
            schedules = mapOf("com.facebook.katana" to listOf(morning), "com.a.camera" to listOf(morning)),
            labelOf = ::labelOf,
            at = ZonedDateTime.of(2026, 10, 2, 12, 0, 0, 0, toronto),
        )
        assertEquals(listOf("Camera", "Facebook"), entries.map { it.label })
        assertEquals("Always", entries.last().subtitle)
    }
}
