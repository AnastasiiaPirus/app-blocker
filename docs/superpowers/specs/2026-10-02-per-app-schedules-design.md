# Per-App Schedules — Design Spec

**Date:** 2026-10-02
**Status:** Draft for review (model agreed in conversation: per-app windows)
**Extends:** [design.md](../../design.md) (v1) and [unblock-gate.md](../../unblock-gate.md)

## Purpose

Today an app is either blocked all the time or not at all. Some apps are
fine most of the day and only a problem in specific hours. Schedules let each
app carry its own set of weekly time windows during which it is blocked, with
the same block screen, the same pause, and the same Unblock Gate as a full
block.

The windows are the user's call. Two common starting points are offered as
quick picks: **Morning 08:00–11:00** and **Night 21:30–01:00**, every day.

## Guiding principles (unchanged)

- **Friction, not fortress.** Schedules are wall-clock timestamps compared on
  every event; no alarms, no jobs, nothing to tamper-proof.
- **Keep it simple.** One new concept (a window), one new DataStore key, one
  new clause in the decision function.

## In scope

1. An app is blocked in one of two ways: **Always** (today's blocked list) or
   **On a schedule** (one or more windows). Never both; the UI enforces it and
   the decision function treats Always as winning if both are ever set.
2. A **window** is a set of weekdays plus a start and end time of day
   (minute precision). End before start means the window crosses midnight
   and ends on the following day.
3. Schedules respect pause and the master toggle exactly like full blocks.
4. Edits that reduce blocking go through the Unblock Gate; edits that add
   blocking apply instantly.
5. The main screen shows each app's windows and whether one is active now.

## Out of scope (backlog)

- Scheduling the partial modes (Instagram messages-only, YouTube no-Shorts).
  They stay always-on toggles in this iteration.
- Per-window exceptions, holidays, "until" dates, notifications when a window
  starts or ends.
- Daily time budgets ("30 minutes of Facebook a day"). A different mechanism.

## Data model

One new DataStore key in the existing preferences store:

| Key         | Type   | Meaning                                              |
|-------------|--------|------------------------------------------------------|
| `schedules` | String | JSON: `{ "<pkg>": [ {"days":[1,2,3], "start":480, "end":660}, … ], … }` |

- `days` are ISO weekday numbers (1 = Monday … 7 = Sunday), the window's
  **start** day. `start` and `end` are minutes after local midnight
  (0–1439). `end < start` crosses midnight.
- Encoded and decoded with `org.json` (already a test dependency; becomes a
  runtime one — it is part of the Android platform, so no new artifact).
- An app with an empty window list is dropped from the map on save.
- `BlockerState` gains `schedules: Map<String, List<ScheduleWindow>>`.

```kotlin
data class ScheduleWindow(val days: Set<Int>, val startMinute: Int, val endMinute: Int)
```

## Decision logic

```
shouldBlock(pkg, state, now, zone) =
    pkg != SELF_PACKAGE
    && state.enabled
    && !state.isPaused(now)
    && (pkg in state.blockedPackages || isScheduledNow(pkg, state.schedules, local(now, zone)))
```

`isScheduledNow` resolves `now` to a local weekday and minute-of-day once, then
checks each of the app's windows:

```
windowContains(w, day, minute) =
    if (w.end > w.start)  day in w.days && minute in [w.start, w.end)
    else                  (day in w.days && minute >= w.start)
                       || (previousDay(day) in w.days && minute < w.end)
```

Start is inclusive, end exclusive, so adjacent windows do not overlap and a
window ending at 11:00 lets the app open at 11:00:00. A crossing window listed
on Monday covers Monday 21:30 through Tuesday 01:00.

The service passes `ZoneId.systemDefault()` at event time; the pure function
takes the zone as a parameter so tests pin it. The hot path adds one
`Instant → ZonedDateTime` conversion per event only for apps that have
schedules; apps without schedules short-circuit as today.

Pause (`pausedUntil`) and the master toggle gate schedules exactly as they gate
the full list. The partial-mode `guardApplies` is unchanged.

## Unblock Gate

Reducing blocking is anything that leaves some (weekday, minute) covered
before and uncovered after. That includes deleting a window, shrinking it,
removing a day, or moving an app from Always to Scheduled. Detection is a pure
function over a 7×1440 coverage grid:

```
coverageReduced(before: List<ScheduleWindow>, after: List<ScheduleWindow>): Boolean
```

An "Always" app is treated as covering the whole grid for this comparison.

| Edit                                      | Gated? | Wait  |
|-------------------------------------------|--------|-------|
| Add a window, extend one, add a day       | No     | —     |
| Move an app from Scheduled to Always      | No     | —     |
| Delete/shrink a window, remove a day      | Yes    | 5 min |
| Move an app from Always to Scheduled      | Yes    | 5 min |
| Move an app from Scheduled to Never       | Yes    | 5 min |

As with the app list today: gating applies only while `enabled`, additions in
the same save apply immediately, and the reduction becomes the single pending
request. New gate action:

```kotlin
data class SetSchedule(val pkg: String, val windows: List<ScheduleWindow>) : GateAction
// encoded as "schedule:<pkg>:<json array>"; decode splits on the first two ':'
```

Confirming applies the new window list (and removes the app from
`blockedPackages` if it was Always). An empty list means Never.

## UI

**Edit apps screen.** Each row keeps its checkbox for Always. A small
"Schedule" text button on the row opens the app's schedule editor; a row with
a schedule shows a one-line summary under the label ("08:00–11:00 · daily").
Checking Always clears the schedule; adding a schedule unchecks Always.

**Schedule editor** (full-screen, like the gate screens): a list of windows,
each with seven day chips (Mon–Sun) and two time fields that open the
Material 3 time picker; a delete control; an "Add window" button; and two
quick picks that add a preset window ("Morning 08:00–11:00", "Night
21:30–01:00"). Save returns to the edit list; the edit list's Save applies the
diff and routes reductions through the gate.

**Main screen.** The blocked list shows a subtitle per app: "Always", or the
window summary. An app whose window is active right now gets the same
treatment as a fully blocked app; one that is scheduled but outside its window
is shown dimmed with "next: 21:30". The pending-gate line says "Schedule ready
at 18:42." for `SetSchedule` requests.

**Block screen.** Unchanged. A scheduled block shows the same overlay.

## Edge cases

- **Midnight crossing.** Handled by the two-clause rule above; tested with
  Sun→Mon so the week wrap is covered.
- **DST.** Windows are wall clock. On the fall-back night the 01:00–02:00 hour
  occurs twice and a window covering it blocks both; on the spring-forward
  night a window inside the skipped hour never fires. Acceptable.
- **Timezone change.** Wall clock follows the device zone. No conversion.
- **App uninstalled.** Stale schedule entries are harmless and are pruned on
  the next edit-list save, like stale blocked packages.
- **Both Always and Scheduled.** Cannot be produced by the UI; the decision
  function treats Always as winning; the next save normalizes.
- **Clock manipulation.** Out of scope (friction, not fortress).
- **Reboot / process death.** Everything is on disk. Nothing to do.

## Testing

- **Unit:** `windowContains` (inclusive start, exclusive end, same-day,
  crossing midnight, previous-day clause, Sun→Mon wrap, day not listed);
  `isScheduledNow` with a fixed zone across a DST change; `coverageReduced`
  (pure additions, pure removals, shrink, day removal, Always→Scheduled,
  Scheduled→Always, identical lists); JSON round-trip including the empty
  map; `encodeAction`/`decodeAction` for `SetSchedule`; `GateCoordinator`
  applies a confirmed `SetSchedule` and clears the Always flag; `shouldBlock`
  with schedules, pause and toggle combinations.
- **On device:** set a window starting two minutes from now on an installed
  app → opens now, blocked after the minute ticks over, opens again after the
  window ends; a crossing window spanning the current time; shrink a window
  while enabled → gate with 5-minute wait, app stays blocked during the wait;
  reboot keeps the schedule.

## Success criteria

An app with a schedule opens normally outside its windows and shows the block
screen inside them, with no action from the user at the boundaries. Making a
schedule stricter is one tap; making it looser costs the same reflection, wait
and confirmation as removing an app. The main screen tells at a glance which
apps are blocked right now and when the next block starts.
