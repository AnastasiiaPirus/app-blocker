# App Blocker — Design Spec

**Date:** 2026-08-13
**Status:** Approved for implementation planning

## Purpose

A minimalistic, free, personal Android app blocker. Select apps to
block; when blocking is on, opening a blocked app immediately shows a full-screen
block screen and returns you to the home screen. Blocking can be paused for a
chosen number of minutes (instant, auto-resumes) or disabled entirely.

Motivation: commercial blockers put pause functionality behind a paywall.

## Guiding principles

- **Friction, not fortress.** The block breaks the autopilot habit loop. It is
  not tamper-proof and does not try to be. The user can always uninstall the app
  or turn off its permissions; that's acceptable.
- **Keep it simple.** One screen up front: app list + master toggle. Everything
  else lives in a collapsed "Advanced" section.
- **Sideload only.** Never distributed via Play Store. No Play policy
  constraints apply (accessibility API use is fine).

## In scope (v1)

1. Select which installed apps are blocked (searchable checklist).
2. Master toggle: blocking on / off.
3. Blocked app opened while blocking is active → full-screen block screen,
   then home screen.
4. Pause for N minutes (presets: 1, 5, 15, 60). Instant effect, auto-resumes
   when time elapses. Lives in the Advanced section.
5. "Resume now" while paused; countdown shown on main screen.
6. Warning banner when the accessibility service is not enabled, deep-linking
   to system Settings.

## Out of scope (backlog, in priority order)

- **Domain blocking** (requires a local VPN service or Private DNS approach —
  a separate design when we get to it).
- **Schedules** ("block this app from HH:MM to HH:MM").
- **Pause friction** (countdown or typed phrase before a pause takes effect).

## Architecture

Mechanism: **AccessibilityService** — the same approach commercial blockers
use. Android notifies the service on every foreground-window change; the
service decides whether to block. Event-driven (no polling, no battery cost),
instant (blocked app is never meaningfully visible), restarts automatically
after reboot.

### Components

**1. `BlockerService` (AccessibilityService)** — the engine.
- Subscribes to `TYPE_WINDOW_STATE_CHANGED` events.
- On event: extract foreground package name, call the decision function; if it
  says block → `performGlobalAction(GLOBAL_ACTION_HOME)` and launch the block
  screen activity.
- Keeps an in-memory snapshot of state (collected from DataStore via a
  coroutine); the event hot path does zero disk I/O.

**2. Block screen (full-screen activity)** — blocked app's icon, "Blocked",
one "Close" button that finishes to the home screen. Deliberately no pause
shortcut here: pausing requires going to the main app.

**3. Main app (single-Activity Jetpack Compose UI)**
- Master toggle at top.
- Blocked-apps list below it; "Edit apps" opens a searchable checklist of all
  launchable installed apps.
- Collapsed **Advanced** section: pause buttons (1 / 5 / 15 / 60 min).
- Paused state: "Paused — resumes in M:SS" + "Resume now" button (the only
  timer in the app is this UI countdown).
- Warning banner when the accessibility service is off.

**4. State store** — Jetpack DataStore (Preferences) behind a small repository
class. Three values:

| Key               | Type        | Meaning                                  |
|-------------------|-------------|------------------------------------------|
| `enabled`         | Boolean     | Master toggle                            |
| `blockedPackages` | Set<String> | Package names to block                   |
| `pausedUntil`     | Long        | Epoch millis; `0` = not paused           |

### Decision logic

Pure function, the single source of truth for "should this launch be blocked":

```
shouldBlock(pkg, state, now) =
    state.enabled
    && now >= state.pausedUntil
    && pkg in state.blockedPackages
    && pkg not in SELF_PACKAGES   // own app + block screen never blockable
```

**Pause is just a timestamp.** No alarms, no scheduled jobs, no service
lifecycle. Blocking auto-resumes because time passes and the service compares
against `pausedUntil` on every event.

## Edge cases

- **Reboot:** enabled accessibility services restart automatically; state is
  on disk. Nothing to do.
- **Service disabled / revoked:** main screen shows the warning banner with a
  deep link to accessibility settings. The app never silently pretends to
  block.
- **Self-protection:** own package is excluded in the decision function so the
  user can always reach the toggle/pause UI.
- **Blocked app uninstalled:** stale package names in the set are harmless;
  the edit list only shows installed apps, and stale entries are pruned when
  the list is saved.
- **System apps:** the edit list shows only apps with a launcher intent, which
  keeps launchers/system UI out of the list naturally.

## Tech stack

- Kotlin, Jetpack Compose, Material 3 (follows system dark mode).
- Single Gradle module, no dependencies beyond AndroidX + Compose BOM.
- `minSdk = 35` (Android 15; a personal project with no legacy devices to
  support), `targetSdk` = latest stable.
- Version control: git.

## Testing

- **Unit tests:** `shouldBlock` decision function (all clause combinations),
  repository read/write round-trip, pause-countdown formatting.
- **On-device verification:** build → `adb install` → enable service → confirm:
  blocked app bounces to block screen; pause lets it open; auto-resume blocks
  again; toggle off lets everything open; reboot keeps blocking.

## Dev environment & workflow

- Host: macOS. Install via Homebrew: Temurin JDK 17, Android command-line
  tools → `sdkmanager` for platform + build-tools + platform-tools (`adb`).
  No Android Studio required.
- Device: an Android 15+ phone with Developer options + USB debugging enabled
  (one-time manual step; requires accepting the RSA fingerprint prompt).
- Loop: `./gradlew installDebug` over USB; `adb` also used for smoke checks
  (launching apps, screenshots) during verification.

## Success criteria

Opening a blocked app while blocking is on lands you on the block screen and
then home, within a beat. "Pause 5 min" from Advanced immediately lets the app
open, and 5 minutes later it's blocked again with no further interaction. The
master toggle kills/restores blocking instantly. Total cost: $0.
