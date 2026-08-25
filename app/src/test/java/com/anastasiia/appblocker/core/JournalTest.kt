package com.anastasiia.appblocker.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JournalTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun entry(question: String, answer: String, outcome: GateOutcome) = JournalEntry(
        timestamp = 1_755_000_000_000L,
        question = question,
        answer = answer,
        action = "pause:15",
        outcome = outcome,
    )

    @Test
    fun appendAndReadRoundTrips() {
        val journal = Journal(File(tmp.root, "journal.jsonl"))
        val first = entry("Why?", "Because I am bored and tired\nand it is late.", GateOutcome.CANCELLED)
        val second = entry("What now?", "A \"quoted\" answer with unicode — приложение.", GateOutcome.CONFIRMED)

        journal.append(first)
        journal.append(second)

        assertEquals(listOf(first, second), journal.readAll())
    }

    @Test
    fun missingFileReadsEmpty() {
        assertEquals(emptyList<JournalEntry>(), Journal(File(tmp.root, "nope.jsonl")).readAll())
    }

    @Test
    fun corruptLinesAreSkipped() {
        val file = File(tmp.root, "journal.jsonl")
        val journal = Journal(file)
        val good = entry("Why?", "long enough answer for the test to be realistic", GateOutcome.LAPSED)
        journal.append(good)
        file.appendText("not json at all\n")
        journal.append(good)

        assertEquals(listOf(good, good), journal.readAll())
    }

    @Test
    fun answersForFiltersByQuestion() {
        val journal = Journal(File(tmp.root, "journal.jsonl"))
        val a = entry("What are you feeling right now?", "restless", GateOutcome.CANCELLED)
        val b = entry("Why did you block this app in the first place?", "doomscrolling", GateOutcome.CONFIRMED)
        val c = entry("What are you feeling right now?", "bored", GateOutcome.REPLACED)
        listOf(a, b, c).forEach(journal::append)

        assertEquals(listOf(a, c), journal.answersFor("What are you feeling right now?"))
    }
}
