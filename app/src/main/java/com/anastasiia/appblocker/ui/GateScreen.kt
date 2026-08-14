package com.anastasiia.appblocker.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.dp
import com.anastasiia.appblocker.core.GATE_MIN_ANSWER_CHARS
import com.anastasiia.appblocker.core.GATE_QUESTIONS
import com.anastasiia.appblocker.core.GateAction
import com.anastasiia.appblocker.core.JournalEntry
import com.anastasiia.appblocker.core.isValidGateAnswer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private object HiddenTextToolbar : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden
    override fun hide() {}
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {}
}

// Larger single-edit jumps than this are treated as paste attempts and dropped.
private const val MAX_INSERTION_CHARS = 20

@Composable
fun GateScreen(viewModel: MainViewModel, action: GateAction, onDone: () -> Unit, onBack: () -> Unit) {
    val gateState by viewModel.gateState.collectAsState()
    val question = GATE_QUESTIONS[gateState.questionCursor]
    var answer by remember { mutableStateOf("") }
    var showPast by remember { mutableStateOf(false) }
    val past by produceState(initialValue = emptyList<JournalEntry>(), question) {
        value = viewModel.pastAnswers(question)
    }

    BackHandler(onBack = onBack)

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("One question first.", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Text(question, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            CompositionLocalProvider(LocalTextToolbar provides HiddenTextToolbar) {
                OutlinedTextField(
                    value = answer,
                    onValueChange = { new ->
                        if (new.length - answer.length <= MAX_INSERTION_CHARS) answer = new
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    minLines = 4,
                )
            }
            Text(
                "${answer.trim().length} / $GATE_MIN_ANSWER_CHARS",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.submitGateAnswer(action, answer.trim())
                    onDone()
                },
                enabled = isValidGateAnswer(answer),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Submit") }
            if (past.isNotEmpty()) {
                TextButton(onClick = { showPast = !showPast }) {
                    Text(if (showPast) "Hide past answers" else "Past answers")
                }
                if (showPast) {
                    val dateFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
                    LazyColumn(Modifier.weight(1f)) {
                        items(past.asReversed()) { entry ->
                            Text(
                                "${dateFormat.format(Date(entry.timestamp))} — ${entry.answer}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
