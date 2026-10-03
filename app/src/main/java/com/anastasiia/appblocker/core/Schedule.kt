package com.anastasiia.appblocker.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

const val MINUTES_PER_DAY = 1440

/**
 * A weekly blocking window. [days] are ISO weekdays (1 = Monday … 7 = Sunday)
 * naming the day the window STARTS on; [startMinute]/[endMinute] are minutes
 * after local midnight. Start is inclusive, end exclusive. `endMinute <
 * startMinute` means the window crosses midnight and ends on the next day.
 * `endMinute == startMinute` or an empty [days] covers nothing.
 */
data class ScheduleWindow(val days: Set<Int>, val startMinute: Int, val endMinute: Int) {
    val crossesMidnight: Boolean get() = endMinute < startMinute
    val isEmpty: Boolean get() = days.isEmpty() || endMinute == startMinute
}

fun previousDay(day: Int): Int = if (day == 1) 7 else day - 1

fun nextDay(day: Int): Int = if (day == 7) 1 else day + 1

fun windowContains(window: ScheduleWindow, day: Int, minuteOfDay: Int): Boolean {
    if (window.isEmpty) return false
    return if (!window.crossesMidnight) {
        day in window.days && minuteOfDay >= window.startMinute && minuteOfDay < window.endMinute
    } else {
        (day in window.days && minuteOfDay >= window.startMinute) ||
            (previousDay(day) in window.days && minuteOfDay < window.endMinute)
    }
}

/** True when any window covers the local wall-clock instant [at]. */
fun isScheduledNow(windows: List<ScheduleWindow>, at: ZonedDateTime): Boolean {
    if (windows.isEmpty()) return false
    val day = at.dayOfWeek.value
    val minute = at.hour * 60 + at.minute
    return windows.any { windowContains(it, day, minute) }
}

/**
 * One boolean per (weekday, minute) of the week, true where any window
 * blocks. Index is `(day - 1) * MINUTES_PER_DAY + minute`. Used to decide
 * whether an edit removes blocking somewhere (gated) or only adds it.
 */
fun coverage(windows: List<ScheduleWindow>): BooleanArray {
    val grid = BooleanArray(7 * MINUTES_PER_DAY)
    for (w in windows) {
        if (w.isEmpty) continue
        for (day in w.days) {
            if (!w.crossesMidnight) {
                for (m in w.startMinute until w.endMinute) grid[(day - 1) * MINUTES_PER_DAY + m] = true
            } else {
                for (m in w.startMinute until MINUTES_PER_DAY) grid[(day - 1) * MINUTES_PER_DAY + m] = true
                val next = nextDay(day)
                for (m in 0 until w.endMinute) grid[(next - 1) * MINUTES_PER_DAY + m] = true
            }
        }
    }
    return grid
}

fun fullCoverage(): BooleanArray = BooleanArray(7 * MINUTES_PER_DAY) { true }

/** Coverage of an app's blocking configuration: Always covers the whole week. */
fun coverageOf(always: Boolean, windows: List<ScheduleWindow>): BooleanArray =
    if (always) fullCoverage() else coverage(windows)

/** True when some minute was blocked [before] and is not blocked [after]. */
fun coverageReduced(before: BooleanArray, after: BooleanArray): Boolean {
    for (i in before.indices) if (before[i] && !after[i]) return true
    return false
}

val ALL_DAYS: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7)
val MORNING_PRESET = ScheduleWindow(ALL_DAYS, 8 * 60, 11 * 60)
val NIGHT_PRESET = ScheduleWindow(ALL_DAYS, 21 * 60 + 30, 60)

private fun ScheduleWindow.isValid(): Boolean =
    days.all { it in 1..7 } && startMinute in 0 until MINUTES_PER_DAY && endMinute in 0 until MINUTES_PER_DAY

fun encodeWindows(windows: List<ScheduleWindow>): String = windowsToJson(windows).toString()

private fun windowsToJson(windows: List<ScheduleWindow>): JSONArray {
    val arr = JSONArray()
    for (w in windows) {
        arr.put(
            JSONObject()
                .put("days", JSONArray(w.days.sorted()))
                .put("start", w.startMinute)
                .put("end", w.endMinute),
        )
    }
    return arr
}

fun decodeWindows(json: String): List<ScheduleWindow> =
    runCatching { windowsFromJson(JSONArray(json)) }.getOrDefault(emptyList())

private fun windowsFromJson(arr: JSONArray): List<ScheduleWindow> {
    val out = ArrayList<ScheduleWindow>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val daysArr = o.optJSONArray("days") ?: continue
        val days = HashSet<Int>()
        for (j in 0 until daysArr.length()) days.add(daysArr.optInt(j, -1))
        val w = ScheduleWindow(days, o.optInt("start", -1), o.optInt("end", -1))
        if (w.isValid()) out.add(w)
    }
    return out
}

fun encodeSchedules(map: Map<String, List<ScheduleWindow>>): String {
    val o = JSONObject()
    for ((pkg, windows) in map) if (windows.isNotEmpty()) o.put(pkg, windowsToJson(windows))
    return o.toString()
}

/** Tolerant: anything unreadable yields an empty map rather than a crash in the service. */
fun decodeSchedules(json: String?): Map<String, List<ScheduleWindow>> {
    if (json.isNullOrBlank()) return emptyMap()
    return runCatching {
        val o = JSONObject(json)
        val out = HashMap<String, List<ScheduleWindow>>()
        for (pkg in o.keys()) {
            val windows = o.optJSONArray(pkg)?.let(::windowsFromJson).orEmpty()
            if (windows.isNotEmpty()) out[pkg] = windows
        }
        out
    }.getOrDefault(emptyMap())
}

fun formatMinute(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

private val DAY_ABBREVIATIONS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun formatDays(days: Set<Int>): String = when {
    days == ALL_DAYS -> "daily"
    days == setOf(1, 2, 3, 4, 5) -> "Mon–Fri"
    else -> days.sorted().joinToString(", ") { DAY_ABBREVIATIONS[it - 1] }
}

fun formatWindow(window: ScheduleWindow): String =
    "${formatMinute(window.startMinute)}–${formatMinute(window.endMinute)} · ${formatDays(window.days)}"

fun describeWindows(windows: List<ScheduleWindow>): String = windows.joinToString(", ", transform = ::formatWindow)

/**
 * Minute-of-day at which the next window begins, scanning forward from
 * (day, minuteOfDay) for up to one week. A window that is active right now
 * reports its own start. Null when there are no windows.
 */
fun nextWindowStart(windows: List<ScheduleWindow>, day: Int, minuteOfDay: Int): Int? {
    if (windows.none { !it.isEmpty }) return null
    var d = day
    var m = minuteOfDay
    repeat(7 * MINUTES_PER_DAY) {
        for (w in windows) if (!w.isEmpty && d in w.days && w.startMinute == m) return m
        for (w in windows) if (windowContains(w, d, m) && !(d in w.days && w.startMinute == m)) {
            // Inside a window that started earlier: report that window's start.
            return w.startMinute
        }
        m++
        if (m == MINUTES_PER_DAY) { m = 0; d = nextDay(d) }
    }
    return null
}

/**
 * Whether saving [after] in place of [before] must go through the Unblock
 * Gate: only while blocking is on, and only if some minute of the week stops
 * being blocked. An app that was Always blocked counts as fully covered.
 */
fun scheduleSaveNeedsGate(
    enabled: Boolean,
    wasAlways: Boolean,
    before: List<ScheduleWindow>,
    after: List<ScheduleWindow>,
): Boolean = enabled && coverageReduced(coverageOf(wasAlways, before), coverageOf(always = false, windows = after))
