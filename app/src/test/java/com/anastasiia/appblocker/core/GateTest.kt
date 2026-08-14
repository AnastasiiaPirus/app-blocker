package com.anastasiia.appblocker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class GateTest {
    private val request = PendingRequest(
        action = GateAction.Pause(15),
        questionIdx = 3,
        answer = "a".repeat(50),
        readyAt = 1_000_000L,
    )

    @Test
    fun waitTimesMatchSpec() {
        assertEquals(5 * 60_000L, waitMillisFor(GateAction.Pause(15)))
        assertEquals(5 * 60_000L, waitMillisFor(GateAction.RemoveApps(setOf("a"))))
        assertEquals(30 * 60_000L, waitMillisFor(GateAction.Disable))
    }

    @Test
    fun phaseTransitionsAtReadyAtAndConfirmWindow() {
        assertEquals(GatePhase.WAITING, gatePhase(request, 999_999L))
        assertEquals(GatePhase.READY, gatePhase(request, 1_000_000L))
        assertEquals(GatePhase.READY, gatePhase(request, 1_000_000L + CONFIRM_WINDOW_MS - 1))
        assertEquals(GatePhase.EXPIRED, gatePhase(request, 1_000_000L + CONFIRM_WINDOW_MS))
    }

    @Test
    fun answerValidationTrimsAndRequires50Chars() {
        assertFalse(isValidGateAnswer(""))
        assertFalse(isValidGateAnswer("x".repeat(49)))
        assertFalse(isValidGateAnswer(" ".repeat(30) + "x".repeat(30) + " ".repeat(30)))
        assertTrue(isValidGateAnswer("x".repeat(50)))
        assertTrue(isValidGateAnswer("  " + "x".repeat(50) + "  "))
    }

    @Test
    fun tenOpenEndedQuestions() {
        assertEquals(10, GATE_QUESTIONS.size)
        assertEquals(10, GATE_QUESTIONS.toSet().size)
    }

    @Test
    fun actionEncodingRoundTrips() {
        val actions = listOf(
            GateAction.Pause(15),
            GateAction.Disable,
            GateAction.RemoveApps(setOf("com.instagram.android", "com.zhiliaoapp.musically")),
        )
        for (action in actions) assertEquals(action, decodeAction(encodeAction(action)))
        assertNull(decodeAction(""))
        assertNull(decodeAction("pause:notanumber"))
        assertNull(decodeAction("garbage"))
    }

    @Test
    fun formatClockUsesHourOfDayAndPadsMinutes() {
        val utc = TimeZone.getTimeZone("UTC")
        // 1970-01-01 18:42 UTC
        assertEquals("18:42", formatClock((18 * 3600 + 42 * 60) * 1000L, utc))
        // 1970-01-01 08:05 UTC
        assertEquals("8:05", formatClock((8 * 3600 + 5 * 60) * 1000L, utc))
    }
}
