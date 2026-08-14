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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class GateCoordinatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val answer = "I am bored and I want a quick hit; honestly nothing in there is new."

    private fun runGateTest(block: suspend (BlockerStateRepository, Journal, GateCoordinator) -> Unit) = runTest {
        val file = tmp.newFile("state.preferences_pb").absolutePath.toPath()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val store = PreferenceDataStoreFactory.createWithPath(scope = scope) { file }
        val repo = BlockerStateRepository(store)
        val journal = Journal(File(tmp.root, "journal.jsonl"))
        block(repo, journal, GateCoordinator(repo, journal))
        scope.cancel()
    }

    @Test
    fun submitStoresRequestAdvancesCursorAndSetsWait() = runGateTest { repo, _, gate ->
        gate.submit(GateAction.Pause(15), answer, now = 1_000L)
        val state = repo.gateState.first()
        assertEquals(
            PendingRequest(GateAction.Pause(15), questionIdx = 0, answer = answer, readyAt = 1_000L + 5 * 60_000L),
            state.pending,
        )
        assertEquals(1, state.questionCursor)
        assertEquals(0, state.urgesOutlasted)
    }

    @Test
    fun submitReplacingJournalsReplacedWithoutCounter() = runGateTest { repo, journal, gate ->
        gate.submit(GateAction.Pause(15), answer, now = 1_000L)
        gate.submit(GateAction.Disable, answer, now = 2_000L)
        val state = repo.gateState.first()
        assertEquals(GateAction.Disable, state.pending?.action)
        assertEquals(2_000L + 30 * 60_000L, state.pending?.readyAt)
        assertEquals(1, state.pending?.questionIdx)
        assertEquals(listOf(GateOutcome.REPLACED), journal.readAll().map { it.outcome })
        assertEquals(0, state.urgesOutlasted)
    }

    @Test
    fun cancelJournalsCancelledAndCounts() = runGateTest { repo, journal, gate ->
        gate.submit(GateAction.Pause(15), answer, now = 1_000L)
        gate.cancel(now = 2_000L)
        val state = repo.gateState.first()
        assertNull(state.pending)
        assertEquals(1, state.urgesOutlasted)
        assertEquals(listOf(GateOutcome.CANCELLED), journal.readAll().map { it.outcome })
        assertEquals(GATE_QUESTIONS[0], journal.readAll().single().question)
    }

    @Test
    fun confirmPauseAppliesFromConfirmationTime() = runGateTest { repo, journal, gate ->
        repo.setEnabled(true)
        gate.submit(GateAction.Pause(15), answer, now = 0L)
        val ready = 5 * 60_000L
        gate.confirm(now = ready + 60_000L)
        assertEquals(ready + 60_000L + 15 * 60_000L, repo.state.first().pausedUntil)
        assertNull(repo.gateState.first().pending)
        assertEquals(0, repo.gateState.first().urgesOutlasted)
        assertEquals(listOf(GateOutcome.CONFIRMED), journal.readAll().map { it.outcome })
    }

    @Test
    fun confirmDuringWaitingDoesNothing() = runGateTest { repo, journal, gate ->
        gate.submit(GateAction.Pause(15), answer, now = 0L)
        gate.confirm(now = 10_000L)
        assertEquals(0L, repo.state.first().pausedUntil)
        assertEquals(GateAction.Pause(15), repo.gateState.first().pending?.action)
        assertEquals(emptyList<JournalEntry>(), journal.readAll())
    }

    @Test
    fun confirmDisableTurnsBlockingOff() = runGateTest { repo, _, gate ->
        repo.setEnabled(true)
        gate.submit(GateAction.Disable, answer, now = 0L)
        gate.confirm(now = 30 * 60_000L + 1)
        assertEquals(false, repo.state.first().enabled)
    }

    @Test
    fun confirmRemoveAppsSubtractsFromBlockedList() = runGateTest { repo, _, gate ->
        repo.setEnabled(true)
        repo.setBlockedPackages(setOf("a", "b", "c"))
        gate.submit(GateAction.RemoveApps(setOf("a", "c")), answer, now = 0L)
        gate.confirm(now = 5 * 60_000L)
        assertEquals(setOf("b"), repo.state.first().blockedPackages)
    }

    @Test
    fun lapseAfterConfirmWindowCountsAndClears() = runGateTest { repo, journal, gate ->
        gate.submit(GateAction.Pause(15), answer, now = 0L)
        val expired = 5 * 60_000L + CONFIRM_WINDOW_MS
        gate.lapseIfExpired(now = expired)
        assertNull(repo.gateState.first().pending)
        assertEquals(1, repo.gateState.first().urgesOutlasted)
        assertEquals(listOf(GateOutcome.LAPSED), journal.readAll().map { it.outcome })
    }

    @Test
    fun lapseBeforeExpiryIsNoOp() = runGateTest { repo, journal, gate ->
        gate.submit(GateAction.Pause(15), answer, now = 0L)
        gate.lapseIfExpired(now = 5 * 60_000L + CONFIRM_WINDOW_MS - 1)
        assertEquals(GateAction.Pause(15), repo.gateState.first().pending?.action)
        assertEquals(emptyList<JournalEntry>(), journal.readAll())
    }

    @Test
    fun confirmAfterExpiryLapsesInstead() = runGateTest { repo, journal, gate ->
        repo.setEnabled(true)
        gate.submit(GateAction.Pause(15), answer, now = 0L)
        gate.confirm(now = 5 * 60_000L + CONFIRM_WINDOW_MS + 1)
        assertEquals(0L, repo.state.first().pausedUntil)
        assertEquals(1, repo.gateState.first().urgesOutlasted)
        assertEquals(listOf(GateOutcome.LAPSED), journal.readAll().map { it.outcome })
    }

    @Test
    fun cursorWrapsAfterTenSubmissions() = runGateTest { repo, _, gate ->
        var now = 0L
        repeat(10) {
            gate.submit(GateAction.Pause(1), answer, now)
            gate.cancel(now + 1)
            now += 10_000L
        }
        assertEquals(0, repo.gateState.first().questionCursor)
    }
}
