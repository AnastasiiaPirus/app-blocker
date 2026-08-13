package com.anastasiia.appblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.anastasiia.appblocker.core.BlockerState
import com.anastasiia.appblocker.core.BlockerStateRepository
import com.anastasiia.appblocker.core.blockerDataStore
import com.anastasiia.appblocker.core.shouldBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BlockerService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var state = BlockerState()

    private var collectJob: Job? = null

    override fun onServiceConnected() {
        collectJob?.cancel()
        val repository = BlockerStateRepository(applicationContext.blockerDataStore)
        collectJob = scope.launch { repository.state.collect { state = it } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        if (shouldBlock(pkg, state, System.currentTimeMillis())) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            startActivity(
                Intent(this, BlockScreenActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(BlockScreenActivity.EXTRA_PACKAGE, pkg),
            )
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
