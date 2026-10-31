# PR96 main integration — 2026-10-31

Merged `origin/main` 717f051 into `feat/parity-wiring-2` from 0f739bb.
`ThreadScreen` retains Git/checkpoint wiring and PR88 transcript navigation,
secret context, compact overflow and viewport behavior. `TimelineReader` retains
inspector ownership and checkpoint actions alongside quote and editor-focus cleanup.
P08 is Partial after PR88 merged; native U18/U35 and terminal eligibility remain open.

## Queue input investigation

CI run 41212678205 failed at ExpandedComposerDeviceTest.kt:134, the first queue
opening. The complete exception is `Failed to inject touch input. Reason: Expected
exactly '1' node but could not find any node that satisfies: (TestTag = 'queue_edit_q1')`.
Its stack stops in `fetchSemanticsNode`, before dispatch of that edit-button gesture.
This is a missing queue-sheet target, not evidence that Android rejected that gesture.
PR84 run 41192668534 attempt 1 used the same generic failure text in a different
test, `UsageDeviceTest.limitsStreamRotationTabsAndBackReleaseSubscription`, for
missing `nav_tab_Settings`; attempt 2 passed. It does not establish a shared
composer input-dispatch defect.

The preceding queue-opening touch may have missed or the sheet may have disappeared;
the old report has no bounds/focus observation for that touch, so it cannot distinguish
those causes. IME show/hide messages alone do not establish either one.

API34 x86_64 emulator `ot3a-p20-wiring2`, explicit serial `emulator-5580`:
- Original test after merge: 11/11 passes with system animation scales 0.
- Diagnostic test: 10/10 passes with system animation scales 1.
- Both queue openings now use the existing InputTransition observer, retaining real
  touch injection and every draft, selection, rotation, payload and attachment assertion.
  The edit button additionally must be displayed. No retries, sleeps, semantic clicks,
  removed assertions, or increased deadlines were added.
- Traces observe the queue sheet taking window focus and hiding the IME, followed by
  editor focus restoration. These successful transitions do not prove a product fix.

The queue test also passed in the final full suite. The observer may affect scheduling.
No product root cause was reproduced. This change preserves diagnostics for a future
failure rather than claiming that passing repetitions resolve the intermittent cause.
Logs: `/home/node/.cache/o3-android-artifacts/pr96-main-integration/` on NODE.

## Rotation harness readiness

The first full connected run stalled after 49 cases in
`SettingsNavigationDeviceTest.settingsAndNestedPagesHaveBackWithoutFooter`.
The emulator bugreport shows the instrumentation thread blocked in
`waitForIdle` at line 60, immediately after `requestedOrientation` changed.
The replacement window had drawn, but the old-root Espresso idle handoff did
not finish. The test now waits (15 seconds, using ActivityScenario without
querying Compose roots) for a laid-out, focused landscape decor before querying
its Back control. All Back/footer/home assertions and real touches remain.
The stalled run was stopped, preserved, and superseded by a full rerun.

## Local verification

Temurin 21 from `mise where java@temurin-21`, `ANDROID_HOME=$HOME/.android-sdk`,
Gradle `--max-workers=2`:

| Check | Result |
| --- | --- |
| `:app:connectedDebugAndroidTest` | 102 passed, 9 skipped, 0 failed; 3m 41s (111 XML cases; Gradle reports 120 events) |
| Settings navigation focused rerun | 2/2 passed |
| `:core:test` | Each of debug/release: 290 passed, 176 skipped, 0 failed |
| `:app:testDebugUnitTest` | 485 passed, 16 skipped, 0 failed |
| `:app:verifyRoborazziDebug` | 132 goldens passed; no new baseline changes |
| `:core:lint :app:lint` | Passed |
| `tools/import-ot3-strings --check` | 261 strings × 20 locales passed |
| Additional `:app:testReleaseUnitTest` | 276 passed, 16 skipped, 198 failed: release manifest cannot resolve test ComponentActivity; debug suite is the CI configuration |

Phone and `/home/node/Work/ot3-android-all-hosts` untouched. PR not merged.
