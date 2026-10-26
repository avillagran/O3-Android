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
