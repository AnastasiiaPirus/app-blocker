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
}
