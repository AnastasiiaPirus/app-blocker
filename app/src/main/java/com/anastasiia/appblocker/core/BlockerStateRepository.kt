package com.anastasiia.appblocker.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BlockerStateRepository(private val dataStore: DataStore<Preferences>) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val BLOCKED_PACKAGES = stringSetPreferencesKey("blocked_packages")
        val PAUSED_UNTIL = longPreferencesKey("paused_until")
        val INSTAGRAM_MESSAGES_ONLY = booleanPreferencesKey("instagram_messages_only")
        val YOUTUBE_NO_SHORTS = booleanPreferencesKey("youtube_no_shorts")
    }

    val state: Flow<BlockerState> = dataStore.data.map { prefs ->
        BlockerState(
            enabled = prefs[Keys.ENABLED] ?: false,
            blockedPackages = prefs[Keys.BLOCKED_PACKAGES] ?: emptySet(),
            pausedUntil = prefs[Keys.PAUSED_UNTIL] ?: 0L,
            instagramMessagesOnly = prefs[Keys.INSTAGRAM_MESSAGES_ONLY] ?: false,
            youtubeNoShorts = prefs[Keys.YOUTUBE_NO_SHORTS] ?: false,
        )
    }

    suspend fun setEnabled(value: Boolean) = dataStore.edit { it[Keys.ENABLED] = value }
    suspend fun setBlockedPackages(value: Set<String>) = dataStore.edit { it[Keys.BLOCKED_PACKAGES] = value }
    suspend fun setPausedUntil(value: Long) = dataStore.edit { it[Keys.PAUSED_UNTIL] = value }
    suspend fun setInstagramMessagesOnly(value: Boolean) =
        dataStore.edit { it[Keys.INSTAGRAM_MESSAGES_ONLY] = value }

    suspend fun setYoutubeNoShorts(value: Boolean) =
        dataStore.edit { it[Keys.YOUTUBE_NO_SHORTS] = value }
}
