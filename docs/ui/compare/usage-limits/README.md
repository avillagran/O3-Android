# Usage limits increment

Settings → Usage now has a Limits tab. Its single selected-environment subscription
uses typed `server.subscribeServerConfig {usageLimitSources:true}`. Every event is
folded by Rust `quotaSources.apply`; Kotlin renders only the public provider/account
projection. Switching environment or tab, disconnecting or leaving the page closes
the subscription. Closed streams retry with bounded backoff. Provider probes share
an application-scoped five-minute gate per environment, including failed attempts.

Reference: ot3 `ui/qml/QuotaStrip.qml`, `src/quota_sources.rs` and `src/quotas.rs`;
Android UI_SPEC §4.10. One bar per native window, used percentage explicitly labelled,
and absolute device-local reset/check times. Bars are static. No pooling, remaining
percentage, pace, warnings or provider-name mapping is recreated in Kotlin. Those
common projections await U26. Compact Home/thread strips and the tab fade remain
pending; this increment is the full-page account/window list, not final quota parity.

The debug fixture includes a Codex provider and a separate team Claude account.
`limits.events.json` contains synthetic server events. `limits.view.json` was emitted
by the pinned 0.3.5 host C ABI's `quotaSources.apply`, for screenshot isolation from
Robolectric classloader-native-library restrictions. Core JNI tests exercise the
actual typed WebSocket subscription, cancellation and reducer sanitization.

All emulator images/video here use synthetic accounts and real screens/ViewModels/
Room/native projections. They do not prove productive provider data, NODE discovery
or physical acceptance. The physical install is documented separately after success.

## Verification and delivery

- Final code f240491: app208/core286 JVM passed,5 core opt-ins skipped; lint,
  assembly and screenshot verification passed. API36.1 UsageDeviceTest3/3 passed,
  including rendered reset/check dates, rotation, tabs, stream cleanup and pacing.
- `portrait.png` and `landscape.png`: real emulator, Catppuccin Latte, synthetic
  provider/hub stream. `latte-before-date-fix.png` records the device-only omission
  caught visually and corrected. Tokyo Night and Latte JVM goldens are in
  app/src/test/snapshots/UsageScreenshotTest.
- `tabs-rotation.mp4`:67.6s encoded coverage from74.7s recording; mostly static
  limits plus portrait→landscape rotation. Decoded contact sheet inspected. It
  does not establish tab-fade parity or live provider updates.
- Xiaomi install-r of cec976c returned Success with matching signing certificate,
  preserving data. The date correction f240491 could not install: WiFi ADB went
  offline and the final inventory no longer listed the phone. No physical limits
  acceptance is claimed. Emulator stopped and helper closed.
- Final APK SHA256: efe6a66f64c50c2d511abbdc19a4b923752eefa79ffcabe42be0b0368d420d3a.
  Bridge SDK remains production f7d93a9 and T3 remains0.3.5; the experimental
  delegated-catalog candidate is not included.

## Live NODE subscription

Read-only host C ABI probe against the existing local NODE T3 received `snapshot`
and `usageLimitSourcesUpdated`. The same pinned0.3.5 `quotaSources.apply` produced
10 public providers and4 readable windows; this server returned0 hub sources.
See `live-node-sanitized.json`. A temporary two-minute orchestration:read session
was revoked in cleanup; no provider refresh, config change or provider run occurred.
The report contains only counts and event types, no account identifiers or credentials.
This establishes real-server stream/reducer compatibility. It does not establish
Android socket behavior, productive bridge discovery or physical Limits acceptance.

## Reproducible screenshot timezone

CI run40389243280 exposed a screenshot-only difference: the runner rendered reset
and check timestamps in UTC, while the checked-in images use America/Santiago.
UsageScreenshotTest now fixes and restores its default timezone; production still
formats dates in the device timezone. All three Usage screenshot tests (six theme
images) pass with the process launched as `TZ=UTC`, without replacing goldens.
