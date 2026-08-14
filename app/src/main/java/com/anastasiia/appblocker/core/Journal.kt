package com.anastasiia.appblocker.core

import org.json.JSONObject
import java.io.File

enum class GateOutcome { CONFIRMED, CANCELLED, LAPSED, REPLACED }

data class JournalEntry(
    val timestamp: Long,
    val question: String,
    val answer: String,
    val action: String,
    val outcome: GateOutcome,
)

/** Append-only JSONL log of completed gate requests. Not thread-safe; all
 *  writes go through the single GateCoordinator. */
class Journal(private val file: File) {
    fun append(entry: JournalEntry) {
        val line = JSONObject()
            .put("ts", entry.timestamp)
            .put("question", entry.question)
            .put("answer", entry.answer)
            .put("action", entry.action)
            .put("outcome", entry.outcome.name)
            .toString()
        file.appendText(line + "\n")
    }

    fun readAll(): List<JournalEntry> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                JournalEntry(
                    timestamp = o.getLong("ts"),
                    question = o.getString("question"),
                    answer = o.getString("answer"),
                    action = o.getString("action"),
                    outcome = GateOutcome.valueOf(o.getString("outcome")),
                )
            }.getOrNull()
        }
    }

    fun answersFor(question: String): List<JournalEntry> =
        readAll().filter { it.question == question }
}
