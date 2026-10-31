# P15 terminal: blocked native integration

Base: `feat/adopt-lib-036`. P15 is **Blocked**, not implemented terminal parity.

## Verified contract inventory

Pinned AAR `v0.3.6`, source `86819eb442ae4a8635cb21206f2195343c75b094`,
SHA-256 `5e44c1ef846f27b7f131ca3bbacb771f21ce70028f061014904064e4caf9e21d`.
`javap` of its classes.jar confirms `T3Api.Terminal.open`, `write`, `resize`,
`attach` and generic `T3Client.subscribeFn` are present. These RPC bindings are
**not missing**. The native stream owns validation and subscription cancellation.

**terminal::context binding is absent in AAR 0.3.6.**
**terminal::Output binding is absent in AAR 0.3.6.**

Those helpers exist in the pinned Rust `src/terminal.rs` but are absent from
Android's T3Api, execute operations and domain registry. Context resolves the
thread worktree, project root, provider instance and fallback scratch directory;
Output replaces snapshots, deduplicates sequence replay and observes exit/error.
Do not port these shared rules to Kotlin to work around the missing bindings.

`terminal.attach` requires `terminal:operate` even for observation and can start
a process. There is no separate read-only attach contract in this release.
A token lacking that permission therefore cannot receive the requested live
read-only terminal. Do not reuse a Desktop credential or probe access with attach.

Sources inspected read-only:
- Desktop `docs/SHARED_DRAFTS_HOST.md`, native terminal contracts (lines 99–115).
- Desktop `src/terminal.rs`, run_frontend uses both native helpers.
- Audit `/home/node/.cache/o3-android-artifacts/desktop-parity-audit.md`, P15 row.
- Pinned library Android `T3Api.kt`, `T3Client.kt`; `src/terminal.rs`, `src/ffi*`,
  `src/domain*` and AAR classes.jar.

## Delivered prerequisite

Thread menu opens a full-screen host/thread-scoped unavailable page. It cancels
permission reads and discards state when the connection or target changes. Close
and Back return to the thread. No attach, command execution, local shell, input,
credential forwarding or automatic retry is offered. Bottom navigation is hidden.
Five hand-kept strings in all 20 locales identify the binding and read-only gaps.
Core adapter validates current auth before typed write/resize and does not retry
unknown delivery; it is not wired to an executable UI while prerequisites lack.

## Verification

Java 21 (`mise where java@temurin-21`), `ANDROID_HOME=$HOME/.android-sdk`,
Gradle `--max-workers=2`. Logs: `~/.cache/o3-android-artifacts/p15/`.
The first app unit run failed existing MessageImagesTest visibility of Image 199;
an unchanged full repeat passed; the final full repeat failed the same test again.
No cause or fix is claimed for that failure. Final app result: 512 passed,
1 failed, 1 skipped (514 total); the earlier green repeat had 513 passed and
1 skipped. Both new app tests passed in every run. Core debug/release each:
477 passed, 5 skipped (482 total), including five new terminal tests.

The native contract regression runs against the actual host JNI and confirms
RPC presence and absent domain/execute terminal helper exports. Permission tests
cover unauthenticated/read-only tokens, unchanged UTF-8 payload forwarding,
resize target preservation and exactly one attempt on unknown delivery. These
are adapter tests, not PTY/ANSI/UTF-8 rendering acceptance.

API 34 emulator, explicit serial `emulator-5580`, 40-minute timeout:
core 8 passed / 2 skipped; app 107 passed / 9 skipped; no failures.
App XML duration 238.136 seconds; full Gradle run 4m19s. New blocked-surface
device test passed. Console totals double-count skipped tests; counts here use
XML testcase entries. Emulator stopped after testing. No phone/all-hosts access.

Standalone `verifyRoborazziDebug` with all `*ScreenshotTest` classes:
144 unchanged goldens passed, no baseline updates. Lint: 0 errors (core
2 warnings; app 102 warnings and 1 hint). Required-Bridge-SDK debug APK passed.
`tools/import-ot3-strings --check`: 261 × 20 passed; terminal XML parity:
5 hand-kept keys × 20 locales passed. `git diff --check` passed.
The screenshot-only run is separate from the failing full app unit run above.

## Pending acceptance

Remote PTY renderer, ANSI, UTF-8 rendering, bounded scrollback, selection/copy/paste,
soft keyboard, key row, layout/IME resize, reconnect, exit state, run-code confirmation
and native cwd remain unimplemented/unverified. Run-code actions remain disabled;
a successful write response must never be treated as command success. A close
must detach its subscription without killing the remote process.

To unblock: expose native terminal context and output helpers through a versioned
Android release, and add a server-authorized observation-only terminal stream for
read-only tokens. Then wire the renderer/controller and command confirmation using
the same authenticated host connection and stable per-thread terminal IDs. Do not
restart an exited shell on reconnect; never retry uncertain command input.
