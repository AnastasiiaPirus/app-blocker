package com.anastasiia.appblocker.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.anastasiia.appblocker.core.GateAction
import com.anastasiia.appblocker.core.formatClock

@Composable
fun ConfirmScreen(viewModel: MainViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val gateState by viewModel.gateState.collectAsState()
    val pending = gateState.pending
    if (pending == null) {
        onDone()
        return
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "“${pending.answer}”",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Text("Still want this?", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val action = pending.action
                    viewModel.confirmPending()
                    val message = if (action is GateAction.Pause) {
                        "Done — resumes at ${formatClock(System.currentTimeMillis() + action.minutes * 60_000L)}."
                    } else {
                        "Done."
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    onDone()
                }) { Text("Yes") }
                OutlinedButton(onClick = {
                    viewModel.declinePending()
                    Toast.makeText(context, "Nice. That one passed.", Toast.LENGTH_SHORT).show()
                    onDone()
                }) { Text("No, I'm good") }
            }
        }
    }
}
