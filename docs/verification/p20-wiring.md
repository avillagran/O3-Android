# P20: first wiring pass

Historical first pass. Current wiring/results: [second pass](p20-wiring-2.md);
current package checklist: [Desktop parity status](desktop-parity-status.md).

Base: origin/main ce490a9. Checkout: /home/node/Work/ot3-android-all-hosts.
Desktop reference: /home/node/Work/omarchy-t3-code feat/o3-brand (read only).
No phone or /home/node/Work/ot3-android working-tree changes.

## Reachable

- Preferences search/root: General, Appearance, Notifications, Usage, Projects,
  Connections and Source control. Archive/Licenses and undelivered modules remain hidden.
- General: explicit all-host or one-host scopes and native project selection;
  native descriptors, partial errors and refresh; device quick replies, queue/steer,
  timestamps and math setting. Scope changes discard old page state.
- Projects: host/project picker and native ProjectConfiguration page; entry from
  project and thread menus; new chat on the selected thread's branch.
- Thread menu: retained P05 operation owner and unknown-command recovery,
  authoritative title-regeneration gating.
- Composer: P06 touch editor, undo/redo, quick replies, selected content URI upload,
  removable attachment states, explicit interrupted-send retry/discard, opposite
  delivery once. Upload uses the supervisor's authenticated JNI handle. Uploads
  serialize staging, enforce the existing 50 MiB bound and reject changed connection.
- Composer descriptors and exact pending message bytes persist atomically under
  noBackupFilesDir, qualified by environment/thread and credential authority.
  Outbox precedes dispatch. Repeated sends reuse command/message IDs. No automatic
  resend after reopening. Interrupted uploads restore as failed. Storage errors
  disable sends; a changed credential authority cannot reuse attachments.
- Native code highlighting/copy and block/inline math in the timeline; unfinished
  visual blocks remain source. Unsupported visual previews are hidden.
- Thread overflow → Git review: working-tree/branch-range native readers,
  host-bound ref pages, file reads and shared split/wrap/context preferences.
  Timeline diffs use enhanced review controls with the same preferences.
- Connections: environment manager, enrollment/pairing and full SDK identity.

- Providers (merged PR85): Settings contribution and typed route, explicit host selection,
  controller scoped to selected host/connected transport, ephemeral external auth URL callback.
  No project scope or all-host mutation is offered; P07 binding limitations still apply.
- Header (merged PR79): P02 brand glyph/sidebar style/master animations and effective
  reduced-motion policy now reach the production Home header in both orientations.

## Hidden / incomplete

- Transcript: PR88 remains a separate contribution; this pass does not integrate it.
- Add project / command palette: no delivered page; remove Home's dead entry points.
- Archive, licenses, shortcuts, integrations, storage, memory: no merged page module.
- P05 named groups / physical grouping: native sidebar adapter integration remains
  pending; no local controls that save an unconsumed presentation preference.
- P11 commit/push/pull/scripts and checkpoint rollback: remain unexposed. This pass
  does not implement the required global checkout/composer admission guard, durable
  execution reconciliation or atomic attachment-aware checkpoint draft recovery.
  These are remaining P20 implementation work, not claimed SDK blockers.
- Mermaid: no offline engine. QML/HTML frames: no delivered SDK document runtime.
  No remote renderer, remote script or Kotlin protocol replacement.
- Composer history, shared project drafts, completion transforms and plan actions:
  shared contracts U33/shared draft/recovery are unavailable. Background/SendAndNew
  remain hidden; their host/creation receipt integration is not implemented here.
- Legacy local plan toggle is hidden until its interaction-mode consumer exists.
- Live host uploads, actual multi-host mutations and checkpoints require further
  acceptance. Fixture/device results do not prove live host behavior.

## Initial wiring verification (before merged contributions)

Current integration/repair results: [P20 instrumented repair](p20-instrumented-repair.md).

- Core: 245 passed, 176 skipped, zero failures (421 total).
- App: 441 passed, 16 skipped, zero failures (457 total).
- Roborazzi: 112 reference PNGs verified; two Markdown references updated for the delivered code/math renderer.
- Lint: zero errors (app 94 warnings/one hint, core two warnings).
- Strings: generated catalog 261 × 20 locales; the new math label is registered in all 20 locales.
- Required-bridge debug APK and instrumentation APK assemble successfully.
- JVM skips retain existing unavailable host-JNI/opt-in coverage. No live-host result is inferred from them.
- API 34 emulator: 91 passed, nine skipped, zero failures (100 total), 236.406 seconds. Full suite completed under the 35-minute outer limit and 120-second per-test limit.

Commands use Java 21 from `mise where java@temurin-21`,
`ANDROID_HOME=$HOME/.android-sdk`, and Gradle `--max-workers=2`.

```sh
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug lint :app:assembleDebug :app:assembleDebugAndroidTest -Pot3a.requireBridgeSdk=true --max-workers=2
tools/import-ot3-strings --check
scripts/emulator.sh start --headless
scripts/emulator-ready.sh emulator-5580 --timeout 30
scripts/emulator.sh install app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
timeout 2100 adb -s emulator-5580 shell am instrument -w -r -e timeout_msec 120000 dev.avillagran.ot3android.test/androidx.test.runner.AndroidJUnitRunner
```

The device matrix exercises the durable composer in portrait/landscape and both
bundled light/dark themes, committed IME text, undo/redo, quick replies, expanded
editing and attachment-command recovery after closing/reopening the Activity.
Recovery asserts no automatic send, identical command JSON on explicit retry, and
cleared private storage after acceptance. Existing cache/offline, environment,
reader, queue, request and navigation suites are retained. This is not a physical
phone test, a full OS process-kill test, or a live host upload test.

APK: `/home/node/.cache/o3-android-artifacts/p20/o3-android-p20-debug.apk`.
SHA-256: `09708f5b3a93234c2c0b36389c74bc4a762416c09cb14b99fdfb8f4cdd8b93ed`.
Logs: `/home/node/.cache/o3-android-artifacts/p20/`.

Harness corrections preserve assertions: PR-return and offline-cache tests use
Compose's v2 rule; the workspace inset check waits for nonzero layout, and the
offline pixel baseline is captured after committing/hiding the IME so the compared
viewports have the same keyboard state. Per-test execution is bounded to 120 seconds
and the entire invocation to 2,100 seconds.
