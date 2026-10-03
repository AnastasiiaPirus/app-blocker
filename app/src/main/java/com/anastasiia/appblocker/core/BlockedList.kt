package com.anastasiia.appblocker.core

import java.time.ZonedDateTime

/** One row of the main screen's blocked list. Keyed by [pkg]: two apps may share a label. */
data class BlockedEntry(val pkg: String, val label: String, val subtitle: String, val activeNow: Boolean)

/**
 * Rows for every Always-blocked and scheduled app, sorted by label. An app
 * in both sets is shown as Always. A scheduled app outside its window is
 * inactive and names its next start.
 */
fun blockedEntries(
    blocked: Set<String>,
    schedules: Map<String, List<ScheduleWindow>>,
    labelOf: (String) -> String,
    at: ZonedDateTime,
): List<BlockedEntry> {
    val day = at.dayOfWeek.value
    val minute = at.hour * 60 + at.minute
    val always = blocked.map { pkg -> BlockedEntry(pkg, labelOf(pkg), subtitle = "Always", activeNow = true) }
    val scheduled = schedules.filterKeys { it !in blocked }.map { (pkg, windows) ->
        val active = isScheduledNow(windows, at)
        val next = nextWindowStart(windows, day, minute)
        BlockedEntry(
            pkg = pkg,
            label = labelOf(pkg),
            subtitle = describeWindows(windows) + if (!active && next != null) " · next: ${formatMinute(next)}" else "",
            activeNow = active,
        )
    }
    return (always + scheduled).sortedBy { it.label.lowercase() }
}
