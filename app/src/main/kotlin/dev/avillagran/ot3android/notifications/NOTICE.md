# Notice: T3 Mobile notification module

`Ot3Notifications.kt` and `LiveUpdates.kt` adapt parts of T3 Mobile's Android notification
module, MIT licensed, Copyright (c) 2026 T3 Tools Inc. (full text:
`app/src/main/assets/licenses/t3code-MIT.txt`, the repository's `LICENSE`).

- Repository: <https://github.com/pingdotgg/t3code>, commit `680152e27b` (2026-10-21).
- Files read (paths under
  `apps/mobile/modules/t3-agent-notifications/android/src/main/java/expo/modules/t3agentnotifications/`):
  `AgentNotifications.kt` (channels `:310-323`, base builder `:325-341`, ongoing card
  `:247-291`, alert grouping `:164-203`, foreground suppression `:136-162`),
  `AgentActivityPresentation.kt` (phases and chip words `:19-70`, chip `:128-134`, card text
  `:138-163`) and `T3AgentNotificationsModule.kt` (Live Update settings `:37-53`).
- What was adapted: two channels (low activity, high alerts), the ongoing card that asks
  for promotion only while work is live and is never colourised, the chip words
  Working / N live / Approve / Answer with a 9+ cap, alerts grouped as attention and
  finished with a silent summary, absolute expiry through `setTimeoutAfter`, and the
  API 36 promotion settings action.
- What differs: no FCM, relay or account state; every phase, count, transition and
  expiry comes from lib-t3-rust (`docs/LIB_REQUESTS.md`, U28) instead of a relay payload;
  alerts are tagged by their stable id (no `hashCode` ids); the card never calls
  `NotificationCompat.setSilent`, which adds a synthetic group on API 26+; ot3 strings,
  status colours and lock-screen modes; Approve opens the thread and never approves.
