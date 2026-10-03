package com.anastasiia.appblocker.core

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
