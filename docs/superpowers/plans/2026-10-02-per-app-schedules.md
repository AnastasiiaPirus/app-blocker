# Per-App Schedules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let each app carry its own weekly time windows during which it is blocked, with the same block screen, pause, and Unblock Gate as a full block.

**Architecture:** A new pure module `core/Schedule.kt` owns the window model, the wall-clock containment rule, the coverage comparison that decides whether an edit is unblock-ward, and the JSON persistence format. `BlockerState` gains a `schedules` map stored under one DataStore key; `shouldBlock` gains one clause; the gate gains one action. The UI adds a per-app schedule editor reached from the edit-apps list and shows window summaries on the main screen.

**Tech Stack:** Kotlin, Jetpack Compose + Material 3 (BOM 2025.06.01, Material 3 1.3.x: `TimePicker` + `rememberTimePickerState` are available, `TimePickerDialog` is not), DataStore Preferences, `org.json` (platform class at runtime, `libs.json` in unit tests), `java.time` (minSdk 35), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-02-per-app-schedules-design.md`

## Global Constraints

- Blocking decisions stay a pure function; the service hot path does no disk I/O and sets no alarms (spec: "no alarms, no jobs").
- Windows are wall clock in the device zone; `start`/`end` are minutes after local midnight (0–1439); `end < start` crosses midnight; start inclusive, end exclusive.
- Days are ISO weekday numbers 1 = Monday … 7 = Sunday and name the window's **start** day.
- One new DataStore key only: `schedules` (JSON string). An app with an empty window list is dropped from the map on save.
- Always (full block) wins over a schedule if both are ever set; the UI never produces both.
- Any edit that leaves a (weekday, minute) covered before and uncovered after is gated with a 5-minute wait while `enabled`; additions apply instantly.
- Copy stays friendly and minimal; no new dependencies beyond AndroidX + the platform `org.json`.
- Unit tests run with `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` then `./gradlew :app:testDebugUnitTest`. Device install: `./gradlew :app:installDebug`.
- Commit messages end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

1. A window with `start == end` must cover nothing and must not be saveable; a person would otherwise expect "all day" or "nothing" and get one of them silently. (Test in Task 1 and Task 7.)
2. A window with no days must cover nothing and must not be saveable. (Test in Task 1 and Task 7.)
3. Malformed or hand-edited JSON under the `schedules` key must decode to an empty map, never crash the service. (Test in Task 3.)
4. An app that is both in `blockedPackages` and in `schedules` must be blocked at all times, and saving from the editor must normalize it to one or the other. (Test in Task 5 and Task 7.)
5. A pause or the master toggle off must open a scheduled app even inside its window; a scheduled app outside its window must open even when blocking is on. (Test in Task 5.)

---

### Task 1: Window model and containment

**Files:**
- Create: `app/src/main/java/com/anastasiia/appblocker/core/Schedule.kt`
- Test: `app/src/test/java/com/anastasiia/appblocker/core/ScheduleTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `data class ScheduleWindow(val days: Set<Int>, val startMinute: Int, val endMinute: Int)`, `fun windowContains(window: ScheduleWindow, day: Int, minuteOfDay: Int): Boolean`, `fun isScheduledNow(windows: List<ScheduleWindow>, at: ZonedDateTime): Boolean`, `fun previousDay(day: Int): Int`, `fun nextDay(day: Int): Int`, `const val MINUTES_PER_DAY = 1440`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.anastasiia.appblocker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTest {
    private val weekdays = setOf(1, 2, 3, 4, 5)
    private val morning = ScheduleWindow(days = weekdays, startMinute = 8 * 60, endMinute = 11 * 60)
    // Listed on Monday; runs Mon 21:30 -> Tue 01:00.
    private val night = ScheduleWindow(days = setOf(1), startMinute = 21 * 60 + 30, endMinute = 60)

    @Test fun startIsInclusiveEndIsExclusive() {
        assertTrue(windowContains(morning, day = 1, minuteOfDay = 8 * 60))
        assertTrue(windowContains(morning, day = 1, minuteOfDay = 11 * 60 - 1))
        assertFalse(windowContains(morning, day = 1, minuteOfDay = 11 * 60))
        assertFalse(windowContains(morning, day = 1, minuteOfDay = 8 * 60 - 1))
    }

    @Test fun dayNotListedIsOutside() {
        assertFalse(windowContains(morning, day = 6, minuteOfDay = 9 * 60))
        assertFalse(windowContains(morning, day = 7, minuteOfDay = 9 * 60))
    }

    @Test fun crossingMidnightCoversLateStartDayAndEarlyNextDay() {
        assertTrue(windowContains(night, day = 1, minuteOfDay = 21 * 60 + 30))
        assertTrue(windowContains(night, day = 1, minuteOfDay = 23 * 60 + 59))
        assertTrue(windowContains(night, day = 2, minuteOfDay = 0))
        assertTrue(windowContains(night, day = 2, minuteOfDay = 59))
        assertFalse(windowContains(night, day = 2, minuteOfDay = 60))
        assertFalse(windowContains(night, day = 2, minuteOfDay = 22 * 60)) // Tue night not listed
        assertFalse(windowContains(night, day = 1, minuteOfDay = 30)) // Mon 00:30 belongs to Sunday's window
    }

    @Test fun sundayWindowWrapsIntoMonday() {
        val sunNight = night.copy(days = setOf(7))
        assertTrue(windowContains(sunNight, day = 7, minuteOfDay = 22 * 60))
        assertTrue(windowContains(sunNight, day = 1, minuteOfDay = 30))
        assertFalse(windowContains(sunNight, day = 1, minuteOfDay = 22 * 60))
    }

    @Test fun zeroLengthWindowCoversNothing() {
        val empty = ScheduleWindow(days = setOf(1, 2, 3, 4, 5, 6, 7), startMinute = 600, endMinute = 600)
        for (day in 1..7) for (m in listOf(0, 599, 600, 601, 1439)) assertFalse(windowContains(empty, day, m))
    }

    @Test fun noDaysCoversNothing() {
        val noDays = ScheduleWindow(days = emptySet(), startMinute = 0, endMinute = 1439)
        for (day in 1..7) assertFalse(windowContains(noDays, day, 720))
    }

    @Test fun previousAndNextDayWrap() {
        assertEquals(7, previousDay(1))
        assertEquals(1, previousDay(2))
        assertEquals(1, nextDay(7))
        assertEquals(2, nextDay(1))
    }

    @Test fun isScheduledNowResolvesLocalWallClock() {
        val toronto = ZoneId.of("America/Toronto")
        // Thu 2026-10-01 09:30 Toronto
        val inside = ZonedDateTime.of(2026, 10, 1, 9, 30, 0, 0, toronto)
        val outside = ZonedDateTime.of(2026, 10, 1, 11, 0, 0, 0, toronto)
        val saturday = ZonedDateTime.of(2026, 10, 3, 9, 30, 0, 0, toronto)
        assertTrue(isScheduledNow(listOf(morning), inside))
        assertFalse(isScheduledNow(listOf(morning), outside))
        assertFalse(isScheduledNow(listOf(morning), saturday))
        assertFalse(isScheduledNow(emptyList(), inside))
    }

    @Test fun isScheduledNowAnyWindowMatches() {
        val toronto = ZoneId.of("America/Toronto")
        // Tue 2026-10-06 00:30 Toronto: inside Monday's night window, outside morning.
        val lateMonday = ZonedDateTime.of(2026, 10, 6, 0, 30, 0, 0, toronto)
        assertTrue(isScheduledNow(listOf(morning, night), lateMonday))
        assertFalse(isScheduledNow(listOf(morning), lateMonday))
    }

    @Test fun isScheduledNowUsesTheGivenZoneNotUtc() {
        // 2026-10-01 13:30 UTC is 09:30 in Toronto (inside) but 13:30 in UTC (outside).
        val utcInstant = ZonedDateTime.of(2026, 10, 1, 13, 30, 0, 0, ZoneId.of("UTC"))
        assertFalse(isScheduledNow(listOf(morning), utcInstant))
        assertTrue(isScheduledNow(listOf(morning), utcInstant.withZoneSameInstant(ZoneId.of("America/Toronto"))))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home && ./gradlew :app:testDebugUnitTest --tests '*ScheduleTest*' 2>&1 | grep -E "^e: |BUILD"`
Expected: compile errors `Unresolved reference 'ScheduleWindow'` etc., BUILD FAILED.

- [ ] **Step 3: Write the model and containment**

```kotlin
package com.anastasiia.appblocker.core

import java.time.ZonedDateTime

const val MINUTES_PER_DAY = 1440

/**
 * A weekly blocking window. [days] are ISO weekdays (1 = Monday … 7 = Sunday)
 * naming the day the window STARTS on; [startMinute]/[endMinute] are minutes
 * after local midnight. Start is inclusive, end exclusive. `endMinute <
 * startMinute` means the window crosses midnight and ends on the next day.
 * `endMinute == startMinute` or an empty [days] covers nothing.
 */
data class ScheduleWindow(val days: Set<Int>, val startMinute: Int, val endMinute: Int) {
    val crossesMidnight: Boolean get() = endMinute < startMinute
    val isEmpty: Boolean get() = days.isEmpty() || endMinute == startMinute
}

fun previousDay(day: Int): Int = if (day == 1) 7 else day - 1

fun nextDay(day: Int): Int = if (day == 7) 1 else day + 1

fun windowContains(window: ScheduleWindow, day: Int, minuteOfDay: Int): Boolean {
    if (window.isEmpty) return false
    return if (!window.crossesMidnight) {
        day in window.days && minuteOfDay >= window.startMinute && minuteOfDay < window.endMinute
    } else {
        (day in window.days && minuteOfDay >= window.startMinute) ||
            (previousDay(day) in window.days && minuteOfDay < window.endMinute)
    }
}

/** True when any window covers the local wall-clock instant [at]. */
fun isScheduledNow(windows: List<ScheduleWindow>, at: ZonedDateTime): Boolean {
    if (windows.isEmpty()) return false
    val day = at.dayOfWeek.value
    val minute = at.hour * 60 + at.minute
    return windows.any { windowContains(it, day, minute) }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduleTest*' 2>&1 | grep -E "^e: |BUILD"` then `grep -oE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' app/build/test-results/testDebugUnitTest/TEST-com.anastasiia.appblocker.core.ScheduleTest.xml`
Expected: BUILD SUCCESSFUL, `tests="10" … failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anastasiia/appblocker/core/Schedule.kt app/src/test/java/com/anastasiia/appblocker/core/ScheduleTest.kt
git commit -m "feat(schedule): window model and wall-clock containment

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Coverage comparison for the gate

**Files:**
- Modify: `app/src/main/java/com/anastasiia/appblocker/core/Schedule.kt`
- Test: `app/src/test/java/com/anastasiia/appblocker/core/ScheduleTest.kt`

**Interfaces:**
- Consumes: `ScheduleWindow`, `windowContains`, `MINUTES_PER_DAY` (Task 1).
- Produces: `fun coverage(windows: List<ScheduleWindow>): BooleanArray` (size 7 × 1440, index `(day - 1) * 1440 + minute`), `fun fullCoverage(): BooleanArray`, `fun coverageReduced(before: BooleanArray, after: BooleanArray): Boolean`, `fun coverageOf(always: Boolean, windows: List<ScheduleWindow>): BooleanArray`.

- [ ] **Step 1: Write the failing tests** (append inside `ScheduleTest`)

```kotlin
    private val allDays = setOf(1, 2, 3, 4, 5, 6, 7)

    @Test fun coverageMarksExactlyTheWindowMinutes() {
        val c = coverage(listOf(ScheduleWindow(setOf(3), 600, 602)))
        assertEquals(2, c.count { it })
        assertTrue(c[(3 - 1) * MINUTES_PER_DAY + 600])
        assertTrue(c[(3 - 1) * MINUTES_PER_DAY + 601])
        assertFalse(c[(3 - 1) * MINUTES_PER_DAY + 602])
    }

    @Test fun coverageOfCrossingWindowSpillsIntoNextDay() {
        val c = coverage(listOf(ScheduleWindow(setOf(7), 23 * 60, 60)))
        assertTrue(c[(7 - 1) * MINUTES_PER_DAY + 23 * 60 + 30])
        assertTrue(c[(1 - 1) * MINUTES_PER_DAY + 30])
        assertFalse(c[(1 - 1) * MINUTES_PER_DAY + 60])
        assertEquals(120, c.count { it })
    }

    @Test fun pureAdditionIsNotAReduction() {
        val before = listOf(ScheduleWindow(weekdays, 480, 660))
        val after = before + ScheduleWindow(allDays, 21 * 60 + 30, 60)
        assertFalse(coverageReduced(coverage(before), coverage(after)))
    }

    @Test fun shrinkingAWindowIsAReduction() {
        val before = listOf(ScheduleWindow(weekdays, 480, 660))
        val after = listOf(ScheduleWindow(weekdays, 480, 600))
        assertTrue(coverageReduced(coverage(before), coverage(after)))
    }

    @Test fun removingADayIsAReduction() {
        val before = listOf(ScheduleWindow(weekdays, 480, 660))
        val after = listOf(ScheduleWindow(setOf(1, 2, 3, 4), 480, 660))
        assertTrue(coverageReduced(coverage(before), coverage(after)))
    }

    @Test fun deletingAllWindowsIsAReduction() {
        assertTrue(coverageReduced(coverage(listOf(ScheduleWindow(weekdays, 480, 660))), coverage(emptyList())))
    }

    @Test fun identicalListsAreNotAReduction() {
        val w = listOf(ScheduleWindow(weekdays, 480, 660), ScheduleWindow(allDays, 1290, 60))
        assertFalse(coverageReduced(coverage(w), coverage(w)))
        assertFalse(coverageReduced(coverage(w), coverage(w.reversed())))
    }

    @Test fun alwaysToScheduledIsAReductionAndBackIsNot() {
        val windows = listOf(ScheduleWindow(allDays, 480, 660))
        assertTrue(coverageReduced(coverageOf(always = true, windows = emptyList()), coverageOf(always = false, windows = windows)))
        assertFalse(coverageReduced(coverageOf(always = false, windows = windows), coverageOf(always = true, windows = emptyList())))
        assertFalse(coverageReduced(fullCoverage(), fullCoverage()))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduleTest*' 2>&1 | grep -E "^e: |BUILD"`
Expected: `Unresolved reference 'coverage'`, BUILD FAILED.

- [ ] **Step 3: Implement coverage** (append to `Schedule.kt`)

```kotlin
/**
 * One boolean per (weekday, minute) of the week, true where any window
 * blocks. Index is `(day - 1) * MINUTES_PER_DAY + minute`. Used to decide
 * whether an edit removes blocking somewhere (gated) or only adds it.
 */
fun coverage(windows: List<ScheduleWindow>): BooleanArray {
    val grid = BooleanArray(7 * MINUTES_PER_DAY)
    for (w in windows) {
        if (w.isEmpty) continue
        for (day in w.days) {
            if (!w.crossesMidnight) {
                for (m in w.startMinute until w.endMinute) grid[(day - 1) * MINUTES_PER_DAY + m] = true
            } else {
                for (m in w.startMinute until MINUTES_PER_DAY) grid[(day - 1) * MINUTES_PER_DAY + m] = true
                val next = nextDay(day)
                for (m in 0 until w.endMinute) grid[(next - 1) * MINUTES_PER_DAY + m] = true
            }
        }
    }
    return grid
}

fun fullCoverage(): BooleanArray = BooleanArray(7 * MINUTES_PER_DAY) { true }

/** Coverage of an app's blocking configuration: Always covers the whole week. */
fun coverageOf(always: Boolean, windows: List<ScheduleWindow>): BooleanArray =
    if (always) fullCoverage() else coverage(windows)

/** True when some minute was blocked [before] and is not blocked [after]. */
fun coverageReduced(before: BooleanArray, after: BooleanArray): Boolean {
    for (i in before.indices) if (before[i] && !after[i]) return true
    return false
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduleTest*' 2>&1 | grep -E "^e: |BUILD"`; check the XML shows `tests="18"` and zero failures.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anastasiia/appblocker/core/Schedule.kt app/src/test/java/com/anastasiia/appblocker/core/ScheduleTest.kt
git commit -m "feat(schedule): weekly coverage grid and reduction check

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: JSON persistence format and display helpers

**Files:**
- Modify: `app/src/main/java/com/anastasiia/appblocker/core/Schedule.kt`
- Test: `app/src/test/java/com/anastasiia/appblocker/core/ScheduleTest.kt`

**Interfaces:**
- Consumes: `ScheduleWindow`, `windowContains`, `nextDay` (Task 1).
- Produces: `fun encodeWindows(windows: List<ScheduleWindow>): String`, `fun decodeWindows(json: String): List<ScheduleWindow>`, `fun encodeSchedules(map: Map<String, List<ScheduleWindow>>): String`, `fun decodeSchedules(json: String?): Map<String, List<ScheduleWindow>>`, `fun formatMinute(minuteOfDay: Int): String` ("08:05"), `fun formatWindow(window: ScheduleWindow): String` ("08:00–11:00 · daily" / "· Mon–Fri" / "· Sat, Sun"), `fun describeWindows(windows: List<ScheduleWindow>): String`, `fun nextWindowStart(windows: List<ScheduleWindow>, day: Int, minuteOfDay: Int): Int?` (minute-of-day the next window starts, searching forward up to a week; null if none), `val MORNING_PRESET: ScheduleWindow`, `val NIGHT_PRESET: ScheduleWindow`.

- [ ] **Step 1: Write the failing tests** (append inside `ScheduleTest`)

```kotlin
    @Test fun windowsJsonRoundTrips() {
        val w = listOf(ScheduleWindow(weekdays, 480, 660), ScheduleWindow(setOf(6, 7), 21 * 60 + 30, 60))
        assertEquals(w, decodeWindows(encodeWindows(w)))
        assertEquals(emptyList<ScheduleWindow>(), decodeWindows(encodeWindows(emptyList())))
    }

    @Test fun schedulesJsonRoundTripsAndDropsEmptyEntries() {
        val map = mapOf(
            "com.facebook.katana" to listOf(ScheduleWindow(allDays, 480, 660)),
            "com.google.android.youtube" to listOf(ScheduleWindow(allDays, 1290, 60), ScheduleWindow(weekdays, 720, 840)),
            "com.example.empty" to emptyList(),
        )
        val decoded = decodeSchedules(encodeSchedules(map))
        assertEquals(map - "com.example.empty", decoded)
        assertEquals(emptyMap<String, List<ScheduleWindow>>(), decodeSchedules(encodeSchedules(emptyMap())))
    }

    @Test fun malformedJsonDecodesToEmpty() {
        assertEquals(emptyMap<String, List<ScheduleWindow>>(), decodeSchedules(null))
        assertEquals(emptyMap<String, List<ScheduleWindow>>(), decodeSchedules(""))
        assertEquals(emptyMap<String, List<ScheduleWindow>>(), decodeSchedules("not json"))
        assertEquals(emptyMap<String, List<ScheduleWindow>>(), decodeSchedules("[1,2,3]"))
        assertEquals(emptyList<ScheduleWindow>(), decodeWindows("{\"oops\":1}"))
        // A window with out-of-range fields is skipped, the rest survive.
        val mixed = """[{"days":[1],"start":480,"end":660},{"days":[9],"start":480,"end":660},{"days":[2],"start":-5,"end":660},{"days":[3],"start":100,"end":2000}]"""
        assertEquals(listOf(ScheduleWindow(setOf(1), 480, 660)), decodeWindows(mixed))
    }

    @Test fun formatsMinutesAndWindows() {
        assertEquals("08:05", formatMinute(8 * 60 + 5))
        assertEquals("00:00", formatMinute(0))
        assertEquals("23:59", formatMinute(1439))
        assertEquals("08:00–11:00 · daily", formatWindow(ScheduleWindow(allDays, 480, 660)))
        assertEquals("08:00–11:00 · Mon–Fri", formatWindow(ScheduleWindow(weekdays, 480, 660)))
        assertEquals("21:30–01:00 · Sat, Sun", formatWindow(ScheduleWindow(setOf(6, 7), 1290, 60)))
        assertEquals("09:00–10:00 · Mon, Wed, Fri", formatWindow(ScheduleWindow(setOf(1, 3, 5), 540, 600)))
        assertEquals("08:00–11:00 · daily, 21:30–01:00 · Sat, Sun",
            describeWindows(listOf(ScheduleWindow(allDays, 480, 660), ScheduleWindow(setOf(6, 7), 1290, 60))))
    }

    @Test fun nextWindowStartSearchesForwardAcrossTheWeek() {
        val w = listOf(ScheduleWindow(weekdays, 480, 660), ScheduleWindow(setOf(6), 1290, 60))
        assertEquals(480, nextWindowStart(w, day = 1, minuteOfDay = 300))   // Mon 05:00 -> Mon 08:00
        assertEquals(1290, nextWindowStart(w, day = 5, minuteOfDay = 700))  // Fri 11:40 -> Sat 21:30
        assertEquals(480, nextWindowStart(w, day = 7, minuteOfDay = 120))   // Sun 02:00 -> Mon 08:00
        assertEquals(null, nextWindowStart(emptyList(), day = 1, minuteOfDay = 0))
        assertEquals(480, nextWindowStart(w, day = 1, minuteOfDay = 480))   // currently inside: its own start
    }

    @Test fun presetsMatchTheAnalysis() {
        assertEquals(ScheduleWindow(allDays, 8 * 60, 11 * 60), MORNING_PRESET)
        assertEquals(ScheduleWindow(allDays, 21 * 60 + 30, 60), NIGHT_PRESET)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduleTest*' 2>&1 | grep -E "^e: |BUILD"`
Expected: `Unresolved reference 'encodeWindows'`, BUILD FAILED.

- [ ] **Step 3: Implement persistence and helpers** (append to `Schedule.kt`; add `import org.json.JSONArray` and `import org.json.JSONObject` at the top)

```kotlin
val ALL_DAYS: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7)
val MORNING_PRESET = ScheduleWindow(ALL_DAYS, 8 * 60, 11 * 60)
val NIGHT_PRESET = ScheduleWindow(ALL_DAYS, 21 * 60 + 30, 60)

private fun ScheduleWindow.isValid(): Boolean =
    days.all { it in 1..7 } && startMinute in 0 until MINUTES_PER_DAY && endMinute in 0 until MINUTES_PER_DAY

fun encodeWindows(windows: List<ScheduleWindow>): String = windowsToJson(windows).toString()

private fun windowsToJson(windows: List<ScheduleWindow>): JSONArray {
    val arr = JSONArray()
    for (w in windows) {
        arr.put(
            JSONObject()
                .put("days", JSONArray(w.days.sorted()))
                .put("start", w.startMinute)
                .put("end", w.endMinute),
        )
    }
    return arr
}

fun decodeWindows(json: String): List<ScheduleWindow> =
    runCatching { windowsFromJson(JSONArray(json)) }.getOrDefault(emptyList())

private fun windowsFromJson(arr: JSONArray): List<ScheduleWindow> {
    val out = ArrayList<ScheduleWindow>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val daysArr = o.optJSONArray("days") ?: continue
        val days = HashSet<Int>()
        for (j in 0 until daysArr.length()) days.add(daysArr.optInt(j, -1))
        val w = ScheduleWindow(days, o.optInt("start", -1), o.optInt("end", -1))
        if (w.isValid()) out.add(w)
    }
    return out
}

fun encodeSchedules(map: Map<String, List<ScheduleWindow>>): String {
    val o = JSONObject()
    for ((pkg, windows) in map) if (windows.isNotEmpty()) o.put(pkg, windowsToJson(windows))
    return o.toString()
}

/** Tolerant: anything unreadable yields an empty map rather than a crash in the service. */
fun decodeSchedules(json: String?): Map<String, List<ScheduleWindow>> {
    if (json.isNullOrBlank()) return emptyMap()
    return runCatching {
        val o = JSONObject(json)
        val out = HashMap<String, List<ScheduleWindow>>()
        for (pkg in o.keys()) {
            val windows = o.optJSONArray(pkg)?.let(::windowsFromJson).orEmpty()
            if (windows.isNotEmpty()) out[pkg] = windows
        }
        out
    }.getOrDefault(emptyMap())
}

fun formatMinute(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

private val DAY_ABBREVIATIONS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun formatDays(days: Set<Int>): String = when {
    days == ALL_DAYS -> "daily"
    days == setOf(1, 2, 3, 4, 5) -> "Mon–Fri"
    else -> days.sorted().joinToString(", ") { DAY_ABBREVIATIONS[it - 1] }
}

fun formatWindow(window: ScheduleWindow): String =
    "${formatMinute(window.startMinute)}–${formatMinute(window.endMinute)} · ${formatDays(window.days)}"

fun describeWindows(windows: List<ScheduleWindow>): String = windows.joinToString(", ", transform = ::formatWindow)

/**
 * Minute-of-day at which the next window begins, scanning forward from
 * (day, minuteOfDay) for up to one week. A window that is active right now
 * reports its own start. Null when there are no windows.
 */
fun nextWindowStart(windows: List<ScheduleWindow>, day: Int, minuteOfDay: Int): Int? {
    if (windows.none { !it.isEmpty }) return null
    var d = day
    var m = minuteOfDay
    repeat(7 * MINUTES_PER_DAY) {
        for (w in windows) if (!w.isEmpty && d in w.days && w.startMinute == m) return m
        for (w in windows) if (windowContains(w, d, m) && !(d in w.days && w.startMinute == m)) {
            // Inside a window that started earlier: report that window's start.
            return w.startMinute
        }
        m++
        if (m == MINUTES_PER_DAY) { m = 0; d = nextDay(d) }
    }
    return null
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduleTest*' 2>&1 | grep -E "^e: |BUILD"`; XML should show `tests="24"`, zero failures.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anastasiia/appblocker/core/Schedule.kt app/src/test/java/com/anastasiia/appblocker/core/ScheduleTest.kt
git commit -m "feat(schedule): JSON persistence format, presets and display helpers

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: State and repository

**Files:**
- Modify: `app/src/main/java/com/anastasiia/appblocker/core/BlockerState.kt`
- Modify: `app/src/main/java/com/anastasiia/appblocker/core/BlockerStateRepository.kt`
- Test: `app/src/test/java/com/anastasiia/appblocker/core/BlockerStateRepositoryTest.kt`

**Interfaces:**
- Consumes: `ScheduleWindow`, `encodeSchedules`, `decodeSchedules` (Task 3).
- Produces: `BlockerState.schedules: Map<String, List<ScheduleWindow>>` (default empty), `suspend fun BlockerStateRepository.setSchedules(value: Map<String, List<ScheduleWindow>>)`, `suspend fun BlockerStateRepository.setSchedule(pkg: String, windows: List<ScheduleWindow>)` (empty list removes the entry).

- [ ] **Step 1: Write the failing test** (add to `BlockerStateRepositoryTest`)

```kotlin
    @Test
    fun schedulesRoundTripAndEmptyListRemovesTheApp() = runTest {
        val file = tmp.newFile("sched.preferences_pb").absolutePath.toPath()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val store = PreferenceDataStoreFactory.createWithPath(scope = scope) { file }
        val repo = BlockerStateRepository(store)

        assertEquals(emptyMap<String, List<ScheduleWindow>>(), repo.state.first().schedules)

        val morning = ScheduleWindow(setOf(1, 2, 3, 4, 5, 6, 7), 480, 660)
        val night = ScheduleWindow(setOf(1, 2, 3, 4, 5), 1290, 60)
        repo.setSchedule("com.facebook.katana", listOf(morning))
        repo.setSchedule("com.google.android.youtube", listOf(morning, night))
        assertEquals(
            mapOf("com.facebook.katana" to listOf(morning), "com.google.android.youtube" to listOf(morning, night)),
            repo.state.first().schedules,
        )

        repo.setSchedule("com.facebook.katana", emptyList())
        assertEquals(mapOf("com.google.android.youtube" to listOf(morning, night)), repo.state.first().schedules)

        repo.setSchedules(mapOf("com.whatsapp" to listOf(night)))
        assertEquals(mapOf("com.whatsapp" to listOf(night)), repo.state.first().schedules)
        scope.cancel()

        val scope2 = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val repo2 = BlockerStateRepository(PreferenceDataStoreFactory.createWithPath(scope = scope2) { file })
        assertEquals(mapOf("com.whatsapp" to listOf(night)), repo2.state.first().schedules)
        scope2.cancel()
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*BlockerStateRepositoryTest*' 2>&1 | grep -E "^e: |BUILD"`
Expected: `Unresolved reference 'schedules'`, BUILD FAILED.

- [ ] **Step 3: Add the field and the key**

In `BlockerState.kt`, change the data class to:

```kotlin
data class BlockerState(
    val enabled: Boolean = false,
    val blockedPackages: Set<String> = emptySet(),
    val pausedUntil: Long = 0L,
    val instagramMessagesOnly: Boolean = false,
    val youtubeNoShorts: Boolean = false,
    val schedules: Map<String, List<ScheduleWindow>> = emptyMap(),
) {
    fun isPaused(now: Long): Boolean = now < pausedUntil
}
```

In `BlockerStateRepository.kt`:
- In `Keys`, add `val SCHEDULES = stringPreferencesKey("schedules")`.
- In the `state` mapping, add `schedules = decodeSchedules(prefs[Keys.SCHEDULES]),` after `youtubeNoShorts`.
- After `setYoutubeNoShorts`, add:

```kotlin
    suspend fun setSchedules(value: Map<String, List<ScheduleWindow>>) =
        dataStore.edit { it[Keys.SCHEDULES] = encodeSchedules(value) }

    /** Replaces one app's windows; an empty list removes the app from the schedules. */
    suspend fun setSchedule(pkg: String, windows: List<ScheduleWindow>) = dataStore.edit { prefs ->
        val current = decodeSchedules(prefs[Keys.SCHEDULES])
        val updated = if (windows.isEmpty()) current - pkg else current + (pkg to windows)
        prefs[Keys.SCHEDULES] = encodeSchedules(updated)
    }
```

- [ ] **Step 4: Run the whole unit suite**

Run: `./gradlew :app:testDebugUnitTest 2>&1 | grep -E "^e: |BUILD"`; then `cat app/build/test-results/testDebugUnitTest/*.xml | grep -oE 'failures="[0-9]+" errors="[0-9]+"' | sort | uniq -c`
Expected: BUILD SUCCESSFUL, only `failures="0" errors="0"` lines. (The existing `roundTripsAllFields` still passes because `schedules` defaults to empty.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anastasiia/appblocker/core/BlockerState.kt app/src/main/java/com/anastasiia/appblocker/core/BlockerStateRepository.kt app/src/test/java/com/anastasiia/appblocker/core/BlockerStateRepositoryTest.kt
git commit -m "feat(schedule): persist per-app schedules in DataStore

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Decision function

**Files:**
- Modify: `app/src/main/java/com/anastasiia/appblocker/core/Decision.kt`
- Test: `app/src/test/java/com/anastasiia/appblocker/core/DecisionTest.kt`

**Interfaces:**
- Consumes: `BlockerState.schedules` (Task 4), `isScheduledNow` (Task 1).
- Produces: `fun shouldBlock(pkg: String?, state: BlockerState, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean`. Existing three-argument call sites (service, tests) keep compiling.

- [ ] **Step 1: Write the failing tests** (add to `DecisionTest`)

```kotlin
    private val toronto = java.time.ZoneId.of("America/Toronto")
    private fun torontoMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        java.time.ZonedDateTime.of(y, mo, d, h, mi, 0, 0, toronto).toInstant().toEpochMilli()
    private val morning = ScheduleWindow(setOf(1, 2, 3, 4, 5, 6, 7), 8 * 60, 11 * 60)
    private val scheduled = BlockerState(
        enabled = true,
        blockedPackages = emptySet(),
        schedules = mapOf("com.facebook.katana" to listOf(morning)),
    )

    @Test fun blocksScheduledAppInsideItsWindow() =
        assertTrue(shouldBlock("com.facebook.katana", scheduled, torontoMillis(2026, 10, 1, 9, 30), toronto))

    @Test fun allowsScheduledAppOutsideItsWindow() =
        assertFalse(shouldBlock("com.facebook.katana", scheduled, torontoMillis(2026, 10, 1, 11, 0), toronto))

    @Test fun scheduleRespectsPauseAndToggle() {
        val inside = torontoMillis(2026, 10, 1, 9, 30)
        assertFalse(shouldBlock("com.facebook.katana", scheduled.copy(pausedUntil = inside + 60_000L), inside, toronto))
        assertFalse(shouldBlock("com.facebook.katana", scheduled.copy(enabled = false), inside, toronto))
    }

    @Test fun alwaysWinsOverSchedule() {
        val both = scheduled.copy(blockedPackages = setOf("com.facebook.katana"))
        assertTrue(shouldBlock("com.facebook.katana", both, torontoMillis(2026, 10, 1, 15, 0), toronto))
    }

    @Test fun scheduleIsEvaluatedInTheGivenZone() {
        // 13:30 UTC = 09:30 Toronto (inside) but 13:30 in UTC (outside).
        val utc = java.time.ZoneId.of("UTC")
        val millis = java.time.ZonedDateTime.of(2026, 10, 1, 13, 30, 0, 0, utc).toInstant().toEpochMilli()
        assertTrue(shouldBlock("com.facebook.katana", scheduled, millis, toronto))
        assertFalse(shouldBlock("com.facebook.katana", scheduled, millis, utc))
    }

    @Test fun unscheduledAppIsUnaffectedBySchedules() =
        assertFalse(shouldBlock("com.spotify.music", scheduled, torontoMillis(2026, 10, 1, 9, 30), toronto))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*DecisionTest*' 2>&1 | grep -E "^e: |BUILD"`
Expected: `Too many arguments for 'fun shouldBlock'`, BUILD FAILED.

- [ ] **Step 3: Add the clause**

Replace `shouldBlock` in `Decision.kt` with:

```kotlin
import java.time.Instant
import java.time.ZoneId

fun shouldBlock(
    pkg: String?,
    state: BlockerState,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean {
    if (pkg == null || pkg == SELF_PACKAGE) return false
    if (!state.enabled || state.isPaused(now)) return false
    if (pkg in state.blockedPackages) return true
    val windows = state.schedules[pkg] ?: return false
    return isScheduledNow(windows, Instant.ofEpochMilli(now).atZone(zone))
}
```

(The imports go at the top of the file with the existing `package` line. The zone conversion runs only for apps that have schedules.)

- [ ] **Step 4: Run the whole unit suite**

Run: `./gradlew :app:testDebugUnitTest 2>&1 | grep -E "^e: |BUILD"`; confirm `DecisionTest` XML shows `tests="14"` and no failures anywhere.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anastasiia/appblocker/core/Decision.kt app/src/test/java/com/anastasiia/appblocker/core/DecisionTest.kt
git commit -m "feat(schedule): block scheduled apps inside their windows

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Gate action for schedule reductions

**Files:**
- Modify: `app/src/main/java/com/anastasiia/appblocker/core/Gate.kt`
- Modify: `app/src/main/java/com/anastasiia/appblocker/core/GateCoordinator.kt`
- Test: `app/src/test/java/com/anastasiia/appblocker/core/GateTest.kt`
- Test: `app/src/test/java/com/anastasiia/appblocker/core/GateCoordinatorTest.kt`

**Interfaces:**
- Consumes: `ScheduleWindow`, `encodeWindows`, `decodeWindows` (Task 3), `BlockerStateRepository.setSchedule`, `setBlockedPackages` (Task 4).
- Produces: `GateAction.SetSchedule(val pkg: String, val windows: List<ScheduleWindow>)`; `waitMillisFor(SetSchedule) == 5 min`; encoding `"schedule:<pkg>:<json array>"`; `GateCoordinator.confirm` applies it by writing the windows and removing the app from `blockedPackages`.

- [ ] **Step 1: Write the failing tests**

In `GateTest.waitTimesMatchSpec`, add before the `Disable` assertion:

```kotlin
        assertEquals(5 * 60_000L, waitMillisFor(GateAction.SetSchedule("com.facebook.katana", emptyList())))
```

In `GateTest.actionEncodingRoundTrips`, add to the `actions` list:

```kotlin
            GateAction.SetSchedule("com.facebook.katana", listOf(ScheduleWindow(setOf(1, 2, 3, 4, 5, 6, 7), 480, 660))),
            GateAction.SetSchedule("com.google.android.youtube", emptyList()),
```

and after the existing `assertNull` lines:

```kotlin
        assertNull(decodeAction("schedule:"))
        assertNull(decodeAction("schedule:com.facebook.katana"))
        assertEquals(
            GateAction.SetSchedule("com.facebook.katana", emptyList()),
            decodeAction("schedule:com.facebook.katana:not json"),
        ) // unreadable windows degrade to "Never", which is the stricter-to-undo but safe reading
```

In `GateCoordinatorTest`, add:

```kotlin
    @Test
    fun confirmSetScheduleWritesWindowsAndDropsAlways() = runGateTest { repo, _, gate ->
        repo.setEnabled(true)
        repo.setBlockedPackages(setOf("com.facebook.katana", "com.sephora"))
        val morning = ScheduleWindow(setOf(1, 2, 3, 4, 5, 6, 7), 480, 660)
        gate.submit(GateAction.SetSchedule("com.facebook.katana", listOf(morning)), answer, now = 0L)
        gate.confirm(now = 5 * 60_000L)
        val state = repo.state.first()
        assertEquals(setOf("com.sephora"), state.blockedPackages)
        assertEquals(mapOf("com.facebook.katana" to listOf(morning)), state.schedules)
    }

    @Test
    fun confirmSetScheduleWithNoWindowsRemovesTheSchedule() = runGateTest { repo, _, gate ->
        repo.setEnabled(true)
        repo.setSchedule("com.facebook.katana", listOf(ScheduleWindow(setOf(1), 480, 660)))
        gate.submit(GateAction.SetSchedule("com.facebook.katana", emptyList()), answer, now = 0L)
        gate.confirm(now = 5 * 60_000L)
        assertEquals(emptyMap<String, List<ScheduleWindow>>(), repo.state.first().schedules)
    }

    @Test
    fun setSchedulePendingRoundTripsThroughTheStore() = runGateTest { repo, _, gate ->
        val night = ScheduleWindow(setOf(6, 7), 1290, 60)
        gate.submit(GateAction.SetSchedule("com.google.android.youtube", listOf(night)), answer, now = 0L)
        assertEquals(
            GateAction.SetSchedule("com.google.android.youtube", listOf(night)),
            repo.gateState.first().pending?.action,
        )
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*GateTest*' --tests '*GateCoordinatorTest*' 2>&1 | grep -E "^e: |BUILD"`
Expected: `Unresolved reference 'SetSchedule'`, BUILD FAILED.

- [ ] **Step 3: Add the action, wait, encoding, and apply**

In `Gate.kt`:

```kotlin
sealed interface GateAction {
    data class Pause(val minutes: Int) : GateAction
    data object Disable : GateAction
    data class RemoveApps(val packages: Set<String>) : GateAction
    data class ModeOff(val mode: BlockMode) : GateAction
    /** Replace [pkg]'s windows (empty = Never) and drop it from the Always list. Gated only when coverage shrinks. */
    data class SetSchedule(val pkg: String, val windows: List<ScheduleWindow>) : GateAction
}
```

Add `private const val SCHEDULE_WAIT_MS = 5 * 60_000L` next to the other waits, and in `waitMillisFor` add the branch `is GateAction.SetSchedule -> SCHEDULE_WAIT_MS`.

In `encodeAction` add:

```kotlin
    is GateAction.SetSchedule -> "schedule:${action.pkg}:${encodeWindows(action.windows)}"
```

In `decodeAction` add, before `else -> null`:

```kotlin
    s.startsWith("schedule:") -> {
        val rest = s.removePrefix("schedule:")
        val pkg = rest.substringBefore(':', missingDelimiterValue = "")
        if (pkg.isEmpty() || !rest.contains(':')) null
        else GateAction.SetSchedule(pkg, decodeWindows(rest.substringAfter(':')))
    }
```

In `GateCoordinator.apply`, add:

```kotlin
            is GateAction.SetSchedule -> {
                repository.setSchedule(action.pkg, action.windows)
                val current = repository.state.first().blockedPackages
                if (action.pkg in current) repository.setBlockedPackages(current - action.pkg)
            }
```

- [ ] **Step 4: Run the whole unit suite**

Run: `./gradlew :app:testDebugUnitTest 2>&1 | grep -E "^e: |BUILD"`; `GateCoordinatorTest` XML should show `tests="16"`, no failures anywhere.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anastasiia/appblocker/core/Gate.kt app/src/main/java/com/anastasiia/appblocker/core/GateCoordinator.kt app/src/test/java/com/anastasiia/appblocker/core/GateTest.kt app/src/test/java/com/anastasiia/appblocker/core/GateCoordinatorTest.kt
git commit -m "feat(schedule): gate action that applies a reduced schedule

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Schedule editor screen

**Files:**
- Create: `app/src/main/java/com/anastasiia/appblocker/ui/ScheduleEditorScreen.kt`
- Modify: `app/src/main/java/com/anastasiia/appblocker/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/anastasiia/appblocker/MainActivity.kt`
- Modify: `app/src/main/java/com/anastasiia/appblocker/ui/EditAppsScreen.kt` (new parameter with a no-op default)
- Modify: `app/src/main/java/com/anastasiia/appblocker/core/Schedule.kt` (one pure helper)
- Test: `app/src/test/java/com/anastasiia/appblocker/core/ScheduleTest.kt`

**Interfaces:**
- Consumes: `ScheduleWindow`, `MORNING_PRESET`, `NIGHT_PRESET`, `formatMinute`, `coverageOf`, `coverageReduced` (Tasks 1–3), `GateAction.SetSchedule` (Task 6), `BlockerStateRepository.setSchedule/setBlockedPackages` (Task 4).
- Produces: `fun scheduleSaveNeedsGate(enabled: Boolean, wasAlways: Boolean, before: List<ScheduleWindow>, after: List<ScheduleWindow>): Boolean` in `Schedule.kt`; `fun MainViewModel.setSchedule(pkg: String, windows: List<ScheduleWindow>)` (also removes `pkg` from the Always list); `@Composable fun ScheduleEditorScreen(viewModel: MainViewModel, pkg: String, label: String, onDone: () -> Unit, onGate: (GateAction) -> Unit)`; `Screen.ScheduleEditor` in `MainActivity` with `onEditSchedule: (AppInfo) -> Unit` passed to `EditAppsScreen` (wired in Task 8).

- [ ] **Step 1: Write the failing test for the save rule** (append inside `ScheduleTest`)

```kotlin
    @Test fun scheduleSaveNeedsGateOnlyForReductionsWhileEnabled() {
        val morning = listOf(ScheduleWindow(allDays, 480, 660))
        val shorter = listOf(ScheduleWindow(allDays, 480, 600))
        assertTrue(scheduleSaveNeedsGate(enabled = true, wasAlways = false, before = morning, after = shorter))
        assertTrue(scheduleSaveNeedsGate(enabled = true, wasAlways = false, before = morning, after = emptyList()))
        assertTrue(scheduleSaveNeedsGate(enabled = true, wasAlways = true, before = emptyList(), after = morning))
        assertFalse(scheduleSaveNeedsGate(enabled = true, wasAlways = false, before = emptyList(), after = morning))
        assertFalse(scheduleSaveNeedsGate(enabled = true, wasAlways = false, before = shorter, after = morning))
        assertFalse(scheduleSaveNeedsGate(enabled = false, wasAlways = false, before = morning, after = emptyList()))
        assertFalse(scheduleSaveNeedsGate(enabled = true, wasAlways = false, before = morning, after = morning))
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduleTest*' 2>&1 | grep -E "^e: |BUILD"`
Expected: `Unresolved reference 'scheduleSaveNeedsGate'`, BUILD FAILED.

- [ ] **Step 3: Add the rule** (append to `Schedule.kt`)

```kotlin
/**
 * Whether saving [after] in place of [before] must go through the Unblock
 * Gate: only while blocking is on, and only if some minute of the week stops
 * being blocked. An app that was Always blocked counts as fully covered.
 */
fun scheduleSaveNeedsGate(
    enabled: Boolean,
    wasAlways: Boolean,
    before: List<ScheduleWindow>,
    after: List<ScheduleWindow>,
): Boolean = enabled && coverageReduced(coverageOf(wasAlways, before), coverageOf(always = false, windows = after))
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduleTest*' 2>&1 | grep -E "^e: |BUILD"`
Expected: BUILD SUCCESSFUL, `tests="25"`.

- [ ] **Step 5: Add the view-model method**

In `MainViewModel.kt`, add the import `import com.anastasiia.appblocker.core.ScheduleWindow` and the method. (`repository.state` is a plain `Flow`; the view model's own `state` is the `StateFlow` with a current value.)

```kotlin
    /** Instant (blocking-ward) schedule save: write the windows and drop the app from the Always list. */
    fun setSchedule(pkg: String, windows: List<ScheduleWindow>) = viewModelScope.launch {
        repository.setSchedule(pkg, windows)
        val current = state.value.blockedPackages
        if (pkg in current) repository.setBlockedPackages(current - pkg)
    }
```

- [ ] **Step 6: Write the editor screen**

Create `ScheduleEditorScreen.kt`:

```kotlin
package com.anastasiia.appblocker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anastasiia.appblocker.core.ALL_DAYS
import com.anastasiia.appblocker.core.GateAction
import com.anastasiia.appblocker.core.MORNING_PRESET
import com.anastasiia.appblocker.core.NIGHT_PRESET
import com.anastasiia.appblocker.core.ScheduleWindow
import com.anastasiia.appblocker.core.formatMinute
import com.anastasiia.appblocker.core.scheduleSaveNeedsGate

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

/**
 * Per-app window editor. Save applies instantly when it only adds blocking;
 * when it removes any, the new window list becomes a gate request instead.
 */
@Composable
fun ScheduleEditorScreen(
    viewModel: MainViewModel,
    pkg: String,
    label: String,
    onDone: () -> Unit,
    onGate: (GateAction) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val saved = state.schedules[pkg].orEmpty()
    val wasAlways = pkg in state.blockedPackages
    var windows by remember(saved) { mutableStateOf(saved) }
    var picking by remember { mutableStateOf<TimePick?>(null) }

    BackHandler(onBack = onDone)

    val allValid = windows.none { it.isEmpty }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.headlineSmall)
            Text(
                if (wasAlways) "Currently blocked always. Saving a schedule replaces that."
                else "Blocked during these windows.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { windows = windows + MORNING_PRESET }) { Text("Morning 08:00–11:00") }
                OutlinedButton(onClick = { windows = windows + NIGHT_PRESET }) { Text("Night 21:30–01:00") }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(windows) { index, window ->
                    WindowCard(
                        window = window,
                        onChange = { changed -> windows = windows.toMutableList().also { it[index] = changed } },
                        onRemove = { windows = windows.toMutableList().also { it.removeAt(index) } },
                        onPickStart = { picking = TimePick(index, start = true) },
                        onPickEnd = { picking = TimePick(index, start = false) },
                    )
                }
                item {
                    TextButton(onClick = { windows = windows + ScheduleWindow(ALL_DAYS, 9 * 60, 10 * 60) }) {
                        Text("Add window")
                    }
                }
            }
            if (!allValid) {
                Text(
                    "Each window needs at least one day and a different start and end.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    if (scheduleSaveNeedsGate(state.enabled, wasAlways, saved, windows)) {
                        onGate(GateAction.SetSchedule(pkg, windows))
                    } else {
                        viewModel.setSchedule(pkg, windows)
                        onDone()
                    }
                },
                enabled = allValid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }

    val pick = picking
    val pickedWindow = pick?.let { windows.getOrNull(it.index) }
    if (pick != null && pickedWindow != null) {
        val window = pickedWindow
        val initial = if (pick.start) window.startMinute else window.endMinute
        TimeDialog(
            title = if (pick.start) "Starts" else "Ends",
            initialMinute = initial,
            onDismiss = { picking = null },
            onConfirm = { minute ->
                val changed = if (pick.start) window.copy(startMinute = minute) else window.copy(endMinute = minute)
                windows = windows.toMutableList().also { it[pick.index] = changed }
                picking = null
            },
        )
    }
}

private data class TimePick(val index: Int, val start: Boolean)

@Composable
private fun WindowCard(
    window: ScheduleWindow,
    onChange: (ScheduleWindow) -> Unit,
    onRemove: () -> Unit,
    onPickStart: () -> Unit,
    onPickEnd: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (day in 1..7) {
                    FilterChip(
                        selected = day in window.days,
                        onClick = {
                            val days = if (day in window.days) window.days - day else window.days + day
                            onChange(window.copy(days = days))
                        },
                        label = { Text(DAY_LETTERS[day - 1]) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onPickStart) { Text(formatMinute(window.startMinute)) }
                    Text("to")
                    OutlinedButton(onClick = onPickEnd) { Text(formatMinute(window.endMinute)) }
                    if (window.crossesMidnight) {
                        Text(
                            "next day",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, initialMinute: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val pickerState = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour * 60 + pickerState.minute) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

- [ ] **Step 7: Wire the screen into navigation**

In `MainActivity.kt`:
- Change the enum to `private enum class Screen { Main, EditApps, ScheduleEditor, Gate, Confirm }`.
- Add `import com.anastasiia.appblocker.ui.AppInfo` and `import com.anastasiia.appblocker.ui.ScheduleEditorScreen`.
- Inside `setContent`, next to `gateAction`, add `var scheduleApp by remember { mutableStateOf<AppInfo?>(null) }`.
- In the `when`, change the `EditApps` branch to:

```kotlin
                    Screen.EditApps -> EditAppsScreen(
                        viewModel,
                        onDone = { screen = Screen.Main },
                        onGate = onGate,
                        onEditSchedule = { app ->
                            scheduleApp = app
                            screen = Screen.ScheduleEditor
                        },
                    )
                    Screen.ScheduleEditor -> {
                        val app = scheduleApp
                        if (app == null) {
                            screen = Screen.EditApps
                        } else {
                            ScheduleEditorScreen(
                                viewModel,
                                pkg = app.packageName,
                                label = app.label,
                                onDone = { screen = Screen.EditApps },
                                onGate = onGate,
                            )
                        }
                    }
```

`EditAppsScreen` does not yet accept `onEditSchedule`; add the parameter now with a no-op default so this task compiles, and Task 8 gives it a body:

```kotlin
fun EditAppsScreen(
    viewModel: MainViewModel,
    onDone: () -> Unit,
    onGate: (GateAction) -> Unit = {},
    onEditSchedule: (AppInfo) -> Unit = {},
)
```

- [ ] **Step 8: Build, run unit tests, install, and smoke the editor on the device**

Run: `./gradlew :app:testDebugUnitTest :app:installDebug 2>&1 | grep -E "^e: |BUILD|Installed"`
Expected: BUILD SUCCESSFUL, `Installed on 1 device.`

The editor is not reachable from the UI until Task 8, so verify compilation only here. (If you want to see it now, temporarily call `onEditSchedule(app)` from a row; do not commit that.)

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/anastasiia/appblocker/ui/ScheduleEditorScreen.kt app/src/main/java/com/anastasiia/appblocker/ui/MainViewModel.kt app/src/main/java/com/anastasiia/appblocker/MainActivity.kt app/src/main/java/com/anastasiia/appblocker/ui/EditAppsScreen.kt app/src/main/java/com/anastasiia/appblocker/core/Schedule.kt app/src/test/java/com/anastasiia/appblocker/core/ScheduleTest.kt
git commit -m "feat(schedule): per-app window editor with presets and gated saves

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: Edit-apps list integration

**Files:**
- Modify: `app/src/main/java/com/anastasiia/appblocker/ui/EditAppsScreen.kt`

**Interfaces:**
- Consumes: `onEditSchedule: (AppInfo) -> Unit` (Task 7), `describeWindows` (Task 3), `BlockerState.schedules` (Task 4), `MainViewModel.setBlockedPackages`.
- Produces: rows show a schedule summary and a "Schedule" button; checking Always clears the app's schedule on save; saving prunes schedules of uninstalled apps.

- [ ] **Step 1: Update the row and the save**

Replace the row `Row(...)` inside `items(visible, ...)` with:

```kotlin
                    val windows = state.schedules[app.packageName].orEmpty()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label)
                            if (windows.isNotEmpty() && app.packageName !in selected) {
                                Text(
                                    describeWindows(windows),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(onClick = { onEditSchedule(app) }) { Text("Schedule") }
                        Checkbox(
                            checked = app.packageName in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + app.packageName
                                else selected - app.packageName
                            },
                        )
                    }
```

Add imports: `androidx.compose.foundation.layout.Column` is already there; add `androidx.compose.material3.MaterialTheme`, `androidx.compose.material3.TextButton`, `com.anastasiia.appblocker.core.describeWindows`.

In the Save `onClick`, after `val additions = chosen - current`, add the schedule normalization and prune, and call it on both branches:

```kotlin
                    // Always wins: an app checked here loses its schedule. Schedules of
                    // uninstalled apps are pruned like stale blocked packages.
                    val keptSchedules = state.schedules.filterKeys { it in installed && it !in chosen }
                    if (keptSchedules != state.schedules) viewModel.setSchedules(keptSchedules)
```

Place those two lines before the `if (state.enabled && removals.isNotEmpty())`. Then add to `MainViewModel`:

```kotlin
    fun setSchedules(value: Map<String, List<ScheduleWindow>>) =
        viewModelScope.launch { repository.setSchedules(value) }
```

- [ ] **Step 2: Build, install, and verify on the device**

Run: `./gradlew :app:testDebugUnitTest :app:installDebug 2>&1 | grep -E "^e: |BUILD|Installed"`
Expected: BUILD SUCCESSFUL, installed.

On the phone: open App Blocker → Edit → tap "Schedule" on an installed app that is not blocked (e.g. Facebook) → tap "Morning 08:00–11:00" → Save. Back on the list the row shows `08:00–11:00 · daily`. Tap Save on the list. Then check the stored value:

```bash
adb exec-out run-as com.anastasiia.appblocker cat files/datastore/blocker_state.preferences_pb | strings | grep -A1 schedules
```

Expected: a JSON string containing `"com.facebook.katana":[{"days":[1,2,3,4,5,6,7],"start":480,"end":660}]`.

Then check Always wins: check Facebook's checkbox → Save → the stored `schedules` no longer contains Facebook and `blocked_packages` does. Uncheck it again (this goes through the gate while blocking is on: answer, wait 5 minutes, confirm) or skip if blocking is off.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/anastasiia/appblocker/ui/EditAppsScreen.kt app/src/main/java/com/anastasiia/appblocker/ui/MainViewModel.kt
git commit -m "feat(schedule): schedule entry point and summaries in the app list

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 9: Main screen summaries and pending copy

**Files:**
- Modify: `app/src/main/java/com/anastasiia/appblocker/ui/MainScreen.kt`

**Interfaces:**
- Consumes: `BlockerState.schedules`, `isScheduledNow`, `describeWindows`, `nextWindowStart`, `formatMinute` (Tasks 1–4), `GateAction.SetSchedule` (Task 6).
- Produces: the blocked list shows Always apps and scheduled apps with a subtitle; a scheduled app outside its window is dimmed with "next: HH:MM"; the pending line says "Schedule ready at …" for schedule requests.

- [ ] **Step 1: Replace the label list with entries**

Replace the `appLabels` computation with:

```kotlin
    val entries = remember(state.blockedPackages, state.schedules, now / 60_000L) {
        val byPackage = launchableApps(context.packageManager).associateBy { it.packageName }
        val at = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
        val day = at.dayOfWeek.value
        val minute = at.hour * 60 + at.minute
        val always = state.blockedPackages.map { pkg ->
            BlockedEntry(byPackage[pkg]?.label ?: pkg, subtitle = "Always", activeNow = true)
        }
        val scheduled = state.schedules.filterKeys { it !in state.blockedPackages }.map { (pkg, windows) ->
            val active = isScheduledNow(windows, at)
            val next = nextWindowStart(windows, day, minute)
            BlockedEntry(
                label = byPackage[pkg]?.label ?: pkg,
                subtitle = describeWindows(windows) + if (!active && next != null) " · next: ${formatMinute(next)}" else "",
                activeNow = active,
            )
        }
        (always + scheduled).sortedBy { it.label.lowercase() }
    }
```

Add, at file level below `PAUSE_MINUTES`:

```kotlin
private data class BlockedEntry(val label: String, val subtitle: String, val activeNow: Boolean)
```

Replace the `LazyColumn` body with:

```kotlin
            LazyColumn(Modifier.weight(1f)) {
                if (entries.isEmpty()) {
                    item {
                        Text(
                            "No apps selected yet.",
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(entries, key = { it.label + it.subtitle }) { entry ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(
                            entry.label,
                            color = if (entry.activeNow) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            entry.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
```

Add imports: `com.anastasiia.appblocker.core.describeWindows`, `com.anastasiia.appblocker.core.formatMinute`, `com.anastasiia.appblocker.core.isScheduledNow`, `com.anastasiia.appblocker.core.nextWindowStart`.

- [ ] **Step 2: Update the pending copy**

In the `GatePhase.WAITING` branch, replace the `pendingText` expression with:

```kotlin
                            val pendingText = when (pending.action) {
                                is GateAction.Pause -> "Pause ready at ${formatClock(pending.readyAt)}."
                                is GateAction.SetSchedule -> "Schedule ready at ${formatClock(pending.readyAt)}."
                                else -> "Noted. Ready at ${formatClock(pending.readyAt)}."
                            }
```

- [ ] **Step 3: Build, install, and verify on the device**

Run: `./gradlew :app:testDebugUnitTest :app:installDebug 2>&1 | grep -E "^e: |BUILD|Installed"`
Expected: BUILD SUCCESSFUL, installed.

On the phone, with Facebook scheduled from Task 8 and the current time outside 08:00–11:00: the main list shows "Facebook" dimmed with `08:00–11:00 · daily · next: 08:00`; Temu/Shein/Sephora show "Always". Open Facebook: it opens (outside the window).

Now the real end-to-end check. Note the phone's current time with `adb shell date`. In the editor, add a window for Facebook from two minutes from now to twenty minutes from now, today only (deselect the other days), Save (it only adds coverage, so it applies instantly). Wait for the minute to tick over, then open Facebook: the block overlay appears and the phone returns home. The main list now shows Facebook undimmed. Remove that test window afterwards (gated: answer, wait five minutes, confirm; "Schedule ready at …" shows on the main screen meanwhile, and Facebook stays blocked during the wait).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/anastasiia/appblocker/ui/MainScreen.kt
git commit -m "feat(schedule): show windows and next start on the main screen

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 10: Docs, version, and final verification

**Files:**
- Modify: `README.md`
- Modify: `docs/design.md`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: everything above.
- Produces: README describes schedules; the v1 design doc's backlog entry points to the spec; version 1.4 (versionCode 5).

- [ ] **Step 1: README**

In `README.md`, after the first paragraph of the intro (ending "…That's the whole app — plus two ideas that make it actually work."), insert a new section before "## Friction, not fortress":

```markdown
## Schedules

An app can be blocked always, or only during windows you choose — say,
08:00–11:00 every day and 21:30–01:00 on weeknights. Windows are wall-clock,
may cross midnight, and go through the same pause and Unblock Gate as a full
block: adding time is one tap, removing it costs the usual question, wait and
confirmation.
```

- [ ] **Step 2: Design doc backlog**

In `docs/design.md`, under "## Out of scope (backlog, in priority order)", change the Schedules bullet to:

```markdown
- **Schedules** — done 2026-10; see
  [superpowers/specs/2026-10-02-per-app-schedules-design.md](superpowers/specs/2026-10-02-per-app-schedules-design.md).
```

- [ ] **Step 3: Version bump**

In `app/build.gradle.kts`, set `versionCode = 5` and `versionName = "1.4"`.

- [ ] **Step 4: Full test run and install**

Run: `./gradlew :app:testDebugUnitTest :app:installDebug 2>&1 | grep -E "^e: |BUILD|Installed"`; then `cat app/build/test-results/testDebugUnitTest/*.xml | grep -oE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' | awk -F'"' '{t+=$2; f+=$6; e+=$8} END {print "tests="t" failures="f" errors="e}'`
Expected: BUILD SUCCESSFUL, failures=0, errors=0, tests=106 (71 existing + 25 ScheduleTest + 6 DecisionTest + 1 repository + 3 coordinator).

Reboot check: `adb reboot`, wait for the device, unlock it, open App Blocker: the schedule is still listed and the accessibility banner is not shown.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/design.md app/build.gradle.kts
git commit -m "docs: describe schedules; bump to 1.4 (5)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Self-review notes

- **Spec coverage:** model (Task 1), decision clause (Task 5), persistence key (Task 4), gate table (Tasks 6–7: additions instant, reductions gated at 5 min, Always→Scheduled gated, Scheduled→Always instant via the checkbox), editor UI with presets (Task 7), list summaries and next-start (Tasks 8–9), pending copy (Task 9), pruning of uninstalled apps (Task 8), Always-wins normalization (Tasks 5 and 8), block screen unchanged (nothing to do), docs (Task 10).
- **Not in the plan on purpose:** scheduled partial modes, notifications, budgets — all spec backlog.
- **Review Focus mapping:** zero-length window (Task 1 test, Task 7 Save disabled), no days (same), malformed JSON (Task 3), both Always and Scheduled (Task 5 decision test, Task 8 normalization), pause/toggle precedence (Task 5).
