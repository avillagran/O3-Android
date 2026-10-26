# Usage increment — 2026-10-26

Settings → Usage now opens the native-backed cost/token page instead of a placeholder.
The environment selector, Day/Week/Month/Quarter, four totals, daily bars and model
rows follow `omarchy-t3-code-ref/ui/qml/T3UsagePage.qml:29-76` (reference `7b5b1de`).
A tap selects a day's value; horizontal scrolling keeps longer periods readable.
This is the Usage half of UI_SPEC §4.10.3, not completion of UI-6.

## Data and ownership

`UsageReader` calls `usageSummary.request`, `server.getUsageSummary`, then
`usageSummary.aggregate` from pinned lib 0.3.5. Kotlin supplies local date/timezone,
formats numbers for the current locale and scales the chart; Rust owns windows,
totals, model ordering and missing-day buckets. The page uses the existing
ConnectionManager transport. It opens no additional shell subscription.

A period/environment change cancels the previous request. Disconnect clears its
result; reconnection reads through the new Connection epoch. Leaving the screen
cancels collection. The actual navigation entry supplies SavedStateHandle for the
selected environment and period. Refresh re-reads the selected summary.

## Evidence

- `app/src/test/resources/usage/summary.input.json` is a synthetic server summary.
  `summary.view.json` was exported through the actual 0.3.5 host JNI, and the
  contract test verifies it against that library.
- Goldens: `app/src/test/snapshots/UsageScreenshotTest/summary.tokyo-night.png` and
  `summary.catppuccin-latte.png`. These are Robolectric API 36 renders, **not**
  emulator screenshots or live NODE/VQ readings.
- Focused tests cover native request/aggregation, request cancellation, offline
  clearing, reconnect, selection restoration, refresh and visible controls.

## Remaining differences

- Limits tab, pooled quotas and accounts (U26), pull-to-refresh and tab transition.
- Detailed pricing states beyond the native unavailable/unpriced presentation.
- ot3 compact k/M/B token format, provider icons and middle-elided model labels.
  Current totals use full localized integers; model names wrap.
- Live diagnostics scope and Xiaomi acceptance. No physical Usage validation is claimed.

Local result: app JVM 200/200; core JVM 283 passed, 5 opt-in skipped;
`verifyRoborazziDebug`, `lintDebug`, `assembleDebug` passed with required host JNI
and bridge SDK. Goldens for both themes were visually inspected. This first
validation was JVM-only; the emulator follow-up is recorded below.


## API 36.1 emulator follow-up

`UsageDeviceTest.settingsPeriodRotationRefreshAndBack` uses the real Settings
route, UsageViewModel, existing ConnectionManager and JNI with the debug server.
It opens Usage, verifies the week total, switches to Day, rotates to landscape,
checks preserved selection, refreshes, scrolls to Models and returns to Settings.
No dispatch command is sent. The server filters its fixed buckets by the native
request window. It is **not** a productive NODE or VQ reading.

Captures inspected:
- [Tokyo Night portrait](emulator-tokyo-night.png)
- [Latte portrait](emulator-latte.png)
- [Landscape before](emulator-landscape-before.png) and
  [landscape after](emulator-landscape.png): environment and periods share a row;
  four KPI tiles use one row. Lower chart/model content remains scrollable.
- [Controls video, 19.18 s](usage-controls.mp4): Tokens, day selection, Day period
  and Refresh. The recorded portrait layout is unchanged by the landscape fix.
  The video was decoded and its selected-day frame inspected.

Reproduce with `scripts/emulator.sh` AVD `ot3a361` and explicit serial
`emulator-5580`, then `:app:connectedDebugAndroidTest` filtered to
`dev.avillagran.ot3android.ui.usage.UsageDeviceTest`. The debug-only
WorkspaceFixtureActivity supplies deterministic diagnostics; it has no launcher
entry. No Xiaomi operation is part of this evidence.

Follow-up result: app JVM 200/200, lint and goldens passed after the landscape
change; API36.1 instrumentation passed 1/1. A subsequent cold-emulator attempt
could not resolve the fixture Activity; reinstalling the built debug APK and
rerunning the same final test passed, including the Models scroll assertion.
The initial failure is not treated as application acceptance evidence.


## Price refresh increment

“Refresh prices” now follows `T3UsagePage.qml:75` and desktop
`src/usage_summary.rs:21-37`: explicitly call `server.refreshUsageRates`, then
re-read the selected summary. There is no Android price table or cost arithmetic.
The action is consumed once, so reconnect/resume cannot replay it; changing
scope cancels the operation and cannot publish the old environment's result.

Validation: app JVM 203/203, lint and goldens passed; API36.1 instrumented 2/2,
including one rates call followed by a second summary read and no dispatch.
[Emulator before refresh](emulator-prices.png) shows the unavailable state.
Dark/light `unpriced` goldens cover the action and native unpriced count.
The server is still synthetic; no live pricing-table refresh or physical proof.

Cold-boot correction: instrumented tests were started while the asynchronous
emulator-start command was still running. After awaiting that exact handle,
the same APK/tests passed without reinstalling. Future reproductions must await
`scripts/emulator.sh start` completion before invoking Gradle.

## Tab entrance — 2026-10-26

Verified the actual reference at `/tmp/t3code`, commit
`680152e27b2a961686389ca69a1557cbb2c55f08`:
`apps/mobile/src/features/usage/UsageRouteScreen.tsx:261-264` keys the body by tab
and uses `FadeIn.duration(160).reduceMotion(ReduceMotion.System)`. There is no
outgoing fade or cross-fade. The desktop reference
`ui/qml/T3UsagePage.qml:8-78` has a single usage popup and no tab transition.

Android now fades the incoming body over 160 ms using one shared presentation
opacity. Tab/environment controls stay stable; a single LazyColumn retains its
scroll state. No outgoing composition, extra summary read, or extra quota
subscription is created by the animation. Animations Off (including the system
reduced-motion policy) snaps to full opacity, including a preference change during
an entrance. Metric, period and environment selection remain outside the effect.

Validation: focused JVM motion/screen/view-model tests and lint passed; all six
existing UsageScreenshotTest Tokyo Night/Latte goldens pass unchanged. The clock
controlled test checks intermediate opacity, completion, tab restart, and turning
motion off during an entrance. Screen interaction checks metric and period
retention and no refresh on a round trip. This increment has JVM evidence only;
no new emulator recording or physical/live acceptance is claimed.
