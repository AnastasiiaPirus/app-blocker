package com.anastasiia.appblocker.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.anastasiia.appblocker.core.formatRemaining
import kotlinx.coroutines.delay

private val PAUSE_MINUTES = listOf(1, 5, 15, 60)

@Composable
fun MainScreen(viewModel: MainViewModel, onEditApps: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var serviceEnabled by remember { mutableStateOf(isBlockerServiceEnabled(context)) }
    var advancedExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            serviceEnabled = isBlockerServiceEnabled(context)
            delay(1_000L)
        }
    }

    val appLabels = remember(state.blockedPackages) {
        val byPackage = launchableApps(context.packageManager).associateBy { it.packageName }
        state.blockedPackages.map { pkg -> byPackage[pkg]?.label ?: pkg }.sorted()
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (!serviceEnabled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .clickable {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                ) {
                    Text(
                        "Blocking isn't active — tap to enable the accessibility service.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Blocking", style = MaterialTheme.typography.headlineSmall)
                Switch(checked = state.enabled, onCheckedChange = { viewModel.setEnabled(it) })
            }

            if (state.isPaused(now)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Paused — resumes in ${formatRemaining(state.pausedUntil - now)}")
                    TextButton(onClick = { viewModel.resumeNow() }) { Text("Resume now") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Blocked apps", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onEditApps) { Text("Edit") }
            }

            LazyColumn(Modifier.weight(1f)) {
                if (appLabels.isEmpty()) {
                    item {
                        Text(
                            "No apps selected yet.",
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(appLabels) { label ->
                    Text(label, modifier = Modifier.padding(vertical = 8.dp))
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                Text(if (advancedExpanded) "Advanced ▲" else "Advanced ▼")
            }
            if (advancedExpanded) {
                Text("Pause blocking", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PAUSE_MINUTES.forEach { minutes ->
                        Button(onClick = { viewModel.pauseFor(minutes) }) { Text("${minutes}m") }
                    }
                }
            }
        }
    }
}
