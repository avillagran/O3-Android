# Message lane parity

Source: ot3 `T3Panel.qml:1665-1742,1892-1909`. Own prompts now use a content-sized
surface bubble (80 dp minimum, 80% lane maximum, 12 dp padding, 16 dp radius), with
date and copy below and right-aligned. Surface content explicitly uses Omarchy `foreground` for prompts and code; Material’s
inherited surface content color had muted both. The author remains an accessible label rather
than a visible heading. Attribution uses the shared localized labels. Assistant prose
has no redundant author/time header; its date accompanies copy below, and these actions
are hidden during streaming. On touch the actions stay visible and retain 48 dp targets.

This increment does not claim automation folding, terminal-response-only actions,
rewind/fork, attachment previews, full history or full reader parity. Terminal response
eligibility should be exposed by the shared native view before moving ot3's QML scan
into Android. U18 historical merge/transport needs are recorded in LIB_REQUESTS.

Goldens: ReaderScreenshotTest `prompts` and `markdown`, Tokyo Night and Catppuccin Latte.
The prompt test checks short content width, right alignment, 80% cap and actions outside
the bubble. Validation: 193 JVM tests passed (including golden verification), lint/build passed,
and 6/6 focused instrumented tests passed on API 36.1. ThreadScreenTest asserts prompt
width/right edge, external action bounds and no assistant copy while streaming;
RichReaderDeviceTest covers native projections, tables, code, inspectors and no dispatch.
`before.*` and `after.*` use identical golden content/themes. `emulator-*.png` show the
full workspace with offline transport; `reading.mp4` (14.7 s encoded) shows scrolling
and jump-to-latest. No live-server or Xiaomi verification is claimed for this change.
