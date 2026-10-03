package com.anastasiia.appblocker.core

import java.time.Instant
import java.time.ZoneId

const val SELF_PACKAGE = "com.anastasiia.appblocker"

fun shouldBlock(
    pkg: String?,
    state: BlockerState,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean {
    if (pkg == null || pkg == SELF_PACKAGE) return false
    if (!state.enabled || state.isPaused(now)) return false
    if (pkg in state.blockedPackages) return true
    val windows = state.schedules[pkg] ?: return false
    return isScheduledNow(windows, Instant.ofEpochMilli(now).atZone(zone))
}

fun formatRemaining(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
