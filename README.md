# App Blocker

A minimal Android app blocker I built for myself after every blocker I tried put
pausing behind a paywall. I've been running it on my own phone daily since —
it's the reason I can keep Instagram and YouTube installed without doomscrolling.

Pick the apps to block, flip the toggle. Opening a blocked app shows a calm
block screen and returns you to home. Pausing is free, instant, and
auto-resumes. That's the whole app — plus two ideas that make it actually work.

## Friction, not fortress

The app doesn't try to be tamper-proof; you can always uninstall it or revoke
its permissions. Its job is to break the autopilot loop — the open-scroll-close
you never actually decided to do. It turns out a few minutes of friction is
enough.

## The Unblock Gate

Every action that reduces blocking — pausing, removing an app from the list,
turning a mode or the blocker off — goes through the same flow:

1. **Answer one reflective question.** Typed, at least 50 characters, paste
   disabled. One of ten rotating questions, e.g. *"What are you hoping to find
   in there?"*
2. **Wait.** From 1 minute (short pause) up to 30 minutes (full disable),
   scaled to how much you're asking to unlock. Blocking stays on the whole time.
3. **Confirm.** The app shows you the answer you just wrote and asks *"Still
   want this?"* If you don't come back within 30 minutes, the request quietly
   lapses and blocking continues.

The answer is never "no" — only "yes, in a few minutes." But the default
outcome of getting distracted mid-wait is the healthy one. Cancelled and lapsed
requests feed a single quiet line on the main screen: **Urges outlasted: N**.
Answers are journaled to a local file on the device; nothing is uploaded
anywhere.

## Partial modes

For the two apps where all-or-nothing doesn't work:

- **Instagram, messages only** — DMs work normally; the feed, Reels, and
  Explore redirect to your inbox.
- **YouTube, no Shorts** — everything works except the Shorts player.

## How it works

An `AccessibilityService` is notified on every foreground-window change and
consults a pure decision function against state persisted in DataStore. Block
means: fire the global HOME action and show the block screen. A pause is just a
timestamp — no alarms, no background jobs; blocking resumes because time
passes. The partial modes classify screens by view IDs in the accessibility
tree (tuned to fail open: a wrong block is worse than a missed one). No screen
content is read, and nothing ever leaves the device.

- Kotlin, Jetpack Compose, Material 3, DataStore — single module, no
  third-party dependencies beyond AndroidX.
- All decision logic (blocking, gate lifecycle, screen classification) is pure
  and covered by JVM unit tests: `./gradlew :app:testDebugUnitTest`.
- Design docs: [docs/design.md](docs/design.md) and
  [docs/unblock-gate.md](docs/unblock-gate.md).

## Installing

There's no published APK — you clone the repo, build it, and install it over
USB.

1. Requirements: Android 15+ on the phone, JDK 17 and the Android SDK on your
   machine.
2. Enable Developer options and USB debugging on the phone, connect it, then:

```bash
./gradlew installDebug
```

3. Open the app — a banner links you to the Accessibility settings page where
   the service is enabled.

Use at your own discretion; it's a personal tool that I'm happy to share, not a
supported product.

## License

[MIT](LICENSE)
