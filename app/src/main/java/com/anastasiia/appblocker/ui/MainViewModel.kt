package com.anastasiia.appblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anastasiia.appblocker.core.BlockerState
import com.anastasiia.appblocker.core.BlockerStateRepository
import com.anastasiia.appblocker.core.GateAction
import com.anastasiia.appblocker.core.GateCoordinator
import com.anastasiia.appblocker.core.GateState
import com.anastasiia.appblocker.core.Journal
import com.anastasiia.appblocker.core.JournalEntry
import com.anastasiia.appblocker.core.blockerDataStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = BlockerStateRepository(app.blockerDataStore)
    private val journal = Journal(File(app.filesDir, "journal.jsonl"))
    private val gate = GateCoordinator(repository, journal)

    val state: StateFlow<BlockerState> = repository.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, BlockerState())

    val gateState: StateFlow<GateState> = repository.gateState
        .stateIn(viewModelScope, SharingStarted.Eagerly, GateState())

    fun submitGateAnswer(action: GateAction, answer: String) =
        viewModelScope.launch { gate.submit(action, answer, System.currentTimeMillis()) }

    fun cancelPending() = viewModelScope.launch { gate.cancel(System.currentTimeMillis()) }

    fun confirmPending() = viewModelScope.launch { gate.confirm(System.currentTimeMillis()) }

    fun declinePending() = viewModelScope.launch { gate.decline(System.currentTimeMillis()) }

    fun lapseIfExpired() = viewModelScope.launch { gate.lapseIfExpired(System.currentTimeMillis()) }

    suspend fun pastAnswers(question: String): List<JournalEntry> =
        withContext(Dispatchers.IO) { journal.answersFor(question) }

    fun setEnabled(value: Boolean) = viewModelScope.launch { repository.setEnabled(value) }

    fun pauseFor(minutes: Int) = viewModelScope.launch {
        repository.setPausedUntil(System.currentTimeMillis() + minutes * 60_000L)
    }

    fun resumeNow() = viewModelScope.launch { repository.setPausedUntil(0L) }

    fun setBlockedPackages(value: Set<String>) =
        viewModelScope.launch { repository.setBlockedPackages(value) }

    fun setInstagramMessagesOnly(value: Boolean) =
        viewModelScope.launch { repository.setInstagramMessagesOnly(value) }

    fun setYoutubeNoShorts(value: Boolean) =
        viewModelScope.launch { repository.setYoutubeNoShorts(value) }
}
