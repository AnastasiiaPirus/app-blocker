package com.anastasiia.appblocker.core

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class BlockerStateRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun roundTripsAllFields() = runTest {
        val file = tmp.newFile("state.preferences_pb").absolutePath.toPath()

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val store = PreferenceDataStoreFactory.createWithPath(scope = scope) { file }
        val repo = BlockerStateRepository(store)

        assertEquals(BlockerState(), repo.state.first())

        repo.setEnabled(true)
        repo.setBlockedPackages(setOf("com.instagram.android", "com.zhiliaoapp.musically"))
        repo.setPausedUntil(123_456L)
        repo.setInstagramMessagesOnly(true)

        assertEquals(
            BlockerState(
                enabled = true,
                blockedPackages = setOf("com.instagram.android", "com.zhiliaoapp.musically"),
                pausedUntil = 123_456L,
                instagramMessagesOnly = true,
            ),
            repo.state.first(),
        )
        scope.cancel()

        // Verify durability: reopen the same file with a fresh DataStore instance
        val scope2 = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val store2 = PreferenceDataStoreFactory.createWithPath(scope = scope2) { file }
        val repo2 = BlockerStateRepository(store2)

        assertEquals(
            BlockerState(
                enabled = true,
                blockedPackages = setOf("com.instagram.android", "com.zhiliaoapp.musically"),
                pausedUntil = 123_456L,
                instagramMessagesOnly = true,
            ),
            repo2.state.first(),
        )
        scope2.cancel()
    }
}
