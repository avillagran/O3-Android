# UI-N: notifications and Live Updates — preparation and acceptance

Spec: [UI_SPEC 4.14](../../../UI_SPEC.md#414-notifications-and-live-updates). Decision:
[DECISIONS 2026-10-26](../../../DECISIONS.md). Lib request: [LIB_REQUESTS U28](../../../LIB_REQUESTS.md).

**Status: preparation only.** This branch ships documentation, a status bar icon, two
channels, presentation builders and a parser for the proposed lib objects, with
Robolectric tests. Nothing posts a notification, no service exists, the manifest is
unchanged, and no screen or setting was added. No capture, video or device result exists
for UI-N yet; none was produced from fixtures.

## What is in this PR

| Piece | File | Tested by |
|---|---|---|
| Channels `ot3-activity` (LOW, no badge) and `ot3-alerts` (HIGH) | `app/src/main/kotlin/dev/avillagran/ot3android/notifications/Ot3Notifications.kt` | `NotificationPresentationTest.channelsAreLowActivityAndHighAlerts` |
| Ongoing card: ongoing + promotion request only while active, never colourised, title fallback, chip, stable timer, Approve/Answer/Open open the thread, absolute timeout | same | `activeCardIs…`, `cardShowsElapsedTime…`, `approvalOffersApprove…`, `finishedCardDrops…` |
| Chip words from the lib's kind and count, `9+` cap, ≤ 7 characters in English | same | `chipWordsTheLibsKind…` |
| Alerts grouped `ot3-attention` / `ot3-finished`, tag = lib id, silent summary, expired alerts not built | same | `alertsAreGrouped…`, `expiredDoneAlertIsNotBuilt` |
| Lock screen: Full (default), Status only, Hidden | same | `lockscreenStateHidesTitles…` |
| Live Update support, promotion check and settings action per API level | `LiveUpdates.kt` | `liveUpdateSupportAndSettingsFollowTheApiLevel` |
| Proposed lib objects (`card`, `alerts`), malformed input dropped | `NotificationContract.kt` | `malformedLibObjectsAreDroppedNotGuessed` |
| Status icon from the bundled T3 mark, white alpha mask | `app/src/main/res/drawable/ic_stat_ot3.xml` | `NotificationIconTest` |
| Strings: 4 ot3 catalog rows (`ot3_notif_*`) via the generator; 4 mobile-only (`mobile_notif_*`) | `ot3-strings.tsv`, `strings_mobile.xml`, `docs/ui/strings.md` | `tools/import-ot3-strings --check` |
| Licence and notice for the adapted T3 Mobile module | `docs/LICENSES.md`, `notifications/NOTICE.md`, `assets/licenses/t3code-MIT.txt` | review |

The test class runs on **API 24, 26, 33 and 36**, T3 Mobile's own matrix
(`t3:docs/operations/android-notifications.md:13`). Robolectric checks what the app
*requests*; its API 36 image predates the shipped promotion rules (same file `:21`), so
no test asserts that a card *is* promoted.

## API matrix

| Behaviour | API 24–25 | API 26–32 | API 33–35 | API 36.0 | API 36.1+ (Android 16 QPR2) |
|---|---|---|---|---|---|
| Channels | none; priority LOW / HIGH | `ot3-activity` LOW, `ot3-alerts` HIGH | same | same | same |
| Notification permission | install-time | install-time | runtime `POST_NOTIFICATIONS`, asked in context | same | same |
| Ongoing card | yes | yes | yes | yes | yes, asks promotion |
| Status bar chip | no | no | no | no (promotion API is 36.1) | if `canPostPromotedNotifications()` and the system promotes it; OEMs may add criteria |
| Card expiry | service cancels while running; inexact `setAndAllowWhileIdle` alarm in runtime tranche | `setTimeoutAfter` | same | same | same |
| Live Update settings action | app details | app notification settings | same | `ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` | same |
| FGS start from background | allowed | allowed (≤ 30) / restricted (31+) | restricted | restricted | restricted |
| `specialUse` type | n/a (FGS without type) | n/a until 34 | 34+: `FOREGROUND_SERVICE_SPECIAL_USE` + subtype property | same | same |

## Acceptance for the runtime tranche (not claimed here)

1. U28 released in a lib version pinned by `libs/lib-t3-rust.lock`; `NotificationContract`
   fixtures replaced by the lib's own outputs; no notification rule in Kotlin.
2. JVM: supervisor leases (UI `ON_STOP` keeps Follow leases; one stream per environment;
   forget/revoke releases), service lifecycle (start only from visible, idle stop at the
   configured minutes, Disconnect, `onTimeout`, permission revoked), baseline persistence
   across process death, thread-on-screen suppression.
3. Robolectric 24/26/33/36: the above builders posted through the service; foreground
   card is the FGS notification; dismissal of a finished card is respected.
4. Emulator (API 36.1 `google_apis` x86_64), recorded ≤ 10 s each, with Settings showing
   Live Updates allowed: status bar chip `Working` → `Approve` → card `Done`; lock screen
   in each lock-screen mode; shade collapsed and expanded with 3 threads; two alerts
   grouped; tap and Approve opening the thread with the visible approval card; Disconnect.
   Each capture states whether `FLAG_PROMOTED_ONGOING` was set (dumpsys), not inferred.
5. Lower API smoke on the existing API 34 AVD: normal ongoing card, no chip.
6. A downloadable pre-release APK for Andrés's own device. Physical Android 16 / OEM chip
   evidence only from Andrés's capture; the Xiaomi stays untouched unless he asks.

## Emulator inventory (read-only, 2026-10-26)

No emulator was started and no device was reserved: the bridge-closure agent owns them.

- Installed: `system-images;android-34;google_apis;x86_64` rev 14 only; AVDs (`ot3a`,
  `test`, `rvtest`, `omashort_tablet`, `ohm_release_006_20261002`,
  `plebs_store_isolated`) all target `android-34`.
- Available, not installed (`sdkmanager --list`): `system-images;android-36.1;google_apis;x86_64`
  rev 4 (Android 16 QPR2, the promotion API), also `android-36;google_apis;x86_64` rev 7.
- SDK: `platforms/android-36` (compileSdk) has `canPostPromotedNotifications`,
  `FLAG_PROMOTED_ONGOING`, `ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`;
  `POST_PROMOTED_NOTIFICATIONS` and `EXTRA_REQUEST_PROMOTED_ONGOING` first appear in
  `platforms/android-37.0`. androidx.core 1.17.0 (already pinned) provides
  `setRequestPromotedOngoing`, `setShortCriticalText`, `canPostPromotedNotifications`.

## Steps after the current priorities (UI-2a, bridge catalog)

1. Lib: implement and release U28 (`awareness.view`) with shared fixtures; pin it.
2. Root installs `system-images;android-36.1;google_apis;x86_64` and creates a dedicated
   AVD through `scripts/emulator.sh` when the emulator is free.
3. Runtime PR: `ConnectionManager` leases; `FollowService` (`specialUse`, subtype
   property, `onTimeout`); manifest permissions `POST_NOTIFICATIONS`,
   `POST_PROMOTED_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`;
   Settings → Notifications (toggle, idle minutes, lock-screen detail, Live Update
   settings, permission rationale); baseline in DataStore; on-screen thread reported
   from navigation.
4. UI-4's visible approval/question card is the target of Approve/Answer; until it
   lands, those actions open the thread as today.
5. Captures and recordings of acceptance item 4, then the pre-release APK.

## Official Android sources (read 2026-10-26)

- Live updates guide (eligibility, chip, user control; updated 2026-10-23):
  <https://developer.android.com/develop/ui/views/notifications/live-update>
- `Notification.Builder.setRequestPromotedOngoing` (added in 36.1):
  <https://developer.android.com/reference/android/app/Notification.Builder>
- `NotificationManager.canPostPromotedNotifications`:
  <https://developer.android.com/reference/android/app/NotificationManager>
- Android 16 CDD 3.8.3.1 (API 36.1 devices: lock screen, status chip, per-app control;
  updated 2025-12-02): <https://source.android.com/docs/compatibility/16/android-16-cdd>
- Foreground service types (`dataSync`, `specialUse`, subtype property; updated
  2026-10-23): <https://developer.android.com/develop/background-work/services/fgs/service-types>
- Android 15 behaviour changes (dataSync 6 h / 24 h, `onTimeout`, BOOT_COMPLETED;
  updated 2026-10-23): <https://developer.android.com/about/versions/15/behavior-changes-15>
- Background start restrictions (updated 2026-10-23):
  <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>
- Notification runtime permission (updated 2026-10-23):
  <https://developer.android.com/develop/ui/views/notifications/notification-permission>
- Doze and App Standby (updated 2026-10-22):
  <https://developer.android.com/training/monitoring-device-state/doze-standby>
- Alternatives to dataSync (updated 2026-02-26):
  <https://developer.android.com/about/versions/15/changes/datasync-migration>

Documentation discrepancy found: the live-update guide names
`Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`; neither `android-36` nor
`android-37.0` `android.jar` has it; both have `ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`
(value `android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS`), which T3 Mobile uses.
