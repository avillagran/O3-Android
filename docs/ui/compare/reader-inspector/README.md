# Tool inspector reader increment

2026-10-27. Reader implementation and six focused JVM checks pass; device verification remains **pending**.
No captures or server/physical-device parity claim accompany this change.

## Reference and contract

Desktop reference: `omarchy-t3-code-ref/ui/qml/T3ToolInspector.qml:28,87-108,118-164`.
The Android inline inspector follows its title, failure/status/exit-code display,
Details / Metadata / Raw item modes, retry, explicit empty/omitted/unavailable
states, selectable bounded section bodies, copy and truncation notice. Android
uses touch targets of at least 48dp and the existing theme, spinner and copy control.

The checked-in AAR properties declare lib-t3-rust 0.3.5, source
`d4bf6054b4480efdcd8a436cb66cb7f2e23664b1`. At that commit,
`src/domain/review.rs:66-69` declares all displayed fields and
`src/tool_inspector.rs:320-325` supplies them. No native response parsing,
classification or protocol decisions are reproduced in Kotlin.

`ToolInspectorSurface` keys its state/effect by the full reference, cancels the
previous effect on replacement/disposal, copies the reference before reading,
and checks coroutine cancellation after the reader returns. Retry reuses the
existing `onInspect` callback. Tabs do not request new data. No polling is added.

## Verification

`ToolInspectorSurfaceTest` covers native section/metadata/raw presentation and
copy availability without extra reads; error then retry; omitted versus empty
output; unavailable/disconnected states; and a deliberately non-cooperative old
read returning after reference replacement without overwriting the new result.
These are representative UI inputs, not evidence of a live T3 server response.

Six focused Robolectric cases and app lint pass. The grouped-activity case verifies
that expansion does not read every tool: only an explicitly selected entry loads.
The Spanish case verifies labels from the shared catalog. Next capture actual component rendering on the root-coordinated emulator in Tokyo
and Latte, including a long output section, selected Raw mode, failure/exit code,
and empty/omitted output. Add JNI-derived representative views and verify scroll
bounds/copy and reference switching before claiming runtime validation.

## Remaining differences

Follow live output (`T3ToolInspector.qml:109-117`) remains pending; this increment
adds no command-output subscription or fabricated polling. Android keeps the
existing inline activity placement rather than introducing a desktop popup or
its separate close button. Labels are imported from the pinned AAR catalog into all20 supported locales.
Native section labels remain as supplied by the projection; they are not
reclassified by Kotlin. The inline inspector now has an explicit close control.

## JVM rendering evidence

Four golden images under `app/src/test/snapshots/ToolInspectorScreenshotTest/`
show Details and Raw in Tokyo Night and Catppuccin Latte, using the actual reader
surface with representative projection fields. These are Robolectric native
graphics captures, not emulator, live-server or physical-device evidence.
Details/Tokyo and Raw/Latte were visually inspected after recording.

Reproduce: `:app:recordRoborazziDebug --tests '*ToolInspectorScreenshotTest'`.
The six behavioral checks run separately with
`:app:testDebugUnitTest --tests '*ToolInspectorSurfaceTest'`; combining both
task filters in one invocation can select only the last filter.

Remaining reference gaps include the header terminal/reasoning icon, translation
of native section/status labels, live output subscription and desktop modal
placement. This increment improves actual reader behavior without closing UI-2.
