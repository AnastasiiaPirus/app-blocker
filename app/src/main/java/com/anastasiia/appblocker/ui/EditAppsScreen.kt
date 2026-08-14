package com.anastasiia.appblocker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.anastasiia.appblocker.core.GateAction

@Composable
fun EditAppsScreen(viewModel: MainViewModel, onDone: () -> Unit, onGate: (GateAction) -> Unit = {}) {
    val context = LocalContext.current
    val apps = remember { launchableApps(context.packageManager) }
    val state = viewModel.state.collectAsState().value
    val saved = state.blockedPackages
    var selected by remember(saved) { mutableStateOf(saved) }
    var query by remember { mutableStateOf("") }

    val visible = apps.filter { it.label.contains(query, ignoreCase = true) }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search apps") },
                singleLine = true,
            )
            LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp)) {
                items(visible, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(app.label, modifier = Modifier.weight(1f))
                        Checkbox(
                            checked = app.packageName in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + app.packageName
                                else selected - app.packageName
                            },
                        )
                    }
                }
            }
            Button(
                onClick = {
                    val installed = apps.map { it.packageName }.toSet()
                    val current = saved intersect installed // stale uninstalled entries drop silently
                    val chosen = selected intersect installed
                    val removals = current - chosen
                    val additions = chosen - current
                    if (state.enabled && removals.isNotEmpty()) {
                        // Additions apply now; removals go through the gate.
                        viewModel.setBlockedPackages(current + additions)
                        onGate(GateAction.RemoveApps(removals))
                    } else {
                        viewModel.setBlockedPackages(chosen)
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
