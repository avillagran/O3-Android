# Transcript reader idle and IME repair

PR: https://github.com/avillagran/O3-Android/pull/88

## Findings

- The local reproducible hang is `ToolInspectorDeviceTest.tokyoInspector`, at the
  final `performScrollToNode` after closing the inspector in landscape. Both
  themes exercise the same path. A delayed composer IME show request arrived
  after dialog dismissal. The timeline changed from bounds `(863,377)-(2400,690)`
  to `(863,210)-(2400,210)`: its viewport had **zero height**. Compose's semantic
  scroll search kept attempting to advance without moving. This is a layout/focus
  problem, not a secret-delivery CAS loop or an infinite transcript animation.
- `landscapeImeKeepsMultilineDraftAndReaderReachable` fails against the original
  product code with the zero-viewport assertion. The reader controls and the
  unconstrained composer could together exceed the available IME window height.
- The position preview appeared/disappeared based on the visible anchor, changing
  the very viewport used to calculate that anchor. The regression records a
  41-pixel top-edge jump against the original implementation.
- The historical cache timeout in main run `41200766670` is at
  `ThreadCacheDeviceTest.kt:47`, waiting for the thread collector to stop after
  Back. Its scoped log records repeated IME attachment during the outgoing
  transition; eventually the home row and zero collectors appear after the
  existing deadline. Back now releases editor focus and hides the IME first.
- A subsequent full run exposed a separate test-observation race: message and
  viewport bottoms were fetched in separate frames while the IME resized the
  window (`788` versus `-32`). Both coordinates are now read together on the UI
  thread. The original pixel equality, tolerance, and deadlines remain intact.

## Product changes

- Release underlying input focus before dismissing reader image, inspector, and
  diff dialogs, and before explicit Back navigation.
- Bound the compact composer area and allow scrolling within it. Keep the draft
  intact, use two visible text lines, and move model/permission options to the
  header overflow. Snapshot menu contents before hiding the IME so they remain
  available after the window expands.
- In short reader windows, keep the list's height available and move find and
  position controls into a dialog. In ordinary windows, reserve a stable preview
  line so its text cannot resize the lazy viewport.

No product behavior depends on a test flag. Existing assertions and timeouts were
not relaxed. No phone or secondary checkout was used, and the PR was not merged.

## Historical CI attribution limits

The canceled jobs did not upload per-test partial reports. Run `41194931442`
last logged `93/97` and run `41200800895` last logged `77/102`; these counters also
count the nine assumption skips twice. The former is consistent with the
inspector region and the latter with the queue region in the local runner order.
An isolated original-code queue Latte test passed. Therefore the exact active
case in **both historical cancellations** is not proven by their available logs;
the inspector hang and zero-viewport defect above are directly reproduced.

## Validation

Java 21 (Temurin), `ANDROID_HOME=$HOME/.android-sdk`, emulator `ot3a` API 34,
serial `emulator-5580`, software renderer, all three animation scales zero.

- Original-code regressions: zero landscape viewport and 41-pixel preview resize
  both fail before the repair; both pass after it.
- Broad stress: ten separate invocations of the four focal classes, **110/110
  passed**, no skips/failures (25.788–27.411 seconds each). This includes 40 queue
  cases and 40 transcript cases.
- The subsequent full suite exposed selection loss when the expanded editor's
  overflow also cleared focus. Focus release is now restricted to the compact
  editor. The final-code repeat set adds the expanded Tokyo selection case:
  **50/50 passed** across ten invocations: cache 10/10, inspector 20/20,
  landscape viewport 10/10, expanded selection 10/10. Assertions/deadlines intact.
- Final `:app:connectedDebugAndroidTest --max-workers=2`: **95 passed, 9 skipped,
  0 failed**, 104 XML cases. Wall time **169.203 seconds (2m49.203s)**; device XML
  time 161.501 seconds. Gradle's progress counter counts assumption skips twice;
  the XML count is authoritative.

- `:core:testDebugUnitTest :app:testDebugUnitTest --max-workers=2`: core **260
  passed / 176 skipped**, app **470 passed / 16 skipped**, zero failures/errors.
- `:app:verifyRoborazziDebug`: **128 goldens passed**; four reader goldens updated
  for the reserved preview line after inspecting the image differences.
- `:core:lintDebug :app:lintDebug --max-workers=2`: zero errors. Lint reports
  retain 2 core warnings and 95 app warnings plus 1 hint; the full verification
  command completed successfully.
- Emulator stopped with `scripts/emulator.sh stop`; final script status `stopped`.

Evidence is retained locally in
`~/.cache/o3-android-artifacts/pr88-idle-repair/`, including repeat logs, original
RED outputs, final connected XML/time, unit counts, and lint counts.
