# Reader tool inspector

2026-10-27. The real timeline opens one selected inspector in a reader-owned
window, scoped to the selected thread. Recycling, scrolling or replacing its
source row cannot dispose that window or start another read. Closing returns to
the unchanged timeline. Rotation saves the selected reference and mode; native
content may be read again after Activity recreation, without another thread stream.

## Reference and contract

Desktop: `omarchy-t3-code-ref/ui/qml/T3ToolInspector.qml:28,87–165` and its parent
popup placement. Details / Metadata / Raw item, native status/exit code, explicit
loading/error/unavailable/empty/omitted states, selectable bounded sections,
truncation notice, retry and copy follow that body. The phone window wraps short
content, caps height at90% and width at640dp, uses the theme's50% scrim, existing
OT3 buttons/icons and caption typography. Controls keep48dp touch targets.

The pinned035 AAR source is `d4bf6054b4480efdcd8a436cb66cb7f2e23664b1`.
`src/domain/review.rs:66–69` declares the fields; `src/tool_inspector.rs:320–325`
returns them. Kotlin renders these fields without reproducing classification,
request validation or protocol rules. All static labels come from the AAR catalog
in20 locales. Native section/status labels remain as returned by the projection.

One reference owns one cancellable read. A defensive reference copy and a
post-read cancellation check reject non-cooperative stale completion. Tabs reuse
the result. Retry retains the previous body until its replacement arrives;
closing/dismissing cancels this read, not the shared thread subscription.

## Verification and evidence

Ten focused Robolectric checks cover modes/copy, availability and truncation,
error/retry, stale completion, Spanish labels, explicit grouped selection,
retained retry body, a136dp timeline viewport, short-window density and source-row
replacement while the inspector remains open. App lint and both APK builds pass.

Six native-graphics JVM goldens under
`app/src/test/snapshots/ToolInspectorScreenshotTest/` cover Details, Raw and the
actual dialog in Tokyo Night and Catppuccin Latte. These are representative
projection fixtures, not live-server or physical-device evidence.

Run the behavioral class with `:app:testDebugUnitTest --tests '*ToolInspectorSurfaceTest'`.
Record goldens separately with `:app:recordRoborazziDebug --tests '*ToolInspectorScreenshotTest'`;
combining task filters can select only the last filter.

`ToolInspectorDeviceTest` uses the actual graph, view model, session and Rust035
scope/request/parse over a deterministic transport. It covers portrait/landscape,
modes, retry, close, one shared thread stream and no dispatch. Device acceptance
for the reader-owned window is still pending. Capture checks reject a foreign
active window instead of accepting Compose semantics behind a system dialog.

## Earlier failures and remaining differences

`device-verification.json` records the previous inline prototype's attempts.
One initial assertion used exact rather than substring matching. A real retry
collapse was fixed. API36.1 images were rejected because System UI ANR dialogs
covered the app; a clipboard case also failed. No final emulator video from those
attempts is certified. Private clones were removed and original AVD hashes kept.

API34 CI subsequently exposed close failure in the cramped inline landscape
layout. The inspector now belongs to the reader outside the lazy row, matching
the spec's sheet/popup ownership. Its exact prior touch/layout cause was not
proven; the new device test also requires return to the specific Details entry.

Follow live output remains U36 / [lib issue22](https://github.com/avillagran/lib-t3-rust/issues/22):
the typed subscription exists but the common bounded ANSI/VT/overwrite reducer is
missing. No Kotlin parser or fabricated polling was added. Header terminal/
reasoning icon, native-label translation, exact desktop popup motion and wider
inspector-pane placement remain differences. This increment does not close UI-2.
