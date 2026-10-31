# P20 second wiring pass

Base: Android origin/main 09454b0; branch feat/parity-wiring-2.
Checkout: `/home/node/Work/ot3-android-all-hosts`.
Desktop `feat/o3-brand` 55c0ce3 was read only. Neither the original Android working tree
nor a physical phone was used. Package checklist: [desktop-parity-status.md](desktop-parity-status.md).

## Reachable now

- Settings: Storage (report/manual cleanup and native environment/explicit-project
  policies), Archive, Scheduled tasks, Import agent sessions and Processes/history.
  Host choice is explicit. Connection/credential/project/page replacement disposes
  controllers; no previous host's transport is reused. All controllers share the
  app-private maintenance receipt owner. Unsupported mutations are omitted by
  advertised registry and authenticated scope gates.
- Existing P18 Usage/QuotaLimits/Notifications destinations already consume the
  merged periods, refresh gate, notification modes/channel lifecycle; kept connected.
- P12 comment/review/action callbacks now reach native writes. A private unresolved
  flag is persisted before writing, shared across route re-entry and process restart;
  successful typed acknowledgement clears it. Refresh is not a write retry.
- Thread overflow → Git & scripts: native status/scripts, confirmed commit/push/pull/
  create-PR/composite operations, and existing native terminal script delivery.
  Stacked Git uses the exact supervisor's authenticated JNI client and stream.
  Journal records survive navigation, cancellation and process restart. Script
  delivery remains pending until the user verifies execution and reconciles it.
- Git admission and MessageSender's durable dispatcher share exclusion. Pending
  outbox entries prevent Git admission; unresolved Git/rollback records prevent
  command dispatch. Active effects serialize through one process-wide gate; unresolved
  records exclude commands for their host, including other threads sharing a checkout.
  Narrower per-checkout scheduling is follow-up work. A command
  created concurrently can remain explicitly pending in outbox; it is never sent
  through the exclusion or replayed automatically.
- Native Git scope validation receives required projectId/cwd copied from the
  authoritative shell. Previously captured identifiers are preserved so a changed
  checkout is rejected. The same adapter repairs first-pass working review/ref reads.
- Text-only user checkpoint actions use native canPreview, reference, safety,
  confirmation and correlated read-back. File restoration additionally needs a host
  worktree plus explicit destructive acknowledgement. Dispatch acknowledgement does
  not mean rewind completion. The native provider capability is read directly from
  server config, not the reduced model catalog that omits it; unknown is false.
- Checkpoint draft text and its consumed recovery ID commit in one SQLite transaction.
  Native appendPrompt preserves text typed since preview. Reopening cannot append
  the recovered prompt twice. Reusing a consumed ID also preserves newer editor text
  that has not reached the debounce save yet. A crash after draft commit but before journal completion
  is reconciled using the transaction's marker, without another rollback dispatch.
  Refresh reads a fresh bounded native projection without resending a command. New attachment imports are blocked during confirmation
  or an unresolved rollback. Text-only recovery can finish locally while offline.
- Named group controls in thread/project sheets persist environment-qualified
  assignments and feed the existing sidebar metadata adapter immediately. Thread
  assignment wins over project assignment. Controls live in a scrollable dialog;
  unsupported project mutations and pin reordering are hidden.
- P10 inspector receives its environment/thread owner. Late item reads are dropped
  after the opening supervisor/connection changes; local tabs/reload retain native
  bounded details/metadata/raw output and truncation notices.

## Hidden / remaining

This is a Partial package, not complete Desktop parity. The checklist gives the
repository and required change for every package, including these P20 follow-ups:

- Checkpoints containing attachments or structured context: atomic byte/descriptor + draft recovery and
  availability preflight are not implemented. Such source messages have no rewind
  action. The fresh native recovery message is checked again before dispatch.
  No attachment recovery is inferred from text recovery tests.
- Physical repository/path/checkout grouping, P12 stack and thread-command/receipt
  UI, P06 real creation recipe for new scheduled tasks and missing-project imports.
- PR authenticated file diff/native page merge (U31), live tool output (U36), exact
  pooled quotas (U26), Memory/vault/host-sync/compaction/build-cleanup host contracts.
- Add project/palette, licenses/About, editable shortcuts, browser/document/device,
  terminal reader/capture, Mermaid/QML/HTML runtimes and unavailable composer actions.
- PR unknown receipts intentionally remain blocked until a verified reconciliation
  flow is delivered; opening a fresh route does not clear the flag.
- No actual authenticated host mutation, provider installation, all-host bridge admin,
  arbitrary attachment checkpoint recovery, whole-OS-process-kill, TalkBack/RTL or
  OEM/Doze notification acceptance is claimed.

## Verification

Java 21: `mise where java@temurin-21`; `ANDROID_HOME=$HOME/.android-sdk`;
all Gradle invocations use `--max-workers=2`.

- Core debug: 290 passed, 176 skipped, zero failures/errors (466 total).
- Core release: 290 passed, 176 skipped, zero failures/errors (466 total).
- App debug: 473 passed, 16 skipped, zero failures/errors (489 total).
- Roborazzi: 132 unchanged references, zero additions/changes/recordings.
- Lint: zero errors; app 94 warnings/one hint, core two warnings.
- `tools/import-ot3-strings --check`: 261 strings × 20 locales, passed.
- `:app:assembleDebug -Pot3a.requireBridgeSdk=true` and instrumentation APK: passed.
- API 34 x86_64 emulator: **97 passed, nine skipped, zero failures** (106 cases),
  **284.810 seconds** on the final APK. Runner bounded to 720 seconds, each test to
  120 seconds. All device runs remained within the requested 40-minute budget.
- Three new integration tests cover the four light/dark × portrait/landscape
  combinations: five maintenance entries, explicit two-host replacement/offline,
  eight native fixture pulls with exact host ownership, and four native text rewinds
  with offline draft recovery, re-entry and no duplicate dispatch. Existing inspector,
  PR, usage, notification, cache, image, composer and navigation suites also passed.
- The emulator was stopped after verification. No live-host result is inferred from
  fixture mutation acknowledgements; file-restoring/attachment checkpoint acceptance
  and real host mutations remain outstanding.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:verifyRoborazziDebug lint \
  :app:assembleDebug :app:assembleDebugAndroidTest \
  -Pot3a.requireBridgeSdk=true --max-workers=2
tools/import-ot3-strings --check
OT3A_EMU_AVD=ot3a-p20-wiring2 OT3A_EMU_PORT=5582 \
  OT3A_EMU_STATE_DIR=$HOME/.cache/ot3a-p20-wiring2 scripts/emulator.sh start --headless
scripts/emulator-ready.sh emulator-5582 --timeout 30
timeout 720 adb -s emulator-5582 shell am instrument -w -r -e timeout_msec 120000 \
  dev.avillagran.ot3android.test/androidx.test.runner.AndroidJUnitRunner
```

The existing `ot3a` AVD reported an occupied instance; a separate temporary AVD
and script state directory were used. Every ADB command specified emulator-5582.
Device invocation(s) were bounded, with no physical device selected.

APK: `/home/node/.cache/o3-android-artifacts/p20-wiring-2/o3-android-p20-wiring-2-debug.apk`.
SHA-256: `3f052454537015da1bd06f19fe45a963f58b26bc704038e11f0600be44404e29`.
Evidence: `/home/node/.cache/o3-android-artifacts/p20-wiring-2/`.
