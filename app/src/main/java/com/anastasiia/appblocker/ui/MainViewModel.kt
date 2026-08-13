package com.anastasiia.appblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anastasiia.appblocker.core.BlockerState
import com.anastasiia.appblocker.core.BlockerStateRepository
import com.anastasiia.appblocker.core.blockerDataStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = BlockerStateRepository(app.blockerDataStore)

    val state: StateFlow<BlockerState> = repository.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, BlockerState())

    fun setEnabled(value: Boolean) = viewModelScope.launch { repository.setEnabled(value) }

    fun pauseFor(minutes: Int) = viewModelScope.launch {
        repository.setPausedUntil(System.currentTimeMillis() + minutes * 60_000L)
    }

    fun resumeNow() = viewModelScope.launch { repository.setPausedUntil(0L) }

    fun setBlockedPackages(value: Set<String>) =
        viewModelScope.launch { repository.setBlockedPackages(value) }

    fun setInstagramMessagesOnly(value: Boolean) =
        viewModelScope.launch { repository.setInstagramMessagesOnly(value) }
}
