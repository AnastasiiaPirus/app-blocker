package com.anastasiia.appblocker.core

import kotlinx.coroutines.flow.first

/** Owns the lifecycle of the single pending gate request. All mutations of
 *  gate state and all journal writes go through here. */
class GateCoordinator(
    private val repository: BlockerStateRepository,
    private val journal: Journal,
) {
    suspend fun submit(action: GateAction, answer: String, now: Long) {
        lapseIfExpired(now)
        val state = repository.gateState.first()
        state.pending?.let { journal.append(entryFor(it, now, GateOutcome.REPLACED)) }
        repository.setPending(
            PendingRequest(
                action = action,
                questionIdx = state.questionCursor,
                answer = answer,
                readyAt = now + waitMillisFor(action),
            ),
        )
        repository.setQuestionCursor((state.questionCursor + 1) % GATE_QUESTIONS.size)
    }

    suspend fun cancel(now: Long) = outlast(now)

    suspend fun decline(now: Long) = outlast(now)

    suspend fun confirm(now: Long) {
        val pending = repository.gateState.first().pending ?: return
        when (gatePhase(pending, now)) {
            GatePhase.WAITING -> return
            GatePhase.EXPIRED -> lapseIfExpired(now)
            GatePhase.READY -> {
                apply(pending.action, now)
                journal.append(entryFor(pending, now, GateOutcome.CONFIRMED))
                repository.clearPending()
            }
        }
    }

    suspend fun lapseIfExpired(now: Long) {
        val pending = repository.gateState.first().pending ?: return
        if (gatePhase(pending, now) != GatePhase.EXPIRED) return
        journal.append(entryFor(pending, now, GateOutcome.LAPSED))
        repository.incrementUrgesOutlasted()
        repository.clearPending()
    }

    private suspend fun outlast(now: Long) {
        val pending = repository.gateState.first().pending ?: return
        journal.append(entryFor(pending, now, GateOutcome.CANCELLED))
        repository.incrementUrgesOutlasted()
        repository.clearPending()
    }

    private suspend fun apply(action: GateAction, now: Long) {
        when (action) {
            is GateAction.Pause -> repository.setPausedUntil(now + action.minutes * 60_000L)
            GateAction.Disable -> repository.setEnabled(false)
            is GateAction.RemoveApps -> {
                val current = repository.state.first().blockedPackages
                repository.setBlockedPackages(current - action.packages)
            }
        }
    }

    private fun entryFor(request: PendingRequest, now: Long, outcome: GateOutcome) = JournalEntry(
        timestamp = now,
        question = GATE_QUESTIONS[request.questionIdx],
        answer = request.answer,
        action = encodeAction(request.action),
        outcome = outcome,
    )
}
