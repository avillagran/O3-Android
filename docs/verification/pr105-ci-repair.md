# PR105 CI repair — 2026-11-01

Compared PR105 `9972416` with `origin/main` `47c951d` in the same checkout and
isolated API34 google_apis x86_64 emulator (pixel_6, 2 cores, 2048 MiB,
SwiftShader, animations disabled, emulator-5586). No physical device was used.

## Findings

| CI failure | Cause and correction |
| --- | --- |
| `ChatParityScreenshotTest.components` | Environment-dependent fixture: CI's actual image differs only in the timestamp, 08:53 UTC versus the golden's 05:53 Santiago. Pin and restore the fixture timezone, matching the existing screenshot-test convention. The user/assistant goldens are unchanged. |
| `PhoneThreadLayoutDeviceTest.landscapeSplitReaderOwnsSixtyPercentAndFindCannotCoverMessages` | Product regression: moving every editor into two rows spends another 48dp in short windows, violating the reader's existing 60% requirement. Use one editor/control row when height is limited; keep the full-width typing area and separate control row in taller windows. Model/permission controls remain accessible in the header overflow. |
| `TranscriptDeviceTest.landscapeImeKeepsMultilineDraftAndReaderReachable` | Same product layout regression, independently reproduced: the reader has zero height above the real landscape IME. The adaptive single row restores a nonzero viewport without losing the multiline draft. Durable composer now also explicitly considers landscape IME visibility. |
| `RichReaderDeviceTest.markdownCodeScrollCopyAndScopedReasoningAndChanges` | Stale scroll navigation after intentional bounded activity groups: the outer lazy-list scroll targets a descendant inside another scroll container. The inspector button ends up wholly clipped above the reader. Reveal the activity row first, then scroll its entry and assert that it is displayed before the real touch. Copy, content, diff, request counts and no-dispatch assertions remain intact. |
| `ToolInspectorDeviceTest.tokyoInspector` / `latteInspector` | Same stale scroll navigation, confirmed by a diagnostic run: entry bounds y=191..275, reader begins y=275; no `getTurnItem` request follows the clipped touch. Reveal the row and inner entry separately. Both themes, rotation, tabs, retry count, close and no-dispatch assertions remain intact. |
| `UsageDeviceTest.refreshPricesReadsUpdatedSummaryWithoutDispatch` | Test isolation / platform IME timing. CI log shows IME `onShown` at 07:02:33.626 before the missing Settings tab at 07:02:34.638. Tabs are intentionally hidden while IME is visible, also on main. The original test passes locally on both revisions. Clear inherited focus, hide IME and wait for Settings before the existing real touch. Keep refresh/read/no-dispatch assertions. This is not evidence of a usage-price regression. |

The `Failed to find ColorBuffer` messages do not establish the cause of any of
these failures. Five instrumented failures reproduce deterministically on the PR
revision, while all eight selected cases pass on main. No timeout was extended,
assertion removed, or retry loop introduced.

Measured final landscape reader: 743 / 1220 physical pixels = 60.90164%.

Four landscape PNGs (two themes each for `PhoneThreadScreenshotTest` and
`ComposerRecoveryScreenshotTest`) are updated for the intended restored short
editor. Portrait, chat fixture and locale goldens are retained.

## Commands and results

All Gradle invocations use `JAVA_HOME=$(mise where java@temurin-21)`,
`ANDROID_HOME=$HOME/.android-sdk`, `--no-daemon --max-workers=2
-Pot3a.requireBridgeSdk=true`; connected tests additionally set
`ANDROID_SERIAL=emulator-5586`. No Kotlin cache workaround was needed.

The instrumented selection is both complete `PhoneThreadLayoutDeviceTest`,
`RichReaderDeviceTest` and `ToolInspectorDeviceTest` classes, plus
`TranscriptDeviceTest#landscapeImeKeepsMultilineDraftAndReaderReachable` and
`UsageDeviceTest#refreshPricesReadsUpdatedSummaryWithoutDispatch` (eight cases).

- `git fetch origin`, `git switch fix/outbox-collapse-composer`,
  `git merge --ff-only origin/fix/outbox-collapse-composer`: exit 0, head 9972416.
- `gh run view 41282148231 --repo avillagran/O3-Android --log-failed` and
  `gh run download` for `reports` and `instrumented-reports`: exit 0.
- `:app:testDebugUnitTest --tests '*ChatParityScreenshotTest.components'`:
  exit 0 (ordinary unit execution; not golden verification).
- First connected attempt during cold boot: exit 1, all eight Activities
  unresolvable. Invalid setup; after boot, explicit install/resolve succeeds.
- Original branch, `:app:connectedDebugAndroidTest` with the selection above:
  exit 1, 3 pass / 5 fail, matching the five CI layout/inspector failures.
- Detached `origin/main`, same command and emulator: exit 0, 8 pass.
  Restored the PR branch and its saved timezone change afterward.
- Diagnostic `ToolInspectorDeviceTest#tokyoInspector`: exit 1, confirms clipped
  entry; diagnostic logging removed from the final change.
- Intermediate adaptive-composer fix plus inner-only scroll correction:
  exit 1, 5 pass / 3 inspector display failures. Stronger visibility assertions
  caught the remaining wrong outer scroll target; both layout regressions pass.
- Final `:app:connectedDebugAndroidTest`, same eight cases: exit 0,
  8 pass / 0 skipped / 0 failures, 33.189s instrumentation / 48s Gradle.
- `TZ=UTC :app:verifyRoborazziDebug --tests '*ChatParityScreenshotTest*'`:
  exit 0, existing fixture and all locale goldens pass under CI's timezone.
- Emulator start/wait/stop via `scripts/emulator.sh`, isolated AVD `ot3a-pr105`,
  port 5586 and private state directory: exit 0; emulator stopped after tests.

- `TZ=UTC :app:recordRoborazziDebug --tests '*PhoneThreadScreenshotTest.landscape'
  --tests '*ComposerRecoveryScreenshotTest.landscape'`: exit 0, exactly four
  intended landscape PNGs updated and inspected.
- Final directed JVM/golden/lint command below: exit 0, core 35 / app 70 tests
  passed, no skipped tests; golden verification passed, lint no errors
  (111 warnings, 1 informational finding). Gradle duration 1m11s.
- `git diff --check`: exit 0.

```sh
TZ=UTC JAVA_HOME=$(mise where java@temurin-21) ANDROID_HOME=$HOME/.android-sdk \
  ./gradlew --no-daemon --max-workers=2 -Pot3a.requireBridgeSdk=true \
  :core:testDebugUnitTest --tests '*ThreadCacheTest' \
    --tests '*ThreadComposerTest' --tests '*ComposerSessionTest' \
  :app:verifyRoborazziDebug --tests '*Composer*Test' --tests '*PhoneThread*Test' \
    --tests '*ChatParityScreenshotTest*' --tests '*ToolInspector*Test' \
    --tests '*TimelineReaderTest' --tests '*TimelineFollowLatestTest' \
    --tests '*OutboxMigrationTest' --tests '*LocalCacheSafetyTest' \
  :app:lintDebug
```

No full suite was repeatedly run. CI is the remaining full-suite validation.
The terminal branch and the all-hosts checkout were not modified.
