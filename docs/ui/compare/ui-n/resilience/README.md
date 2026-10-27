# Follow resilience lab — 2026-10-27

[Checks and chronology](checks.json) record the real MainActivity/AppGraph, Room,
Keystore, ThreadFollowService and pinned v0.3.5 JNI on API 36.1/emulator-5580. Two
synthetic loopback servers run in a **separate host JVM**. The app is not instrumented
during measured Doze/process behavior: an opt-in test APK targets its own package/UID
(helper 10226, target 10236). It opens no product IPC and is not an app dependency.
`follow-lab` is included only with `-Pot3a.followLab=true`.

## Results and limits

- Initial pending work seeded silently. Two native approval child alerts were later
  created by actual running→approval shell transitions. The fixture observed zero
  client command dispatches; all production environments/devices remained untouched.
- Controlled SIGKILL requested only the verified debuggable app PID: State 3871→15470,
  Full 15762→17231 after visible reopen. During dead-process observations, package
  `stopped=false` distinguished this from force-stop. No automatic restart was observed
  for 5.318 s/5.110 s. Room environment identity, credential access, opt-in and State/Full
  privacy survived; visible re-entry restarted the FGS and one steady shell per host.
  Two retained native alert keys had identical NotificationRecord.mUpdateTimeMs before
  and after each cold process: they were not merely deduplicated by counting tags.
- This is **SIGKILL, not LMK/OOM**. `am kill` was not used (it is not a reliable way to
  terminate a foreground-service process), and neither was `am force-stop` in measured
  cycles. Preparation/privacy selection used separate completed instrumentation runs;
  those are not process-death acceptance. Android may deliver pending start intents;
  START_NOT_STICKY is not an unconditional promise that no process can ever restart.
- Forced deep Doze was observed as DeviceIdleController IDLE with FGS alive and the
  target absent from observed permanent/except-idle/temporary allowlists. Working
  remained until a controlled fixture outage was observed; Rust then presented Waiting
  during IDLE. After exit it remained Waiting until reconnect, then Working returned.
  **Loopback/ADB reverse does not prove actual Doze network enforcement or CPU sleep**,
  natural maintenance windows, or immediate stale presentation just from entering idle.
- A concurrent observer records blackhole arming, fresh-frame send, pings and successor
  streams while the driver opens the app. Both successors were observed by 4.525 s on
  resume and 5.418 s after WiFi change, before the native 20 s silence budget. The native
  source has 5 s pings/3 s explicit probes/20 s idle timeout. PID stayed unchanged. Network
  switching may also break TCP; its recovery is not attributed exclusively to
  wakeSubscriptions. The visible lifecycle/service path and default-network callbacks
  call wakeAll in source; no new retry or awareness rule was added here.

The opaque baseline is **session-local**, not persisted DataStore state. The measured
cold silence is Rust's existing policy. Idle stop is fixed 15 min; the originally planned
5/15/30/60 selector and explicit onTimeout callback are not implemented. A long lab run
with active cards does not test a 15-minute interval without active work. Natural expiry,
OEM/physical behavior, arbitrary process management and automatic remote revocation
remain pending. No runtime behavior change was needed; only the lifecycle comment and
stale documentation were corrected.

## Harness and reproduction

Only use an empty isolated API 36.1 emulator. Preparation refuses saved environments,
existing credentials, a previous unclean lab state and non-emulator hardware. It grants
notification permission through the actual visible system dialog only if needed,
selects State in real Settings, saves two loopback environments and arms cleanup before
writes. The controller verifies a different UID, a debuggable target and the known lab
state; it binds 127.0.0.1 and requires a private ephemeral nonce. Fixed actions use
UiAutomation for Home/sleep/wake, battery/deviceidle/WiFi and verified-PID termination.
No generic shell endpoint or lifecycle control is added to the application.

1. Build before starting the emulator/long host worker:

   ```sh
   ./gradlew -Pot3a.followLab=true :app:assembleDebug :app:assembleDebugAndroidTest \
     :follow-lab:assembleDebugAndroidTest
   ```

2. In a private directory outside Git, run the bounded 20-minute host fixture in its own
   terminal/session (heap 256 MiB). It writes ready.json containing two loopback URLs:

   ```sh
   ./gradlew :app:testDebugUnitTest --tests '*FollowExternalFixtureHostTest' \
     -Pot3a.followLabDir=/tmp/ot3-follow-resilience
   ```

3. Discover/open the exact emulator through device_list/device_open, retaining its
   returned launcher/config/session. Install app-debug.apk, app-debug-androidTest.apk
   and follow-lab-debug-androidTest.apk on **-s emulator-5580**. Reverse each ready.json
   port to the same host port. Run the opt-in preparation, supplying those two ports:

   ```sh
   adb -s emulator-5580 shell am instrument -w -e followResilienceLab true \
     -e portA "$portA" -e portB "$portB" \
     -e class dev.avillagran.ot3android.notifications.FollowResilienceDeviceTest#prepareState \
     dev.avillagran.ot3android.test/androidx.test.runner.AndroidJUnitRunner
   ```

4. Generate a UUID nonce in a 0600 file in the private directory. Start the separate
   controller instrumentation, passing followControllerLab=true, followControllerNonce,
   followControllerPort=18873, class dev.avillagran.ot3android.followlab.FollowControllerTest,
   component dev.avillagran.ot3android.followlab.test/androidx.test.runner.AndroidJUnitRunner.
   Keep that invocation alive in its own session; a detached child of a completed tool
   cell may be terminated by host cleanup. Forward own host 18874→device 18873.

5. Open the **real app** with the returned agent-device command, --no-test-ime and
   --serial emulator-5580; do not use --relaunch. The client commands below operate only
   the test fixture/controller. Keep raw status outputs in the private directory:

   ```sh
   python3 scripts/follow-resilience-lab.py fixture stats --directory "$labDir"
   python3 scripts/follow-resilience-lab.py controller status --port 18874 --nonce-file "$nonceFile"
   ```

   Fixture actions: publish (phase running/approval/completed), thread (review-one,
   review-two or visible), republish, available(value), blackhole, stats, stop. Controller
   actions: status, home, sleep, wake, idle, unidle, wifiOff, wifiOn, kill(pid),
   restorePermission, stop. Extra fields use --values with JSON. Its status returns
   package-scoped NMS records, foreground-service state, idle state and separate UIDs.

6. For wake chronology, publish running; use `probe resume` or `probe network` with
   --directory, --port 18874, --nonce-file, --agent-config and --agent-session. The
   observer captures pings/resubscriptions during the actual trigger, rather than
   starting its clock after the driver returns. It never retries a mutation on timeout.
   For Doze, Home→sleep→idle must report IDLE; pause fixture availability, record native
   Waiting/FGS, unidle→wake, resume availability and record native recovery. Do not infer
   network blocking from the loopback path.

7. Before each kill case, publish running, then review-one approval and observe two
   native child alerts. Save their mUpdateTimeMs and the normal PID. Home, kill that
   verified PID via the controller, observe absent PID/streams and stopped=false for a
   bounded window, then open the app visibly. Compare actual activity-card key tag=null
   (alerts also use id1), State PRIVATE/Full PUBLIC, new PID, steady peers and unchanged
   alert update times. For Full, run selectFull instead of prepareState using the same
   app-test component, let instrumentation finish, then open Main normally and seed
   fresh transitions. Privacy-selection instrumentation may remove notifications; it
   is excluded from the measured cycle, not mistaken for cold replay behavior.

## Cleanup and QA

The app cleanup method removes only recorded synthetic environment IDs/tokens/cursors,
stops Follow, restores prior follow preferences and cancels its notifications. Then
controller restorePermission preserves the recorded initial grant, and controller stop
restores battery updates, force-idle, WiFi and the prior nonsecure lockscreen setting;
whitelist and battery assertions passed. No PIN was installed. Stop the host fixture,
remove only owned forward/reverse ports, close the exact snapshot-helper session and
stop the owned emulator unit. These steps completed in the recorded run; both port
lists were empty. Permission was already granted initially and remained granted.

Focused JVM: app 54/core 14 passed, 0 skips. Preparation1/1(3.055s), Full selection1/1(3.159s),
cleanup1/1(0.128s), isolated controller1/1(918.336s), external host worker1/1(19min).
Controller and host duration include harness work and do not imply natural expiry.
A runner import error and an unsupported shell-quoting assumption were harness failures;
selected native observations and kill cases passed after correction. A prolonged
controlled outage also exercised the existing capped300s reconnect backoff: visible
wake recovered it; no timeout was inflated or product retry logic changed.

Measured runtime was based on221aeaa. Final branch includes main78f8137 shelves; the
notifications runtime is unchanged. Final build/lint/focused QA covers the integrated
source. System/lockscreen/grouped captures remain in the earlier system-lab directory;
this increment uses native system checks rather than presenting stills as proof of death.

## Specific legacy expiry follow-up

API24/25 expiry after the service stops still needs a real runtime reproduction.
AndroidX documents setTimeoutAfter as a no-op before API26 in its
[primary source](https://android.googlesource.com/platform/frameworks/support/+/0624f640fd3a47edfcf8a070f609d278fb5eb41b/core/core/src/main/java/androidx/core/app/NotificationCompat.java).
Current card/alert builders call it (Ot3Notifications.kt:115/174);
refreshNotificationPrivacy(:247) runs on privacy changes and retains absolute expiry;
onDestroy only explicitly cancels an active card (ThreadFollowService.kt:194).
The app's post-stop legacy behavior is not demonstrated by Robolectric builder tests.
No backport alarm or Kotlin awareness/expiry rule was introduced in this tranche.

Android references: [Doze](https://developer.android.com/training/monitoring-device-state/doze-standby),
[START_NOT_STICKY](https://developer.android.com/reference/android/app/Service#START_NOT_STICKY).
Source behavior references: local T3Mobile 680152e27 AgentNotifications.kt:168–204/280–290;
lib-t3-rust v0.3.5 stream.rs:6–8/95–125 and awareness.rs:264–315.
