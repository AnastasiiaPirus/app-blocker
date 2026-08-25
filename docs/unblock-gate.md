# Unblock Gate — Design Spec

**Date:** 2026-08-14
**Status:** Approved (design agreed in conversation; parameters confirmed)
**Supersedes:** the "pause friction" backlog item in the v1 spec.

## Purpose

Today any unblocking action (pause, toggle off, remove an app) is one tap
away, so the "grab phone → open blocker → pause → scroll" loop defeats the
blocker. The Unblock Gate puts a single, uniform friction flow in front of
every unblock-ward action: answer an open-ended reflective question (typed,
no paste), wait, then explicitly confirm. Forgetting to confirm means
blocking simply continues — the default outcome of distraction is the
healthy one.

Psychological grounding (from design discussion): the wait is an
urge-surfing window (urges peak and pass within minutes); typing an answer
is affect labeling ("name it to tame it"); the second confirmation shows
you your own just-written words (past-you talking to present-you); the
answer is always "yes, in a few minutes," never "no" (delay, not denial —
avoids reactance). Tone is friendly, concise, never scolding.

## Guiding principles (unchanged from v1)

- **Friction, not fortress.** The gate slows the impulse; it does not try
  to be tamper-proof. Uninstalling or revoking permissions remains possible.
- **Keep it simple.** Minimalistic UI, very little text. Six-ish short
  strings cover the whole flow.

## Gated actions

The gate triggers for any action that would reduce blocking **while
blocking is active** (`enabled == true`):

| Action                          | Wait after answer |
|---------------------------------|-------------------|
| Start a 1-minute pause          | 1 minute          |
| Start a 5-minute pause          | 2 minutes         |
| Start a 15-minute pause         | 5 minutes         |
| Start a 60-minute pause         | 10 minutes        |
| Remove app(s) from blocked list | 5 minutes         |
| Instagram messages-only → off   | 5 minutes         |
| YouTube no-Shorts → off         | 5 minutes         |
| Master toggle → off             | 30 minutes        |

Pause waits scale with what's being unlocked (updated 2026-08-14 after
review: a flat wait made every pause cost the same, so the rational move
was always the longest pause — the gradient must reward asking for less).
Non-preset durations fall back to the middle tier (5 minutes).

The mode toggles (Instagram messages-only, YouTube no-Shorts) are gated
because turning them off reduces enforcement; they are gated only while
master blocking is active, matching the service's `guardApplies` which
only enforces them when `enabled` and not paused (decision 2026-08-14).

Never gated (blocking-ward or moot):
- Toggle on, adding apps, turning a mode on, "Resume now" while paused —
  instant.
- Editing the app list or turning a mode off while blocking is off
  (turning blocking off already passed the gate).

App-list edits: the edit screen saves a diff. Additions apply instantly;
if the save removes any apps while blocking is active, the full removal
set becomes one pending gate request.

Starting a pause while already paused (extending) is unblock-ward and is
gated like any pause.

## The flow

**Step 1 — Question.** One open-ended question from a rotating pool of 10
(fixed rotation order, cursor persisted). Answer is mandatory: typed,
≥ 50 characters after trimming, paste disabled. The timer does not start
until the answer is submitted. If the question has been answered before, a
small, low-emphasis "Past answers" button shows previous responses to this
same question — hidden by default so a fresh answer stays the norm.
Backing out before submitting simply abandons the attempt (no record, no
pending request).

**Step 2 — Wait.** On submit the app records a pending request and shows
"Noted. Ready at 18:42." The requested change does **not** apply; blocked
apps stay blocked throughout. The main screen shows the pending state. The
request can be cancelled at any time during the wait → urge outlasted.

**Step 3 — Confirm.** When the wait elapses nothing happens by itself.
Returning to the app shows the confirmation: the answer just written, and
"Still want this?" with two choices:

- **Yes** — the change applies now. A pause's countdown starts at
  confirmation time (not at request time).
- **No, I'm good** — request cancelled → urge outlasted.

The confirmation is claimable for **30 minutes** after the wait ends.
After that the request lapses → urge outlasted. Lapse is detected lazily:
on next app open (or next gate attempt), an expired pending request is
recorded as lapsed and cleared.

Only one pending request exists at a time; starting a new gate flow
replaces the old request (journaled as `replaced`; does not count as an
urge outlasted).

## The 10 questions

1. What are you feeling right now?
2. What were you doing just before you picked up the phone?
3. What are you hoping to find in there?
4. What would you do with the next 20 minutes if it stayed blocked?
5. How will you feel afterwards — honestly?
6. What are you avoiding right now?
7. What would tonight-you thank you for doing instead?
8. Is something uncomfortable happening right now? What?
9. What do you actually need at this moment?
10. Why did you block this app in the first place?

## Journal

Every completed request (confirmed, cancelled, or lapsed) is appended to a
local journal: timestamp, question, answer, requested action, outcome.
Storage: append-only JSONL file in app-private storage
(`filesDir/journal.jsonl`), written via `org.json` (no new dependencies).
The journal powers the "Past answers" button and, over time, serves as a
trigger map. No UI beyond "Past answers" in v1 (no journal browser).

## Urges outlasted

A single quiet line on the main screen: "Urges outlasted: N". Cancelled
and lapsed requests increment it; confirmed ones don't, and neither does a
request that was merely *replaced* by a new gate attempt (journaled as
`replaced` — the urge was still alive). Monotonic counter in DataStore —
no streaks, nothing ever resets.

## Copy (complete set, tone: friendly, concise, granting)

- Question screen title: "One question first."
- Char counter: "50 characters min" (live count).
- After submit: "Noted. Ready at 18:42."
- Main-screen pending line: "Pause ready at 18:42" / "Ready — confirm
  before 19:12".
- Confirmation: the user's answer, then "Still want this?" — buttons
  "Yes" / "No, I'm good".
- After cancel/lapse acknowledgement: "Nice. That one passed."
- After confirm (pause): "Done — resumes at 19:15."

No other text. No lectures, no stats besides the one counter.

## Data model

New DataStore keys (all in the existing preferences store):

| Key                  | Type   | Meaning                                        |
|----------------------|--------|------------------------------------------------|
| `pendingType`        | String | `""` = none; `pause` / `disable` / `remove` / `mode` |
| `pendingParam`       | String | pause: minutes; remove: pkg names (separator-joined) |
| `pendingQuestionIdx` | Int    | which question was asked                       |
| `pendingAnswer`      | String | the typed answer                               |
| `pendingReadyAt`     | Long   | epoch millis when confirmable                  |
| `questionCursor`     | Int    | rotation position in the question pool         |
| `urgesOutlasted`     | Int    | lifetime counter                               |

Expiry is derived: `pendingReadyAt + 30 min`. Request creation time is not
needed after submit (journal records the timestamp).

`shouldBlock` and the BlockerService hot path are **untouched**. The gate
lives entirely in the main app UI + repository; only a confirmed request
mutates `enabled` / `blockedPackages` / `pausedUntil`.

### Request lifecycle (pure logic, unit-testable)

```
none --submit answer--> waiting(readyAt)
waiting --cancel--> outlasted (journal: cancelled)
waiting --now >= readyAt--> ready
ready --confirm--> applied (journal: confirmed; mutate state)
ready --decline--> outlasted (journal: cancelled)
ready --now >= readyAt + 30min--> outlasted (journal: lapsed; lazy)
any --new gate flow--> replaced (journal: replaced, no counter), then waiting
```

## Edge cases

- **Reboot / process death mid-wait:** all state is timestamps on disk;
  nothing to do.
- **Clock manipulation:** out of scope (friction, not fortress).
- **Blocked app opened during wait:** still blocked — decision function
  reads only the existing keys.
- **Own app never blocked:** unchanged (SELF_PACKAGES), so the gate is
  always reachable.
- **Paste prevention:** the answer field filters paste/drag-and-drop
  input; typing only. Autocorrect may remain (typing is the point, not
  spelling).
- **50-char check:** on trimmed text; whitespace padding doesn't count.
- **Pending `remove` for an app that got uninstalled meanwhile:** stale
  package names are harmless (v1 behavior); confirm just removes them.

## Out of scope (explicitly deferred)

- Substitution button on the block screen (decided against for now).
- Escalating wait times / daily pause budgets / emergency quota.
- Attempt counter on the block screen.
- Journal browser UI, trigger analytics, notifications when ready
  (remembering is the user's job — forgetting is a feature).

## Testing

- **Unit:** request lifecycle transitions (all edges above, incl. lapse
  and replace), answer validation (length, trim), question rotation
  wraparound, journal append/read round-trip, urges-outlasted counting.
- **On-device verification:** with blocking on — (1) pause request:
  question → 50-char enforcement → paste blocked → wait shown → app still
  blocked during wait → confirm → pause runs → auto-resume; (2) decline
  path increments counter; (3) lapse path: request, wait 35+ min (or
  shortened debug timings), reopen app, counter incremented, still
  blocked; (4) toggle-off request uses 30-min wait; (5) reboot mid-wait
  preserves the request.

## Success criteria

Every unblock-ward action costs a typed reflection plus a wait plus an
explicit confirmation. A request left unconfirmed changes nothing. The
whole flow reads as calm and minimal — a handful of short sentences, one
counter, no scolding anywhere.
