package com.anastasiia.appblocker.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
        val PENDING_ACTION = stringPreferencesKey("pending_action")
        val PENDING_QUESTION_IDX = intPreferencesKey("pending_question_idx")
        val PENDING_ANSWER = stringPreferencesKey("pending_answer")
        val PENDING_READY_AT = longPreferencesKey("pending_ready_at")
        val QUESTION_CURSOR = intPreferencesKey("question_cursor")
        val URGES_OUTLASTED = intPreferencesKey("urges_outlasted")
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

    val gateState: Flow<GateState> = dataStore.data.map { prefs ->
        val action = prefs[Keys.PENDING_ACTION]?.let(::decodeAction)
        GateState(
            pending = action?.let {
                PendingRequest(
                    action = it,
                    questionIdx = prefs[Keys.PENDING_QUESTION_IDX] ?: 0,
                    answer = prefs[Keys.PENDING_ANSWER] ?: "",
                    readyAt = prefs[Keys.PENDING_READY_AT] ?: 0L,
                )
            },
            questionCursor = prefs[Keys.QUESTION_CURSOR] ?: 0,
            urgesOutlasted = prefs[Keys.URGES_OUTLASTED] ?: 0,
        )
    }

    suspend fun setPending(request: PendingRequest) = dataStore.edit {
        it[Keys.PENDING_ACTION] = encodeAction(request.action)
        it[Keys.PENDING_QUESTION_IDX] = request.questionIdx
        it[Keys.PENDING_ANSWER] = request.answer
        it[Keys.PENDING_READY_AT] = request.readyAt
    }

    suspend fun clearPending() = dataStore.edit {
        it.remove(Keys.PENDING_ACTION)
        it.remove(Keys.PENDING_QUESTION_IDX)
        it.remove(Keys.PENDING_ANSWER)
        it.remove(Keys.PENDING_READY_AT)
    }

    suspend fun setQuestionCursor(value: Int) = dataStore.edit { it[Keys.QUESTION_CURSOR] = value }

    suspend fun incrementUrgesOutlasted() = dataStore.edit {
        it[Keys.URGES_OUTLASTED] = (it[Keys.URGES_OUTLASTED] ?: 0) + 1
    }
}
