# Unblock Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put a question → wait → confirm friction gate in front of every action that reduces blocking (pause, disable, remove apps), with a local answer journal and an "urges outlasted" counter.

**Architecture:** All gate logic lives in the main app; the accessibility-service hot path (`shouldBlock`) is untouched. A `PendingRequest` persisted in DataStore represents the single in-flight gate request; a `GateCoordinator` (pure-ish, JVM-testable) owns the request lifecycle and journals outcomes to an append-only JSONL file. Two new Compose screens (Gate question, Confirm) hang off the existing hand-rolled `Screen` enum navigation in `MainActivity`.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, DataStore Preferences, `org.json` (Android built-in; real artifact as test-only dependency). No new runtime dependencies.

**Spec:** `docs/superpowers/specs/2026-08-14-unblock-gate-design.md`

## Global Constraints

- Waits: pause = 5 min, remove apps = 5 min, disable = 30 min; confirmation window = 30 min after ready. Answers: ≥ 50 chars after trim, paste blocked.
- Copy is exactly the spec's string set — friendly, concise, granting; no extra text anywhere.
- Cancelled and lapsed requests increment `urgesOutlasted`; confirmed and replaced do not.
- `shouldBlock`, `BlockerService`, and the existing DataStore keys must not change.
- No new runtime dependencies (AndroidX + Compose BOM only); test-only artifacts are fine.
- minSdk 35 / target 36, JVM 17, existing test style (JUnit4, `runTest`, `TemporaryFolder`).
- Run unit tests with `./gradlew :app:testDebugUnitTest`; device build with `./gradlew installDebug`.

---

### Task 1: Gate domain model (`core/Gate.kt`)

**Files:**
- Create: `app/src/main/java/com/anastasiia/appblocker/core/Gate.kt`
- Test: `app/src/test/java/com/anastasiia/appblocker/core/GateTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin + java.util).
- Produces (used by Tasks 2–7): `GateAction` sealed interface (`Pause(minutes: Int)`, `Disable`, `RemoveApps(packages: Set<String>)`), `PendingRequest(action, questionIdx, answer, readyAt)`, `GatePhase` enum (`WAITING/READY/EXPIRED`), `gatePhase(request, now)`, `waitMillisFor(action): Long`, `isValidGateAnswer(text): Boolean`, `GATE_QUESTIONS: List<String>` (size 10), `GATE_MIN_ANSWER_CHARS`, `CONFIRM_WINDOW_MS`, `encodeAction(action): String`, `decodeAction(s): GateAction?`, `formatClock(epochMillis, zone): String`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.anastasiia.appblocker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class GateTest {
    private val request = PendingRequest(
        action = GateAction.Pause(15),
        questionIdx = 3,
        answer = "a".repeat(50),
        readyAt = 1_000_000L,
    )

    @Test
    fun waitTimesMatchSpec() {
        assertEquals(5 * 60_000L, waitMillisFor(GateAction.Pause(15)))
        assertEquals(5 * 60_000L, waitMillisFor(GateAction.RemoveApps(setOf("a"))))
        assertEquals(30 * 60_000L, waitMillisFor(GateAction.Disable))
    }

    @Test
    fun phaseTransitionsAtReadyAtAndConfirmWindow() {
        assertEquals(GatePhase.WAITING, gatePhase(request, 999_999L))
        assertEquals(GatePhase.READY, gatePhase(request, 1_000_000L))
        assertEquals(GatePhase.READY, gatePhase(request, 1_000_000L + CONFIRM_WINDOW_MS - 1))
        assertEquals(GatePhase.EXPIRED, gatePhase(request, 1_000_000L + CONFIRM_WINDOW_MS))
    }

    @Test
    fun answerValidationTrimsAndRequires50Chars() {
        assertFalse(isValidGateAnswer(""))
        assertFalse(isValidGateAnswer("x".repeat(49)))
        assertFalse(isValidGateAnswer(" ".repeat(30) + "x".repeat(30) + " ".repeat(30)))
        assertTrue(isValidGateAnswer("x".repeat(50)))
        assertTrue(isValidGateAnswer("  " + "x".repeat(50) + "  "))
    }

    @Test
    fun tenOpenEndedQuestions() {
        assertEquals(10, GATE_QUESTIONS.size)
        assertEquals(10, GATE_QUESTIONS.toSet().size)
    }

    @Test
    fun actionEncodingRoundTrips() {
        val actions = listOf(
            GateAction.Pause(15),
            GateAction.Disable,
            GateAction.RemoveApps(setOf("com.instagram.android", "com.zhiliaoapp.musically")),
        )
        for (action in actions) assertEquals(action, decodeAction(encodeAction(action)))
        assertNull(decodeAction(""))
        assertNull(decodeAction("pause:notanumber"))
        assertNull(decodeAction("garbage"))
    }

    @Test
    fun formatClockUsesHourOfDayAndPadsMinutes() {
        val utc = TimeZone.getTimeZone("UTC")
        // 1970-01-01 18:42 UTC
        assertEquals("18:42", formatClock((18 * 3600 + 42 * 60) * 1000L, utc))
        // 1970-01-01 08:05 UTC
        assertEquals("8:05", formatClock((8 * 3600 + 5 * 60) * 1000L, utc))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.anastasiia.appblocker.core.GateTest"`
Expected: compilation FAILURE (`GateAction` etc. unresolved).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.anastasiia.appblocker.core

import java.util.Calendar
import java.util.TimeZone

sealed interface GateAction {
    data class Pause(val minutes: Int) : GateAction
    data object Disable : GateAction
    data class RemoveApps(val packages: Set<String>) : GateAction
}

const val GATE_MIN_ANSWER_CHARS = 50
const val CONFIRM_WINDOW_MS = 30 * 60_000L
private const val PAUSE_WAIT_MS = 5 * 60_000L
private const val REMOVE_WAIT_MS = 5 * 60_000L
private const val DISABLE_WAIT_MS = 30 * 60_000L

val GATE_QUESTIONS = listOf(
    "What are you feeling right now?",
    "What were you doing just before you picked up the phone?",
    "What are you hoping to find in there?",
    "What would you do with the next 20 minutes if it stayed blocked?",
    "How will you feel afterwards — honestly?",
    "What are you avoiding right now?",
    "What would tonight-you thank you for doing instead?",
    "Is something uncomfortable happening right now? What?",
    "What do you actually need at this moment?",
    "Why did you block this app in the first place?",
)

fun waitMillisFor(action: GateAction): Long = when (action) {
    is GateAction.Pause -> PAUSE_WAIT_MS
    is GateAction.RemoveApps -> REMOVE_WAIT_MS
    GateAction.Disable -> DISABLE_WAIT_MS
}

fun isValidGateAnswer(text: String): Boolean = text.trim().length >= GATE_MIN_ANSWER_CHARS

data class PendingRequest(
    val action: GateAction,
    val questionIdx: Int,
    val answer: String,
    val readyAt: Long,
)

enum class GatePhase { WAITING, READY, EXPIRED }

fun gatePhase(request: PendingRequest, now: Long): GatePhase = when {
    now < request.readyAt -> GatePhase.WAITING
    now < request.readyAt + CONFIRM_WINDOW_MS -> GatePhase.READY
    else -> GatePhase.EXPIRED
}

fun encodeAction(action: GateAction): String = when (action) {
    is GateAction.Pause -> "pause:${action.minutes}"
    GateAction.Disable -> "disable"
    is GateAction.RemoveApps -> "remove:${action.packages.sorted().joinToString(",")}"
}

fun decodeAction(s: String): GateAction? = when {
    s.startsWith("pause:") ->
        s.removePrefix("pause:").toIntOrNull()?.let { GateAction.Pause(it) }
    s == "disable" -> GateAction.Disable
    s.startsWith("remove:") ->
        s.removePrefix("remove:").split(",").filter { it.isNotEmpty() }.toSet()
            .takeIf { it.isNotEmpty() }?.let { GateAction.RemoveApps(it) }
    else -> null
}

fun formatClock(epochMillis: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val cal = Calendar.getInstance(zone).apply { timeInMillis = epochMillis }
    return "%d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.anastasiia.appblocker.core.GateTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anastasiia/appblocker/core/Gate.kt app/src/test/java/com/anastasiia/appblocker/core/GateTest.kt
git commit -m "feat: gate domain model — actions, phases, questions, validation"
```

---

### Task 2: Journal (`core/Journal.kt`)

**Files:**
- Create: `app/src/main/java/com/anastasiia/appblocker/core/Journal.kt`
- Modify: `gradle/libs.versions.toml` (add `json` library), `app/build.gradle.kts:38-41` (add test dependency)
- Test: `app/src/test/java/com/anastasiia/appblocker/core/JournalTest.kt`

**Interfaces:**
- Consumes: `encodeAction` string form from Task 1 (stored opaquely).
- Produces (used by Task 4, 5): `GateOutcome` enum (`CONFIRMED/CANCELLED/LAPSED/REPLACED`), `JournalEntry(timestamp: Long, question: String, answer: String, action: String, outcome: GateOutcome)`, `class Journal(file: File)` with `append(entry)`, `readAll(): List<JournalEntry>`, `answersFor(question: String): List<JournalEntry>`.

- [ ] **Step 1: Add the test-only org.json artifact** (Android ships `org.json` at runtime; local JVM unit tests need the real library)

In `gradle/libs.versions.toml`, add under `[libraries]`:

```toml
json = { module = "org.json:json", version = "20240303" }
```

In `app/build.gradle.kts` dependencies block, after `testImplementation(libs.datastore.preferences.core)`:

```kotlin
testImplementation(libs.json)
```

- [ ] **Step 2: Write the failing test**

```kotlin
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
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.anastasiia.appblocker.core.JournalTest"`
Expected: compilation FAILURE (`Journal` unresolved).

- [ ] **Step 4: Write minimal implementation**

```kotlin
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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.anastasiia.appblocker.core.JournalTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/anastasiia/appblocker/core/Journal.kt app/src/test/java/com/anastasiia/appblocker/core/JournalTest.kt gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat: append-only JSONL journal for gate outcomes"
```

---

### Task 3: Gate state persistence (repository)

**Files:**
- Modify: `app/src/main/java/com/anastasiia/appblocker/core/BlockerStateRepository.kt`
- Test: `app/src/test/java/com/anastasiia/appblocker/core/BlockerStateRepositoryTest.kt` (add tests; keep existing test unchanged)

**Interfaces:**
- Consumes: `PendingRequest`, `encodeAction`, `decodeAction` (Task 1).
- Produces (used by Tasks 4–6): `data class GateState(pending: PendingRequest? = null, questionCursor: Int = 0, urgesOutlasted: Int = 0)`; on `BlockerStateRepository`: `val gateState: Flow<GateState>`, `suspend fun setPending(request: PendingRequest)`, `suspend fun clearPending()`, `suspend fun setQuestionCursor(value: Int)`, `suspend fun incrementUrgesOutlasted()`.
- Existing keys/methods must remain byte-for-byte compatible (the accessibility service reads them).

- [ ] **Step 1: Write the failing tests** (append to `BlockerStateRepositoryTest.kt`; reuse the existing temp-DataStore pattern)

```kotlin
    @Test
    fun gateStateRoundTripsAndClears() = runTest {
        val file = tmp.newFile("gate.preferences_pb").absolutePath.toPath()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val store = PreferenceDataStoreFactory.createWithPath(scope = scope) { file }
        val repo = BlockerStateRepository(store)

        assertEquals(GateState(), repo.gateState.first())

        val request = PendingRequest(
            action = GateAction.RemoveApps(setOf("com.instagram.android")),
            questionIdx = 7,
            answer = "I want to check something and I know it will not stop there.",
            readyAt = 42_000L,
        )
        repo.setPending(request)
        repo.setQuestionCursor(8)
        repo.incrementUrgesOutlasted()
        repo.incrementUrgesOutlasted()

        assertEquals(GateState(pending = request, questionCursor = 8, urgesOutlasted = 2), repo.gateState.first())

        repo.clearPending()
        assertEquals(GateState(pending = null, questionCursor = 8, urgesOutlasted = 2), repo.gateState.first())
        scope.cancel()
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.anastasiia.appblocker.core.BlockerStateRepositoryTest"`
Expected: compilation FAILURE (`gateState` unresolved).

- [ ] **Step 3: Implement in `BlockerStateRepository.kt`**

Add to the imports: `androidx.datastore.preferences.core.intPreferencesKey`, `androidx.datastore.preferences.core.stringPreferencesKey`.

Add inside `Keys`:

```kotlin
        val PENDING_ACTION = stringPreferencesKey("pending_action")
        val PENDING_QUESTION_IDX = intPreferencesKey("pending_question_idx")
        val PENDING_ANSWER = stringPreferencesKey("pending_answer")
        val PENDING_READY_AT = longPreferencesKey("pending_ready_at")
        val QUESTION_CURSOR = intPreferencesKey("question_cursor")
        val URGES_OUTLASTED = intPreferencesKey("urges_outlasted")
```

Add after the existing `state` flow and setters:

```kotlin
    val gateState: Flow<GateState> = dataStore.data.map { prefs ->
        val action = prefs[Keys.PENDING_ACTION]?.let(::decodeAction)
        GateState(
            pending = action?.let {
                PendingRequest(
                    action = it,
                    questionIdx = prefs[Keys.PENDING_QUESTION_IDX] ?: 0,
                    answer = prefs[Keys.PENDING_ANSWER] ?: "",
                    readyAt = prefs[Keys.PENDING_READY_AT] ?: 0L,
                )
            },
            questionCursor = prefs[Keys.QUESTION_CURSOR] ?: 0,
            urgesOutlasted = prefs[Keys.URGES_OUTLASTED] ?: 0,
        )
    }

    suspend fun setPending(request: PendingRequest) = dataStore.edit {
        it[Keys.PENDING_ACTION] = encodeAction(request.action)
        it[Keys.PENDING_QUESTION_IDX] = request.questionIdx
        it[Keys.PENDING_ANSWER] = request.answer
        it[Keys.PENDING_READY_AT] = request.readyAt
    }

    suspend fun clearPending() = dataStore.edit {
        it.remove(Keys.PENDING_ACTION)
        it.remove(Keys.PENDING_QUESTION_IDX)
        it.remove(Keys.PENDING_ANSWER)
        it.remove(Keys.PENDING_READY_AT)
    }

    suspend fun setQuestionCursor(value: Int) = dataStore.edit { it[Keys.QUESTION_CURSOR] = value }

    suspend fun incrementUrgesOutlasted() = dataStore.edit {
        it[Keys.URGES_OUTLASTED] = (it[Keys.URGES_OUTLASTED] ?: 0) + 1
    }
```

And define `GateState` at the bottom of `BlockerState.kt`:

```kotlin
data class GateState(
    val pending: PendingRequest? = null,
    val questionCursor: Int = 0,
    val urgesOutlasted: Int = 0,
)
```

- [ ] **Step 4: Run the full unit suite to verify old + new pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, including the untouched `roundTripsAllFields`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anastasiia/appblocker/core/BlockerStateRepository.kt app/src/main/java/com/anastasiia/appblocker/core/BlockerState.kt app/src/test/java/com/anastasiia/appblocker/core/BlockerStateRepositoryTest.kt
git commit -m "feat: persist pending gate request, question cursor, urges counter"
```

---

### Task 4: GateCoordinator (request lifecycle)

**Files:**
- Create: `app/src/main/java/com/anastasiia/appblocker/core/GateCoordinator.kt`
- Test: `app/src/test/java/com/anastasiia/appblocker/core/GateCoordinatorTest.kt`

**Interfaces:**
- Consumes: `BlockerStateRepository` (Task 3), `Journal` (Task 2), Task 1 domain.
- Produces (used by Task 5): `class GateCoordinator(repository: BlockerStateRepository, journal: Journal)` with suspend functions `submit(action: GateAction, answer: String, now: Long)`, `cancel(now: Long)`, `confirm(now: Long)`, `decline(now: Long)`, `lapseIfExpired(now: Long)`.

Lifecycle rules (from the spec state machine):
- `submit`: if a pending request exists, journal it as `REPLACED` (no counter). Store the new request with `readyAt = now + waitMillisFor(action)`, advance `questionCursor` to `(cursor + 1) % GATE_QUESTIONS.size`.
- `cancel` (during WAITING or READY) and `decline` (at the confirm screen): journal `CANCELLED`, increment counter, clear.
- `confirm`: only when phase is READY — apply the action (`Pause(m)` → `setPausedUntil(now + m * 60_000L)`; `Disable` → `setEnabled(false)`; `RemoveApps(p)` → `setBlockedPackages(current - p)`), journal `CONFIRMED` (no counter), clear. In WAITING: no-op. In EXPIRED: delegate to lapse.
- `lapseIfExpired`: if pending and phase EXPIRED — journal `LAPSED`, increment counter, clear; otherwise no-op.
- Journal entries record `question = GATE_QUESTIONS[request.questionIdx]`, `action = encodeAction(request.action)`, `timestamp = now`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.anastasiia.appblocker.core

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class GateCoordinatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val answer = "I am bored and I want a quick hit; honestly nothing in there is new."

    private fun runGateTest(block: suspend (BlockerStateRepository, Journal, GateCoordinator) -> Unit) = runTest {
        val file = tmp.newFile("state.preferences_pb").absolutePath.toPath()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val store = PreferenceDataStoreFactory.createWithPath(scope = scope) { file }
        val repo = BlockerStateRepository(store)
        val journal = Journal(File(tmp.root, "journal.jsonl"))
        block(repo, journal, GateCoordinator(repo, journal))
        scope.cancel()
    }

    @Test
    fun submitStoresRequestAdvancesCursorAndSetsWait() = runGateTest { repo, _, gate ->
        gate.submit(GateAction.Pause(15), answer, now = 1_000L)
        val state = repo.gateState.first()
        assertEquals(
            PendingRequest(GateAction.Pause(15), questionIdx = 0, answer = answer, readyAt = 1_000L + 5 * 60_000L),
            state.pending,
        )
        assertEquals(1, state.questionCursor)
        assertEquals(0, state.urgesOutlasted)
    }

    @Test
    fun submitReplacingJournalsReplacedWithoutCounter() = runGateTest { repo, journal, gate ->
        gate.submit(GateAction.Pause(15), answer, now = 1_000L)
        gate.submit(GateAction.Disable, answer, now = 2_000L)
        val state = repo.gateState.first()
        assertEquals(GateAction.Disable, state.pending?.action)
        assertEquals(2_000L + 30 * 60_000L, state.pending?.readyAt)
        assertEquals(1, state.pending?.questionIdx)
        assertEquals(listOf(GateOutcome.REPLACED), journal.readAll().map { it.outcome })
        assertEquals(0, state.urgesOutlasted)
    }

    @Test
    fun cancelJournalsCancelledAndCounts() = runGateTest { repo, journal, gate ->
        gate.submit(GateAction.Pause(15), answer, now = 1_000L)
        gate.cancel(now = 2_000L)
        val state = repo.gateState.first()
        assertNull(state.pending)
        assertEquals(1, state.urgesOutlasted)
        assertEquals(listOf(GateOutcome.CANCELLED), journal.readAll().map { it.outcome })
        assertEquals(GATE_QUESTIONS[0], journal.readAll().single().question)
    }

    @Test
    fun confirmPauseAppliesFromConfirmationTime() = runGateTest { repo, journal, gate ->
        repo.setEnabled(true)
        gate.submit(GateAction.Pause(15), answer, now = 0L)
        val ready = 5 * 60_000L
        gate.confirm(now = ready + 60_000L)
        assertEquals(ready + 60_000L + 15 * 60_000L, repo.state.first().pausedUntil)
        assertNull(repo.gateState.first().pending)
        assertEquals(0, repo.gateState.first().urgesOutlasted)
        assertEquals(listOf(GateOutcome.CONFIRMED), journal.readAll().map { it.outcome })
    }

    @Test
    fun confirmDuringWaitingDoesNothing() = runGateTest { repo, journal, gate ->
        gate.submit(GateAction.Pause(15), answer, now = 0L)
        gate.confirm(now = 10_000L)
        assertEquals(0L, repo.state.first().pausedUntil)
        assertEquals(GateAction.Pause(15), repo.gateState.first().pending?.action)
        assertEquals(emptyList<JournalEntry>(), journal.readAll())
    }

    @Test
    fun confirmDisableTurnsBlockingOff() = runGateTest { repo, _, gate ->
        repo.setEnabled(true)
        gate.submit(GateAction.Disable, answer, now = 0L)
        gate.confirm(now = 30 * 60_000L + 1)
        assertEquals(false, repo.state.first().enabled)
    }

    @Test
    fun confirmRemoveAppsSubtractsFromBlockedList() = runGateTest { repo, _, gate ->
        repo.setEnabled(true)
        repo.setBlockedPackages(setOf("a", "b", "c"))
        gate.submit(GateAction.RemoveApps(setOf("a", "c")), answer, now = 0L)
        gate.confirm(now = 5 * 60_000L)
        assertEquals(setOf("b"), repo.state.first().blockedPackages)
    }

    @Test
    fun lapseAfterConfirmWindowCountsAndClears() = runGateTest { repo, journal, gate ->
        gate.submit(GateAction.Pause(15), answer, now = 0L)
        val expired = 5 * 60_000L + CONFIRM_WINDOW_MS
        gate.lapseIfExpired(now = expired)
        assertNull(repo.gateState.first().pending)
        assertEquals(1, repo.gateState.first().urgesOutlasted)
        assertEquals(listOf(GateOutcome.LAPSED), journal.readAll().map { it.outcome })
    }

    @Test
    fun lapseBeforeExpiryIsNoOp() = runGateTest { repo, journal, gate ->
        gate.submit(GateAction.Pause(15), answer, now = 0L)
        gate.lapseIfExpired(now = 5 * 60_000L + CONFIRM_WINDOW_MS - 1)
        assertEquals(GateAction.Pause(15), repo.gateState.first().pending?.action)
        assertEquals(emptyList<JournalEntry>(), journal.readAll())
    }

    @Test
    fun confirmAfterExpiryLapsesInstead() = runGateTest { repo, journal, gate ->
        repo.setEnabled(true)
        gate.submit(GateAction.Pause(15), answer, now = 0L)
        gate.confirm(now = 5 * 60_000L + CONFIRM_WINDOW_MS + 1)
        assertEquals(0L, repo.state.first().pausedUntil)
        assertEquals(1, repo.gateState.first().urgesOutlasted)
        assertEquals(listOf(GateOutcome.LAPSED), journal.readAll().map { it.outcome })
    }

    @Test
    fun cursorWrapsAfterTenSubmissions() = runGateTest { repo, _, gate ->
        var now = 0L
        repeat(10) {
            gate.submit(GateAction.Pause(1), answer, now)
            gate.cancel(now + 1)
            now += 10_000L
        }
        assertEquals(0, repo.gateState.first().questionCursor)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.anastasiia.appblocker.core.GateCoordinatorTest"`
Expected: compilation FAILURE (`GateCoordinator` unresolved).

- [ ] **Step 3: Write the implementation**

```kotlin
package com.anastasiia.appblocker.core

import kotlinx.coroutines.flow.first

/** Owns the lifecycle of the single pending gate request. All mutations of
 *  gate state and all journal writes go through here. */
class GateCoordinator(
    private val repository: BlockerStateRepository,
    private val journal: Journal,
) {
    suspend fun submit(action: GateAction, answer: String, now: Long) {
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all suites PASS (11 new tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anastasiia/appblocker/core/GateCoordinator.kt app/src/test/java/com/anastasiia/appblocker/core/GateCoordinatorTest.kt
git commit -m "feat: gate coordinator — submit/cancel/confirm/decline/lapse lifecycle"
```

---

### Task 5: ViewModel wiring, navigation, and the Gate question screen

**Files:**
- Create: `app/src/main/java/com/anastasiia/appblocker/ui/GateScreen.kt`
- Modify: `app/src/main/java/com/anastasiia/appblocker/ui/MainViewModel.kt`, `app/src/main/java/com/anastasiia/appblocker/MainActivity.kt`

**Interfaces:**
- Consumes: `GateCoordinator`, `Journal`, `GateState`, `GATE_QUESTIONS`, `isValidGateAnswer`, `GATE_MIN_ANSWER_CHARS` (Tasks 1–4).
- Produces (used by Tasks 6–7): on `MainViewModel` — `val gateState: StateFlow<GateState>`, `fun submitGateAnswer(action: GateAction, answer: String)`, `fun cancelPending()`, `fun confirmPending()`, `fun declinePending()`, `fun lapseIfExpired()`, `suspend fun pastAnswers(question: String): List<JournalEntry>`; composable `GateScreen(viewModel: MainViewModel, action: GateAction, onDone: () -> Unit, onBack: () -> Unit)`; `MainActivity` gains `Screen.Gate` and `Screen.Confirm` plus a `gateAction` state passed to `GateScreen`.

- [ ] **Step 1: Extend `MainViewModel`**

Add imports: `com.anastasiia.appblocker.core.GateAction`, `GateCoordinator`, `GateState`, `Journal`, `JournalEntry`, `java.io.File`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.withContext`.

Add inside the class:

```kotlin
    private val journal = Journal(File(app.filesDir, "journal.jsonl"))
    private val gate = GateCoordinator(repository, journal)

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
```

- [ ] **Step 2: Create `GateScreen.kt`**

Requirements this code implements: mandatory typed answer ≥ 50 trimmed chars; paste blocked two ways (toolbar hidden via `LocalTextToolbar`, and single-edit insertions of more than 20 characters rejected — keyboard word suggestions stay usable); live counter "N / 50"; low-emphasis "Past answers" toggle shown only when this question has history; back = abandon (no record).

```kotlin
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
    val past by produceState(initialValue = emptyList(), question) {
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
```

- [ ] **Step 3: Add the two screens to `MainActivity`**

Replace the `Screen` enum and `setContent` body:

```kotlin
private enum class Screen { Main, EditApps, Gate, Confirm }
```

```kotlin
                val viewModel: MainViewModel = viewModel()
                var screen by remember { mutableStateOf(Screen.Main) }
                var gateAction by remember { mutableStateOf<GateAction?>(null) }
                when (screen) {
                    Screen.Main -> MainScreen(
                        viewModel,
                        onEditApps = { screen = Screen.EditApps },
                        onGate = { action ->
                            gateAction = action
                            screen = Screen.Gate
                        },
                        onConfirm = { screen = Screen.Confirm },
                    )
                    Screen.EditApps -> EditAppsScreen(
                        viewModel,
                        onDone = { screen = Screen.Main },
                        onGate = { action ->
                            gateAction = action
                            screen = Screen.Gate
                        },
                    )
                    Screen.Gate -> GateScreen(
                        viewModel,
                        action = gateAction ?: GateAction.Disable,
                        onDone = { screen = Screen.Main },
                        onBack = { screen = Screen.Main },
                    )
                    Screen.Confirm -> ConfirmScreen(viewModel, onDone = { screen = Screen.Main })
                }
```

Add import `com.anastasiia.appblocker.core.GateAction`, `com.anastasiia.appblocker.ui.GateScreen`, `com.anastasiia.appblocker.ui.ConfirmScreen`.

Note: `MainScreen`'s `onGate`/`onConfirm` parameters and `EditAppsScreen`'s `onGate` parameter are added in Tasks 6–7; `ConfirmScreen` is created in Task 6. To keep every task compiling on its own, add the parameters in this task as no-op-defaulted parameters (`onGate: (GateAction) -> Unit = {}`, `onConfirm: () -> Unit = {}`) on both screens now, and create a placeholder-free `ConfirmScreen` in Task 6 — i.e., in THIS task, reference only `MainScreen(viewModel, onEditApps = ...)` and `EditAppsScreen(viewModel, onDone = ...)` as they exist today, and add `Screen.Gate` only. Concretely: in this task `MainActivity` adds `Screen.Gate` and `gateAction`, and wires `GateScreen`; the `onGate`/`onConfirm`/`Screen.Confirm` wiring shown above is completed in Task 6. Until Task 6 there is no UI path into `GateScreen` — that is fine; the build stays green.

- [ ] **Step 4: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anastasiia/appblocker/ui/GateScreen.kt app/src/main/java/com/anastasiia/appblocker/ui/MainViewModel.kt app/src/main/java/com/anastasiia/appblocker/MainActivity.kt
git commit -m "feat: gate question screen + viewmodel wiring"
```

---

### Task 6: Pending line, Confirm screen, urges counter, lapse tick

**Files:**
- Create: `app/src/main/java/com/anastasiia/appblocker/ui/ConfirmScreen.kt`
- Modify: `app/src/main/java/com/anastasiia/appblocker/ui/MainScreen.kt`, `app/src/main/java/com/anastasiia/appblocker/MainActivity.kt`

**Interfaces:**
- Consumes: `gateState`, `gatePhase`, `GatePhase`, `formatClock`, `CONFIRM_WINDOW_MS`, viewmodel methods from Task 5.
- Produces: `ConfirmScreen(viewModel: MainViewModel, onDone: () -> Unit)`; `MainScreen` gains parameters `onGate: (GateAction) -> Unit` and `onConfirm: () -> Unit`.

- [ ] **Step 1: Create `ConfirmScreen.kt`**

Shows the just-written answer and "Still want this?". Yes → `confirmPending()` + toast "Done — resumes at H:MM." for a pause (plain "Done." for disable/remove); No → `declinePending()` + toast "Nice. That one passed."

```kotlin
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
```

- [ ] **Step 2: Add pending line, urges counter, and lapse tick to `MainScreen`**

Change the signature to:

```kotlin
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onEditApps: () -> Unit,
    onGate: (GateAction) -> Unit = {},
    onConfirm: () -> Unit = {},
) {
```

Add `val gateState by viewModel.gateState.collectAsState()` next to the existing `state` collection, and imports for `GateAction`, `GatePhase`, `gatePhase`, `formatClock`, `CONFIRM_WINDOW_MS`.

In the existing 1-second `LaunchedEffect` loop, after `serviceEnabled = ...`, add:

```kotlin
            viewModel.lapseIfExpired()
```

Directly under the "Blocking" toggle `Row`, add the pending-request line and the counter:

```kotlin
            gateState.pending?.let { pending ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (gatePhase(pending, now)) {
                        GatePhase.WAITING -> {
                            Text("Noted. Ready at ${formatClock(pending.readyAt)}.")
                            TextButton(onClick = { viewModel.cancelPending() }) { Text("Cancel") }
                        }
                        GatePhase.READY -> {
                            Text("Ready — confirm before ${formatClock(pending.readyAt + CONFIRM_WINDOW_MS)}")
                            TextButton(onClick = onConfirm) { Text("Confirm") }
                        }
                        GatePhase.EXPIRED -> {} // cleared by the next lapse tick
                    }
                }
            }
            if (gateState.urgesOutlasted > 0) {
                Text(
                    "Urges outlasted: ${gateState.urgesOutlasted}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
```

- [ ] **Step 3: Wire `Screen.Confirm` and the `onGate`/`onConfirm` callbacks in `MainActivity`**

Complete the `MainActivity` wiring exactly as shown in Task 5 Step 3's code block (add `Screen.Confirm` to the enum if not yet, pass `onGate`/`onConfirm` to `MainScreen`, route `Screen.Confirm -> ConfirmScreen(viewModel, onDone = { screen = Screen.Main })`). `EditAppsScreen`'s `onGate` parameter arrives in Task 7 — don't pass it yet.

- [ ] **Step 4: Build and unit tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anastasiia/appblocker/ui/ConfirmScreen.kt app/src/main/java/com/anastasiia/appblocker/ui/MainScreen.kt app/src/main/java/com/anastasiia/appblocker/MainActivity.kt
git commit -m "feat: pending line, confirm screen, urges counter, lapse tick"
```

---

### Task 7: Intercept unblock-ward actions + on-device verification

**Files:**
- Modify: `app/src/main/java/com/anastasiia/appblocker/ui/MainScreen.kt` (toggle + pause buttons), `app/src/main/java/com/anastasiia/appblocker/ui/EditAppsScreen.kt` (removal gating), `README.md`

**Interfaces:**
- Consumes: `onGate` callbacks (Tasks 5–6), `GateAction`.
- Produces: the complete feature; no new interfaces.

- [ ] **Step 1: Gate the master toggle (MainScreen)**

Replace the toggle's `onCheckedChange`:

```kotlin
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { checked ->
                        if (checked) viewModel.setEnabled(true) else onGate(GateAction.Disable)
                    },
                )
```

- [ ] **Step 2: Gate the pause buttons (MainScreen)**

Replace the pause-button row's `onClick`. Blocking off → pause is moot, keep the old instant path; blocking on (even while already paused — extending is unblock-ward) → gate:

```kotlin
                    PAUSE_MINUTES.forEach { minutes ->
                        Button(onClick = {
                            if (state.enabled) onGate(GateAction.Pause(minutes))
                            else viewModel.pauseFor(minutes)
                        }) { Text("${minutes}m") }
                    }
```

- [ ] **Step 3: Gate removals in `EditAppsScreen`**

Change the signature to `fun EditAppsScreen(viewModel: MainViewModel, onDone: () -> Unit, onGate: (GateAction) -> Unit = {})`, add imports for `GateAction`, read `val enabled = viewModel.state.collectAsState().value.enabled` (fold into the existing `state` read: `val state = viewModel.state.collectAsState().value`, `val saved = state.blockedPackages`), and replace the Save button's `onClick`:

```kotlin
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
```

In `MainActivity`, pass `onGate` to `EditAppsScreen` (the callback already exists from Task 5's wiring).

- [ ] **Step 4: Update `README.md`**

Replace the backlog line:

```markdown
Backlog: domain blocking, schedules.
```

And add after the pause sentence in the intro paragraph:

```markdown
Unblock-ward actions (pause, disable, removing apps) pass through the
Unblock Gate: answer one reflective question (50+ chars, typed), wait
(5 min; 30 min for full disable), then confirm within 30 min — or the
request lapses and blocking continues. Spec:
docs/superpowers/specs/2026-08-14-unblock-gate-design.md
```

- [ ] **Step 5: Build, install, and run on-device verification**

Run: `./gradlew installDebug` (device connected, USB debugging on).

Verify with blocking ON and an expendable app (e.g. Instagram) in the blocked list:

1. **Pause path:** Advanced → "5m" → Gate screen appears (does NOT pause). Type < 50 chars → Submit disabled. Attempt paste (long-press in field) → no paste menu. Type ≥ 50 chars → Submit → main screen shows "Noted. Ready at H:MM." with Cancel. Open the blocked app → still blocked. After 5 min the line flips to "Ready — confirm before H:MM". Confirm → answer shown, "Still want this?" → Yes → toast "Done — resumes at H:MM.", pause countdown runs, blocked app opens. Wait out the pause → app blocked again.
2. **Decline path:** request another pause, wait 5 min, Confirm → "No, I'm good" → toast "Nice. That one passed.", "Urges outlasted: 1" appears, app still blocked.
3. **Cancel path:** request a pause, tap Cancel during the wait → counter increments, app still blocked.
4. **Replace + question rotation:** request a pause (note the question), then request another → new question (next in rotation), old request gone, counter unchanged from replacement. On a repeated question, "Past answers" appears and shows the earlier answer.
5. **Disable path:** toggle Blocking off → Gate screen; submit → "Noted. Ready at H:MM." ~30 min out; toggle stays ON during the wait.
6. **Lapse path (shortened):** temporarily set `CONFIRM_WINDOW_MS = 60_000L` and the pause wait to `60_000L` in `Gate.kt`, `installDebug`, request a pause, let it become ready, ignore it for > 1 min, reopen the app → pending line gone, counter incremented. **Revert the constants and reinstall** before the next step.
7. **Reboot mid-wait:** request a pause, reboot the phone, reopen the app → pending line still there with the same ready time.
8. **Removal path:** Edit → uncheck one app → Save → Gate screen; confirm after the wait → app leaves the blocked list. Checking a new app while unchecking another applies the addition immediately.

Expected: every step behaves as written; any deviation is a stop-and-fix.

- [ ] **Step 6: Full test suite, then commit**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

```bash
git add app/src/main/java/com/anastasiia/appblocker/ui/MainScreen.kt app/src/main/java/com/anastasiia/appblocker/ui/EditAppsScreen.kt app/src/main/java/com/anastasiia/appblocker/MainActivity.kt README.md
git commit -m "feat: gate all unblock-ward actions (toggle, pause, app removal)"
```

---

## Plan Self-Review (completed)

- **Spec coverage:** gated actions incl. edit-screen semantics (Task 7), flow steps 1–3 (Tasks 5–6), questions/rotation/past answers (Tasks 1, 4, 5), journal (Task 2), urges counter incl. replaced-doesn't-count (Tasks 3–4, 6), copy set (Tasks 5–6), data model (Task 3), lifecycle incl. lapse-lazily and confirm-after-expiry (Task 4), edge cases: reboot (persisted timestamps), paste (Task 5), stale packages (Task 7), on-device verification (Task 7). Out-of-scope items absent by construction.
- **Placeholder scan:** none — all code blocks are complete.
- **Type consistency:** `GateAction`/`PendingRequest`/`GatePhase`/`GateState`/`GateOutcome`/`JournalEntry` names and signatures match across Tasks 1–7; viewmodel method names in Tasks 6–7 match Task 5's definitions.
