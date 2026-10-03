package com.anastasiia.appblocker.core

data class BlockerState(
    val enabled: Boolean = false,
    val blockedPackages: Set<String> = emptySet(),
    val pausedUntil: Long = 0L,
    val instagramMessagesOnly: Boolean = false,
    val youtubeNoShorts: Boolean = false,
    val schedules: Map<String, List<ScheduleWindow>> = emptyMap(),
) {
    fun isPaused(now: Long): Boolean = now < pausedUntil
}

data class GateState(
    val pending: PendingRequest? = null,
    val questionCursor: Int = 0,
    val urgesOutlasted: Int = 0,
)
