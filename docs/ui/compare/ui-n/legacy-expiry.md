# Legacy notification expiration (2026-10-27)

API24/25 do not implement NotificationCompat.setTimeoutAfter. Before this change, a
normal finished card or expiring alert could remain after follow service teardown:
the production privacy-repost path left no independent expiry delivery. The new
regression was RED on both APIs (zero scheduled alarms, expected one).

The presentation adapter now schedules the absolute `ot3.expiresAt` supplied by the
Rust awareness output. It does not derive phases, TTLs, transitions or eligibility.
An inexact **RTC non-wakeup** alarm targets a non-exported receiver. Device idle may
delay removal until Android delivers the alarm; no exact-alarm permission, wake-during-idle
request, periodic polling or stream/service restart is introduced. API26+ retains its
native timeout path. An early delivery reschedules the same absolute deadline.

The small preferences record contains notification slot, generation and deadline,
committed before NMS enqueue. Alarm and dismissal callbacks check ownership before
canceling. Replacement without a deadline removes the old registration before posting.
A presentation stamp rejects stale platform snapshots during privacy refresh, including
when the newer publication is still queued to NMS. Privacy changes preserve the absolute
deadline. The record is not cleared by service teardown and contains no notification
body or credentials. Only expirable presentations are registered; auto-cancelled alerts
can retain that small record until their received deadline, when cleanup removes it.

## Verification

`LegacyNotificationExpiryTest` exercises API24/25 Robolectric AlarmManager/PendingIntent
delivery to an explicitly registered **production receiver**, using a controlled wall
clock. It checks service onDestroy, persisted registration removal, stale alarm/delete
callbacks against an in-flight replacement, replacement without expiry, early delivery,
privacy deadline preservation and stale platform snapshots. API26/36 assert native
timeout with no legacy alarm. This is not process-death, OS delivery-latency or physical
legacy-device acceptance; the manifest receiver declaration is built/linted, while the
test registers its receiver explicitly because it injects the wall clock. No emulator,
phone, live transport, production notification or new legacy system image was used.

Pending: API24/25 device acceptance; reboot/time-change delivery behavior; OEM idle
latency; summaries left after their last child expires/dismisses. Summaries have no
Rust deadline. No summary cleanup change is made here: NMS can cancel enqueued children
when a summary is removed, so an empty active snapshot alone is insufficient evidence.
Existing system-lab and resilience evidence remains separately scoped.

## Primary sources

- [AndroidX NotificationCompat](https://android.googlesource.com/platform/frameworks/support/+/0624f640fd3a47edfcf8a070f609d278fb5eb41b/core/core/src/main/java/androidx/core/app/NotificationCompat.java): timeout is a no-op before API26.
- [AlarmManager](https://developer.android.com/reference/android/app/AlarmManager): inexact scheduling, RTC non-wakeup and idle restrictions.
- [AOSP NotificationManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/notification/NotificationManagerService.java): enqueue/cancel serialization and cancelGroupChildrenByListLocked include enqueued notifications.

Final local QA after integrating main `d7547bf`:

```sh
JAVA_HOME=$HOME/.local/jdk ./gradlew :app:testDebugUnitTest \
  --tests 'dev.avillagran.ot3android.notifications.*' :app:lintDebug :app:assembleDebug \
  -Pot3a.requireBridgeSdk=true -Pot3a.requireHostJni=true --console=plain
```

PASS: 73 notification tests, zero failures, one existing opt-in skip (72 executed),
including all 18 legacy-expiry cases; lint zero errors and debug APK build passed.
No new screenshot/video is claimed: this increment measures scheduler/receiver behavior.
