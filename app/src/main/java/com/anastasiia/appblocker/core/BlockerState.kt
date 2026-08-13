package com.anastasiia.appblocker.core

data class BlockerState(
    val enabled: Boolean = false,
    val blockedPackages: Set<String> = emptySet(),
    val pausedUntil: Long = 0L,
    val instagramMessagesOnly: Boolean = false,
) {
    fun isPaused(now: Long): Boolean = now < pausedUntil
}
