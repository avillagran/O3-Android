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
- Explicit Refresh prices (`server.refreshUsageRates`), detailed pricing states.
- ot3 compact k/M/B token format, provider icons and middle-elided model labels.
  Current totals use full localized integers; model names wrap.
- Emulator portrait/landscape interaction and video, live diagnostics scope and
  Xiaomi acceptance. No physical Usage validation is claimed.

Local result: app JVM 200/200; core JVM 283 passed, 5 opt-in skipped;
`verifyRoborazziDebug`, `lintDebug`, `assembleDebug` passed with required host JNI
and bridge SDK. Goldens for both themes were visually inspected. No emulator or
phone was operated for this increment.
