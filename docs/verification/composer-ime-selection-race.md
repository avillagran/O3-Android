# Composer selection and late IME commands

## Failure

`ExpandedComposerDeviceTest.tokyoSelectionBackRotationAndImeKeepOneThreadSession`
timed out at line 70 in runs 41294438274 and 41335640819. Line 70 is the first
selection assertion after the portrait rotation, but rotation did not lose the
range: it saved a range that had already been replaced.

The fixture does not pass the `parity` extra, so `attachmentDirectory` is null,
`durableComposerAvailable` is false and `composerSlot` is null. This test never
reaches `LocalComposerEditors` or `restoreSelection`, so PR 107 is not the cause.

## Root cause

`BasicTextField` applies IME commands to its input buffer, which only learns a
new value when the field is recomposed. A range set by semantics reaches the
editor state first. An IME command landing in that gap is applied to the old
buffer and reports the old selection through `onValueChange`.

Trace of a reproduced failure, IME-only throttle, CI display metrics:

```
17:17:05.137 next=(4,12) cur=(80,80) comp=null    via semantics SetSelection
17:17:05.142 next=(80,80) cur=(4,12) comp=(3,6)   via IME onEditCommands
17:17:05.159 presented (80,80) comp=(3,6)
17:17:05.911 saved (80,80)
17:17:06.191 test selection(4,12) line=70
17:17:21.219 TIMEOUT actual=(80,80)
```

No composition ran between the first two lines. In a CPU-stress failure the same
reversal came from a later IME command, 84 ms after the range was set. That
trace predates the composition marker, so only its call path is known.

## Fix

`ComposerEditorState` records the value the field last composed. A callback that
matches it in text and selection reports nothing new about the selection, so
`rebased` keeps the newer range and takes only the composition. A real change of
text or selection stays authoritative.

## Reproduction

The AVD carried a persistent display override, 1700x3000 at density 544, about
500 dp wide. CI runs 1080x2400 at 420 dpi, 411 dp wide. Earlier local passes were
not comparable. Before these runs: `wm size reset` and `wm density reset`.

| Condition, API 34, `-cores 2` | Before | After |
| --- | --- | --- |
| Override present, no stress | 3/3 pass | not run |
| Override present, 3 to 6 CPU spinners | 6/6 test, 6/6 class pass | not run |
| CI metrics, no stress | 8/8 pass | not run |
| CI metrics, 4 CPU spinners | 11/12 pass | 15/15 pass |
| CI metrics, 5 CPU spinners | 25/25 pass | not run |
| CI metrics, IME throttled 150 ms on, 50 ms off | 7/8 pass | not run |
| CI metrics, same throttle, IME force-stopped each run | 9/10 pass | 15/15 pass |

Tracing was present in every "before" run with CI metrics. It stayed out of the
"after" runs and of this change.

The rate is low, so the device loops alone do not prove the fix. The proof is
`ComposerScopeTest.lateImeCommandCannotRevertASelectionTheFieldHasNotComposed`,
which drives the real input connection within one main-thread turn. It failed
in its one run without the fix and passes with it.

## Neighboring tests in the two runs

- `UsageDeviceTest.settingsPeriodRotationRefreshAndBack` failed only in run
  41294438274, at its wait for `nav_tab_Settings`, before any rotation in that
  test. It is not caused by this race and was not changed.
- `TranscriptDeviceTest.landscapeImeKeepsMultilineDraftAndReaderReachable` and
  `PullRequestsDeviceTest.latteReader` passed in both runs.

## Validation

- `:app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug`, with
  `OT3A_REQUIRE_HOST_JNI=1`, Java 21, `--max-workers=2` and
  `-Pot3a.requireBridgeSdk=true`, rebased on bf25c55: exit 0, 589 tests, 1 skipped.
- First attempt of that command, before the rebase: exit 1, while the emulator
  stress loop was running. `VisualRenderingComposeTest.rasterFrameUsesBoundedReaderAndReleasesAfterScopeExpires`
  timed out at its 5 s wait. It also failed in run 41335640819 without this
  change. PR 113 addresses that test, and the rebased run passed it.
- Full package on emulator-5572 through `am instrument`, CI metrics, before the
  rebase: OK, 129 tests, 120 passed, 9 skipped by assumption.
- `ExpandedComposerDeviceTest` after the rebase, same emulator: 3/3 class runs pass.
