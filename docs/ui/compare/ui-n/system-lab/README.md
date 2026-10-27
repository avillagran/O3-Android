# Follow service system lab

This increment exercises the actual ThreadFollowService, AppGraph, EnvironmentRepository,
ConnectionManager follow/foreground leases, NativeT3Transport/JNI, shell reduction and
Rust v0.3.5 awareness.view. Two loopback-only synthetic HTTP/Effect-RPC servers supply
representative private titles and transitions; no production URL/token or NODE/VQ is
used. The test does not inject already-built notification/card/alert objects.

T3 Mobile source reference: PIN680152e27 AgentNotifications.kt alert groups/summary
and private base visibility, with shared agentAwareness transition policy. Android's
Full/Status/Hidden preference is documented in UI_SPEC4.14.7; Hidden retains the notification while withholding private contents. On the observed
API36.1 SystemUI the locked expanded row shows its app header/chronometer only,
without a literal contents-hidden message. The native awareness
output owns transitions, counts, stable IDs, group membership, lines and routes.

Runtime bug fixed: ThreadFollowService previously built each live card with the default
Full privacy. A one-time privacy refresh made State/Hidden initially appear correct,
but a later snapshot/deadline republished Full while the unchanged preference skipped
refresh. Every live-card build now receives the current privacy, matching alerts and
summaries. The opt-in service test publishes two further snapshots under each setting
and checks the actual posted notification after each publication.

The lab installs a synthetic PIN only when the emulator has no credential, configures
lockscreen notifications to redact private content, uses actual notification settings
radio controls and native UiAutomation touch/key events, then removes its PIN and
restores all changed settings/preferences/environments. It refuses existing saved
servers and requires an explicit opt-in and API36.1 emulator. Do not run on a phone.

Doze, process death, natural timeout/expiry, productive environments, physical/OEM lock
screens and automatic revocation remain outside this increment. Cold service baseline
and on-screen suppression are not claims of process-death or Doze acceptance.

Follow lease ownership is now incremental. Updating lastConnectedAt or a label no
longer tears down every lease; removing one environment releases only its lease.
Observers still follow ConnectionManager's current supervisor per environment, and
an unauthorized callback from a retired supervisor cannot close a replacement's
lease. Partial acquisitions are covered by the service coroutine's try/finally.
The lab explicitly repeats markConnected while backgrounded, renames a host without
reconnecting, then removes one environment while retaining the other's stream.

The radio controls are exercised while the real app is visible. For the three locked
captures, the lab changes that same DataStore input while one synthetic credential
remains installed, publishes two more snapshots per mode and opens the observed
SystemUI notificationShelf. This avoids treating PIN-entry automation as acceptance.
The credential is removed before grouped-alert/Open checks; secure unlock UI is not
claimed. Initial compact lockscreen icons are retained only as context; expanded
locked-card captures are required to assess content privacy.

The native lab also exposed Android notification enqueue shedding: repeated unchanged
cards and duplicate cancels exceeded the platform's observed5/s queue budget, and a
real Working update was dropped while Approval remained visible. Presentation now
compares the complete parsed AwarenessCard plus privacy; it never deduplicates native
baseline inputs or alerts. Missing/stale Android records are recognized with a random
UI-only stamp in notification extras and retried only on the existing deadline wake.
Metadata cannot postpone that wake indefinitely. No new polling loop or arbitrary
throttle is introduced. Privacy refresh skips an already-correct record and the service
skips its freshly-built activity card when refreshing existing alerts/summaries.
Absent IDs are not repeatedly cancelled; freshly posted native group summaries are not
retired before Android has had time to enqueue their children.

The privacy regression changes actual card titles, then restores them before each
capture; sequence-only snapshots are intentionally deduplicated. The lab simulates a
stale platform record by removing its UI stamp, then verifies recovery on the real
existing deadline. It also verifies active→finished detaches the card from the FGS,
retains a future absolute expiry, and disabling Follow releases background leases.
Natural15-minute expiry, manual dismissal semantics and high-rate token streaming are
not separately accepted by this lab.

Harness notes: initial nonsecure keyguard is explicitly dismissed before querying
Compose; isDeviceLocked=false alone does not mean its window has disappeared.
SystemUI notification titles overlap expand-header hit regions on this API. Open is
exercised through ACTION_CLICK on the observed clickable notification-row ancestor,
invoking the real system PendingIntent; no synthetic deep link is used as acceptance.

Several isolated guests exited gracefully outside requested stop operations. Correlation
with launcher/driver cleanup is unproven. The snapshot-helper close command used the
exact5580 launcher/session and no --shutdown. No adb server kill or5584 operation was
issued. After a killed guest, optional followCleanupOrphans=true only removes this
lab's known127.0.0.1 follow-lab-a/b rows; every other saved server still fails the guard.


## Verified run and evidence

API36.1 emulator-5580, synthetic loopback transport with real JNI, 2026-10-26:
FollowSystemDeviceTest passed (1/1, 40.533s). Focused app notification/visibility
JVM tests passed 54/54; core awareness/connection/preferences tests passed 14/14.
Debug app/test APK build and lint passed. No production dispatch was made.

[Verification](device/verification.json) records the native groups, posted privacy,
secure-keyguard state, route received, one shell subscription per environment,
stale-record recovery after 17.995s, lease release and credential cleanup.
[Full](device/lock-full-expanded.png), [Status](device/lock-state-expanded.png),
and [Hidden](device/lock-hidden-expanded.png) show the same representative data on
secure keyguard. [Preferences](device/preferences.png),
[groups](device/grouped-shade-expanded.png), and
[opened thread](device/notification-open-thread.png) show actual app/SystemUI surfaces.
The [short video](device/groups-open.mp4) shows the grouped shade and real Open route;
it intentionally excludes later launcher/heads-up transitions. The finished-card
assertions inspect the posted system record; its transition screenshot was not retained
because it captured the launcher during a heads-up transition.

Run only on an isolated, empty API36.1 emulator using the built debug/test APKs:

```sh
adb -s emulator-5580 shell am instrument -w \
  -e class dev.avillagran.ot3android.notifications.FollowSystemDeviceTest \
  -e followSystemLab true -e followSystemEvidence true \
  dev.avillagran.ot3android.test/androidx.test.runner.AndroidJUnitRunner
```

The opt-in refuses a pre-existing credential or saved environment. It restores its
synthetic PIN, secure settings, follow preferences and owned environments in finally.
Notification permission granted through the real system prompt is restored separately
by the lab operator after instrumentation, since revoking it terminates the app.

The Open assertion must pump Compose's test clock (`waitUntil`): a coroutine-only poll
after external SystemUI ACTION_CLICK leaves the received deep link waiting for Compose
recomposition. This was a harness issue; no navigation production change was needed.
At this 2026-10-26 checkpoint, Doze/process death were still pending. The later
[resilience lab](../resilience/README.md) adds forced-Doze/SIGKILL evidence with
explicit limits. Natural15-minute expiry and OEM/physical behavior remain pending.
