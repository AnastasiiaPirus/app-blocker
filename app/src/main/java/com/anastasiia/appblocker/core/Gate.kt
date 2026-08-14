package com.anastasiia.appblocker.core

import java.util.Calendar
import java.util.TimeZone

sealed interface GateAction {
    data class Pause(val minutes: Int) : GateAction
    data object Disable : GateAction
    data class RemoveApps(val packages: Set<String>) : GateAction
}

const val GATE_MIN_ANSWER_CHARS = 50
const val CONFIRM_WINDOW_MS = 30 * 60_000L
private const val PAUSE_WAIT_MS = 5 * 60_000L
private const val REMOVE_WAIT_MS = 5 * 60_000L
private const val DISABLE_WAIT_MS = 30 * 60_000L

val GATE_QUESTIONS = listOf(
    "What are you feeling right now?",
    "What were you doing just before you picked up the phone?",
    "What are you hoping to find in there?",
    "What would you do with the next 20 minutes if it stayed blocked?",
    "How will you feel afterwards — honestly?",
    "What are you avoiding right now?",
    "What would tonight-you thank you for doing instead?",
    "Is something uncomfortable happening right now? What?",
    "What do you actually need at this moment?",
    "Why did you block this app in the first place?",
)

fun waitMillisFor(action: GateAction): Long = when (action) {
    is GateAction.Pause -> PAUSE_WAIT_MS
    is GateAction.RemoveApps -> REMOVE_WAIT_MS
    GateAction.Disable -> DISABLE_WAIT_MS
}

fun isValidGateAnswer(text: String): Boolean = text.trim().length >= GATE_MIN_ANSWER_CHARS

data class PendingRequest(
    val action: GateAction,
    val questionIdx: Int,
    val answer: String,
    val readyAt: Long,
)

enum class GatePhase { WAITING, READY, EXPIRED }

fun gatePhase(request: PendingRequest, now: Long): GatePhase = when {
    now < request.readyAt -> GatePhase.WAITING
    now < request.readyAt + CONFIRM_WINDOW_MS -> GatePhase.READY
    else -> GatePhase.EXPIRED
}

fun encodeAction(action: GateAction): String = when (action) {
    is GateAction.Pause -> "pause:${action.minutes}"
    GateAction.Disable -> "disable"
    is GateAction.RemoveApps -> "remove:${action.packages.sorted().joinToString(",")}"
}

fun decodeAction(s: String): GateAction? = when {
    s.startsWith("pause:") ->
        s.removePrefix("pause:").toIntOrNull()?.let { GateAction.Pause(it) }
    s == "disable" -> GateAction.Disable
    s.startsWith("remove:") ->
        s.removePrefix("remove:").split(",").filter { it.isNotEmpty() }.toSet()
            .takeIf { it.isNotEmpty() }?.let { GateAction.RemoveApps(it) }
    else -> null
}

fun formatClock(epochMillis: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val cal = Calendar.getInstance(zone).apply { timeInMillis = epochMillis }
    return "%d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}
