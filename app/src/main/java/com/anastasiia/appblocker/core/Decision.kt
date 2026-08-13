package com.anastasiia.appblocker.core

const val SELF_PACKAGE = "com.anastasiia.appblocker"

fun shouldBlock(pkg: String?, state: BlockerState, now: Long): Boolean =
    pkg != null &&
        pkg != SELF_PACKAGE &&
        state.enabled &&
        !state.isPaused(now) &&
        pkg in state.blockedPackages

fun formatRemaining(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
