# Outbox recovery and compact project controls

## Causes and recovery

- The connected editor treated disconnected, stale and unconfirmed states as `loading`, disabling the text field. Its persisted attachment journal also locked the editor through `uncertainCommand`. A rejected command could be removed from Room while the attachment journal retained its command id; attachment-authority validation could prevent retrying it. There was no edit recovery, and discard immediately removed the receipt. Legacy state names outside `sending`/`unknown` could block admission without appearing in the unknown-command list.
- Database 4→5 changes existing receipt states to unknown without modifying command bytes or text. Startup recovery also normalizes older nonstandard states. Unknown states remain visible. No startup path replays a command.
- Editing is independent from sending. Offline/stale/uncertain/storage-error states retain an editable draft while send stays blocked. Explicit Edit warns that the original may already have arrived, durably merges its text, records a consumption marker, then retires its receipt. Repeated recovery cannot append the text again or replace newer typing. Uploaded descriptors remain removable chips. Discard requires confirmation. A corrupt journal can be explicitly backed up and rewritten without clearing Room drafts or receipts. Expanded-editor recovery actions remain visible.
- Unexpected dispatch exceptions leave the receipt unknown and actionable, instead of stranding a `sending` row. Retries preserve the stored command identity and bytes.

## Project tree

Project header rows contain no messages themselves: their counts must sum root rows in child sections. The projects presentation also excluded subagents. Android now retains them, nests by host-qualified `parentThreadId`, uses Lucide chevrons, and persists project, state-section and nested-row collapse keys in the existing HomePreferences store. Counts exclude children from project totals and show working/total on nested controls. Existing classic/focus/projects choices remain available; project headers apply to the Projects layout. The five-root limit no longer counts children as roots.

Desktop sources read: `ui/qml/T3ProjectSidebar.qml`, `ui/js/ProjectTree.js`, and `ui/qml/T3ThreadRow.qml` in the desktop checkout. Desktop files were read only.

## Composer presentation

Model/provider selection, history, shared drafts, dictation, image and camera controls use compact icons and existing localized accessibility labels. Every target remains 48dp. Permission and model-option controls already used Desktop Lucide icons. The reader retains remaining space; compact landscape capture stays in the overflow.

Desktop uses Lucide SVGs for these controls; its attachment preview is a Nerd Font `U+F06E` override. Android bundles that single glyph as `nerd_preview.ttf`, renamed Ot3 Preview Symbols, and uses it for the preview eye. Camera, mic and image SVGs are copied from Desktop's IconAssets table. Offline notices include FiraCode Nerd Font's OFL and Font Awesome's font license. Existing localized strings are reused across all 20 locales; no untranslated new keys were introduced.

## Validation

Java 21 (`mise where java@temurin-21`), `ANDROID_HOME=$HOME/.android-sdk`, at most two Gradle workers and one Gradle invocation at a time.

- SQL migration regression inserts old sending/pending/uncertain/unknown rows and checks text/bytes survive unchanged.
- Core recovery regressions verify no replay on startup, editable text with blocked sends, durable merge and idempotent recovery after new typing.
- Compose regressions verify editable uncertain/offline text, disabled sends, confirmed discard, independent project/nested collapse and persisted keys.
- New exact-pixel goldens: 1220×2712 and 2712×1220 at density 3.4, dark/light. Existing PhoneThread and Sidebar goldens were also updated and inspected.
- Core: 518 tests per debug/release variant, 513 passed and 5 skipped, no failures. App: 544 tests, 543 passed and 1 skipped, no failures. Roborazzi: 148 goldens pass (30 updated, 4 new). Lint: zero errors (app 109 warnings, core 2). Strings: 261 × 20; notices: 127. Required-bridge debug APK passes. Final command completion and APK paths are recorded in the artifact report.

Instrumented acceptance is **not verified**: T3 `device_list` reports agent device access disabled. No physical phone was inspected or modified. No emulator was started, so none needed stopping. Android-test APK compilation is checked separately; it is not a substitute for `connectedDebugAndroidTest`. Real Xiaomi/OEM and migration acceptance remains pending.
