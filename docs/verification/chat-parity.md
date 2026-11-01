# Chat parity verification

2026-11-01. PR https://github.com/avillagran/O3-Android/pull/105, branch `fix/outbox-collapse-composer`.

## Corrections

- CI's attachment test timed out at ParityWiringDeviceTest.kt:56, after the dispatch had happened: the attachment receipt no longer displayed the specific uncertain-send guidance. Restored that guidance; pending commands now also lock the editor, transformations and Send until explicit recovery. The instrumented regression checks both disabled controls and continues to verify byte-identical retry, no replay and clearing attachment storage after acceptance.
- CI's maintenance failure followed the addition of fixed-height local-cache controls above host-scoped content. Cache controls now open in a sheet, leaving the selected host and maintenance viewport reachable in landscape. The existing four orientation/theme combinations and two-host checks remain intact.
- Full-width compact editor over one control row. Model/permission/options, attachment, overflow, expansion and Send share that row. Capture, history, project drafts and load-older history use menus instead of extra persistent rows. Send has the Desktop circular accent treatment.
- Feedback/queue/approvals scroll independently above the editor. They cannot scroll Send or the typing surface out of the compact viewport.
- Desktop activity vocabulary, counts, command labels and grouped failures replace raw category/status concatenation. Groups expand in a bounded list; single tools inspect directly. Activity rows have no author/status heading, timestamp or list-wide gap.
- Only the final assistant response before the next user prompt gets the metadata row; commentary has the Desktop smaller gap. Copy and quote use compact accessible icons. Native plans render their content without first opening a fold; raw plan status is removed.
- Chat body/caption typography follows Desktop's default 12/10 logical-pixel scale while retaining Android font scaling. The thread header uses the server's human-readable label, with no environment ID fallback.
- User attachments sit inside the prompt bubble. Image thumbnails preserve Desktop's 220×160 frame, 4px inset, fit, border and radius; long press retains image copy. Markdown images retain their separate natural-width rendering policy.
- Code blocks use a compact header and flat code surface. Question choices use selectable rows with multi-select checks, without a redundant radio-control column.
- 29 hand-kept chat strings × 20 locales; identical key sets and positional formats verified. All 20 locales have activity/composer goldens.

## Comparison evidence

`/home/node/.cache/o3-android-artifacts/chat-parity/` contains 14 contact sheets named `contact-<scenario>.png`, the 28 raw Desktop and 28 raw Android captures, fixture JSON, source image, QML harness and logs. Scenarios: user, assistant, activity, tool, plan, approval, question, working, queue, attachments, code, composer, image and inspector. Each sheet contains 360 and 444 logical-width rows, Desktop left and Android right, with no scaling.

Desktop uses current real T3Panel message delegates and T3Composer/T3Approvals/T3Questions/T3Queue/T3ToolInspector, copied into scratch. Only the Wayland window wrapper is replaced by the prior FloatingWindow shim; backend transport is a fixture-only sink. A capture backdrop uses the chat background to avoid Qt losing alpha when an image makes grabToImage choose RGB. No Desktop source is edited. Android uses real production composables through Robolectric/native graphics at density 1, the same fixture and widths. Shared fixture: `app/src/test/resources/chat-parity.json`.

References audited: T3Panel, T3Composer, T3ActivityBlock, T3ActivityText, T3MessageImage, T3TimelineMinimap, T3ToolInspector; Desktop timeline-spacing, approval-state, questions and tool-inspector QML fixtures; historical desktop-parity-audit.md composer/transcript records. This is component rendering and fixture validation, not a claim of live-host or physical-phone acceptance.

## Remaining differences and limits

- Touch hit areas remain 48dp in the composer and question navigation; compact metadata/inspection icons are 32dp. Desktop hover-only actions are exposed as compact touch actions or long press. This deliberately changes vertical dimensions without stacking the main composer controls.
- Phone queue details and minimap/search navigation open in touch surfaces; Desktop uses an expanded queue and a narrow hover rail. Live queue actions and native eligibility are retained.
- Native Android and Qt font rasterization differ. Contact sheets retain those differences; no pixel-equivalence claim.
- Functional follow-up: [Chat actions](chat-actions.md) connects plan implementation/feedback/new-thread, Plan/Build, send-and-new, native message forks/merge-back and the full thread menu. A separate background delivery action stays absent: the audited Desktop composer and pinned typed API expose no matching operation. Live-host/device acceptance remains unverified.
- No live host latency, reconnect, process-death, upload, permission prompt or physical-device acceptance is claimed. The local device tool explicitly reports agent device access disabled. No emulator was opened or left running, no phone was touched, and full connectedDebugAndroidTest could not be run locally. Device tests compile; remote CI must confirm the two original failures.

## Local validation

Java 21 from `mise where java@temurin-21`; ANDROID_HOME=$HOME/.android-sdk. Every Gradle run uses `--max-workers=2`, with one Gradle run at a time.

- Core debug and release: 528 passed, 5 skipped each (533 cases).
- App: 566 passed, 1 skipped (567 cases).
- 220 golden PNGs, including 52 new chat captures; 20 locale captures.
- Final verification: full lint passed (app 0 errors / 111 warnings; core 0 errors / 2 warnings); importer 261×20 and hand-kept 29×20 checks passed. Required-bridge debug APK and debug Android-test APK assembled successfully. `final-verification.log` contains the complete Gradle result.
- APK: `/home/node/.cache/o3-android-artifacts/chat-parity/app-debug.apk`; SHA-256 `9b7c6588c537cfd47dcb1b44786e1c3f28d3d91b85133b6a947ed1da31c6fb7b`.
- Skips are five opt-in/live/import core cases per variant and one external-host app fixture. No test failures or errors.
