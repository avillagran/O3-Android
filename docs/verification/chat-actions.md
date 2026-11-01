# Chat action parity

2026-11-01. PR https://github.com/avillagran/O3-Android/pull/107.
Base: `fix/outbox-collapse-composer`; implementation: `feat/chat-actions`.
No merge, terminal changes, phone access or changes to the other Android checkout.

## Delivered behavior

| Feature | Result |
| --- | --- |
| Plans | Ready-plan Implement, feedback/refine from the composer, Implement in new thread, existing Copy, and Android Share of the original Markdown including headings and trailing newline. Historical plans remain readable/exportable but cannot mutate a different ready plan. |
| Plan / Build | Provider-gated draft preference in the existing composer overflow; its label reflects the effective native mode. `/plan` and `/default` select the mode without sending those local commands to a provider. The preference persists in the private composer journal and shared-draft preferences. |
| Send and new | The current draft and uploaded descriptors become the new thread's first message through native `composer.newThread` and atomic `orchestration.launchThread.initialMessage`. Same root/branch or existing worktree is explicit in the native recipe. Navigation and draft clearing wait for acceptance. |
| Opposite delivery | Existing native typed send route retained. During an active run, the one-shot action swaps queue/steer without changing the saved default, including the pending-approval preference. |
| Queue | Existing Rust `queue.command` edit/reorder/steer, attachment/context preservation, drag and accessible up/down ordering retained. Queue edits use their separate editor and never overwrite the unsent draft. |
| Recall | Existing native `composer.transform` recall menu retained with captured editor revision and undo of recall back to the scratch draft. |
| Slash / references | Native prefix detection, host path/skill/command catalogs and exact-range transforms retained. The formerly filtered `/model` completion now clears its source range and opens the actual model options control. IME and stale-editor guards remain. |
| Fork / merge-back | Final assistant responses expose Fork only with native `forkRunId`. Merge-back consumes native `threadState.mergeBackTargetThreadId` and `mergeBackRunId`. Permissions, subagent, connection and busy/unknown state are checked again before native typed dispatch. Stable target identity survives explicit receipt recovery. |
| Thread menu | Chat overflow opens the same full `ThreadActionsSheet` route as Home: capability-gated lifecycle actions, rename/regeneration, pin controls, snooze, copying, project/new-thread navigation, groups and existing recovery/confirmation flows. No second menu implementation. |

The composer keeps one control row; extra actions use its existing overflow. Four new
hand-kept strings have translated entries in all 20 locales. Existing action labels are reused.

## Native policy and receipts

Pinned lib: v0.3.7 / `7cbad5d8198d37e9ded767d33ac03f2cf15875f4`.
The implementation calls `planWorkflow.mode`, `planWorkflow.view`,
`planWorkflow.followUp`, `planWorkflow.attachSourceRef`,
`planWorkflow.implementationTitle` and `composer.newThread` directly. Typed
`orchestration.dispatchCommand` and `orchestration.launchThread` validate wire inputs
in Rust; Android owns editor state, command identity, persistence and navigation.
No plan selection, implementation prompt, title extraction, queue ordering keys,
Markdown/reference parsing or launch-default policy is reimplemented in Kotlin.

A plan action rereads the authenticated bounded thread and checks the native ready
plan's ID, thread and original Markdown, effective provider mode and attachment gates.
Feedback stays in plan mode; implementation uses the native default-mode prompt and
source reference. New-thread implementation uses Desktop’s full-access Build mode. UI readiness alone never authorizes a send.

For a mode change followed by a message, the exact command IDs and bodies are persisted
before dispatch. Only an accepted step advances the Room checkpoint. Unknown stops
immediately; reopening, reconnecting or constructing a new owner never resumes it.
Explicit Resend uses the stored suffix and the same IDs/bytes. Discard/Edit remain
explicit existing recovery actions. Accepted prefixes are not resent after checkpointing.

Implement-in-new-thread uses a checkpointed native launch followed by the native
source-referenced message, because v0.3.7 `InitialMessage` has no `sourcePlanRef` field.
A refusal after creation can leave an empty target thread; the original draft remains
and there is no navigation or invented atomic-success claim. Ordinary Send and new uses
the native atomic initial-message path instead.

## Left out and acceptance limits

- **Separate background delivery:** omitted. The audited `T3Composer.qml` / `T3Backend.submit`
  has ordinary submit, send-and-new and opposite queue/steer. Pinned native delivery
  intents are `auto`, `queue`, `steer`; there is no matching background intent or builder.
  `defer_start` is a different workspace/start scheduling contract and is not relabeled.
  Existing sends already survive leaving Chat through application-scope ownership.
- **Device tests:** unavailable locally. `device_list` reports “Agent device access is turned
  off for this environment.” No device or emulator was opened; no emulator was left running.
  The instrumented APK is compiled, but this does not claim connected-suite acceptance.
- No authenticated live-host, physical/OEM keyboard, TalkBack, RTL interaction, two-device
  shared-draft or whole-process acceptance is claimed. Touch recall uses the existing
  menu/undo flow; Desktop's physical-key history navigation is not added by this change.

References: read-only Desktop `T3Panel.qml`, `T3Composer.qml`, `T3Queue.qml`,
`T3PlanWorkflow.qml`, `T3Backend.qml`; pinned native `plan_workflow.rs`, `composer.rs`,
`parity.rs`, `timeline.rs`, `domain/review.rs`, `api/orchestration.rs`.

## Verification

Java 21 from `mise where java@temurin-21`; `ANDROID_HOME=$HOME/.android-sdk`.
One Gradle invocation at a time, always `--max-workers=2`.

- Core debug: 530 passed, 5 skipped (535 cases); release: the same.
- App: 573 passed, 1 skipped in the main worker, plus 6/6 real-ViewModel cases in the required native worker (579 passed / 1 skipped total).
- Roborazzi: 228 golden PNGs; 8 new and 2 existing plan captures updated.
- Lint: 0 errors; app 112 warnings / 1 hint, core 2 warnings.
- Strings: generated 261 × 20 importer check; 4 new hand-kept keys × 20 locales with matching key sets.
- Required-bridge debug APK and debug instrumented-test APK assembled. Device execution remains unavailable as described above.
- Full command: `:core:test :app:testDebugUnitTest :app:verifyRoborazziDebug lint :app:assembleDebug :app:assembleDebugAndroidTest -Pot3a.requireBridgeSdk=true --max-workers=2`.

Retained artifacts: `/home/node/.cache/o3-android-artifacts/chat-actions/`.
APK: `app-debug.apk`; SHA-256 `9a08659e397b8d79b8636fe69dbc4de9d56bac98a9973ae3e6ce8ddd9ec88462`.
The directory also contains the instrumented APK, successful `final-verification.log`,
`validation.json` and the eight new golden captures. `tools/import-ot3-strings --check`
passed independently.

New regressions cover accepted-prefix checkpoints, unknown launch/message suffixes,
exact-ID retry after owner replacement, authoritative plan invalidation, feedback,
source-referenced new-thread implementation, native atomic send-and-new, fork recovery,
merge-back and acceptance-only navigation. Eight new goldens cover ready/refine/unknown
plan controls and response forks in both themes; two existing plan captures are updated.

The complete run caught duplicate JNI loading between the Robolectric sandbox and
plain JVM native tests. The six real-ViewModel cases now run in a separate
`:app:testNativeChatDebugUnitTest` worker, automatically required by the normal
`:app:testDebugUnitTest` task. They are not skipped. Owner shutdown joins coroutine
jobs before closing Room.
