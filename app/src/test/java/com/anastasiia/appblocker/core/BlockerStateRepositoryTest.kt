package com.anastasiia.appblocker.core

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
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

class BlockerStateRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun roundTripsAllFields() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val store = PreferenceDataStoreFactory.createWithPath(scope = scope) {
            tmp.newFile("state.preferences_pb").absolutePath.toPath()
        }
        val repo = BlockerStateRepository(store)

        assertEquals(BlockerState(), repo.state.first())

        repo.setEnabled(true)
        repo.setBlockedPackages(setOf("com.instagram.android", "com.zhiliaoapp.musically"))
        repo.setPausedUntil(123_456L)

        assertEquals(
            BlockerState(
                enabled = true,
                blockedPackages = setOf("com.instagram.android", "com.zhiliaoapp.musically"),
                pausedUntil = 123_456L,
            ),
            repo.state.first(),
        )
        scope.cancel()
    }
}
