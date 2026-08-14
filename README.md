# App Blocker

Personal Android app blocker (Pixel 9, sideloaded). Pick apps, flip the
toggle; blocked apps bounce to a block screen. Pause for 1/5/15/60 minutes
from the Advanced section — auto-resumes.
Unblock-ward actions (pause, disable, removing apps) pass through the
Unblock Gate: answer one reflective question (50+ chars, typed), wait
(5 min; 30 min for full disable), then confirm within 30 min — or the
request lapses and blocking continues. Spec:
docs/superpowers/specs/2026-08-14-unblock-gate-design.md

- Spec: docs/superpowers/specs/2026-08-13-app-blocker-design.md
- Build & install: `./gradlew installDebug` (device with USB debugging)
- Enable: the app's warning banner links to Accessibility settings.

Backlog: domain blocking, schedules.
