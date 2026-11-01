# PR 107 instrumented failure investigation

## Queue editor selection

Run 41292431114 fails at ExpandedComposerDeviceTest.kt:143, specifically
`selection(3, 9)` after cancelling the expanded queue edit. The original text
has already passed its assertion. This is a product defect, not an assertion
that should change.

The connected ComposerParity and ThreadContent's queue/expanded surface owned
different ComposerEditors. The original range was saved only in the former.
Cancelling the expanded queue edit displayed the latter's normal editor with
its initial end-of-text range.

A new JVM integration test exercises the connected composer, an expanded queue
edit, saved-state recreation, cancellation, and return to the compact surface.
It fails on both unmodified PR head 6edbe5f and origin/main de1e0f6 with exactly
`expected TextRange(3, 9), actual TextRange(21, 21)`. This establishes that the
defect predates this branch. The fix shares the thread-owned saveable editors
with the connected composer and restores that selection into a remounted
session. Standalone composers retain their existing private state.

Existing device assertions, attachment checks, timeouts and retries are unchanged.

## Cancelled run at 57/129

Run 41289521493 has no downloadable instrumented-reports artifact. Its last
progress message is 57/129, eight skipped, at 08:46:58; cancellation is at
09:15:45. Treating 57 as an XML ordinal gives the wrong test.

Correlating run 41292431114's per-test logcat timestamps with its progress log
shows that skipped tests are counted twice in these Gradle progress messages:

- At 09:28:14, progress is 57/129, eight skipped. XML/logcat test 49
  (PullRequestsDeviceTest.tokyoReader) finished at 09:28:10.996; test 50
  (PullRequestsDeviceTest.latteReader) runs 09:28:10.997–09:28:17.642.
- At 09:28:34, progress is 64/129, nine skipped. XML/logcat test 55 finished
  at 09:28:33.165 and test 56 is running.
- Final progress is 138/129, nine skipped; XML contains 129 testcases.

Thus the cancelled run stalled in PullRequestsDeviceTest.latteReader (50th
testcase, after 49 completed plus eight duplicate skip counts), assuming the
same runner order. Both revisions contain the same test inventory/order.
This matches the earlier independently recorded latteReader stall in the
2026-11-01 host-surfaces investigation, before Chat actions. PR screen/tests
have no diff between 893984f and origin/main. The later CI run passes latteReader
in 6.661 seconds. These facts support a recurring pre-existing intermittent
hang, rather than identifying a Chat actions regression.

The exact blocking operation/root cause remains unproven: the cancelled run
retained neither per-test logcat nor a thread dump. No speculative test or
product change is made for this hang.

## Usage and IME

The 893984f helper clears focus and hides the IME before waiting for and
clicking Settings. All four UsageDeviceTest cases pass in run 41292431114's
XML, including repeatedTabEntrancesKeepOneSubscription (3.610 seconds).

## Local validation

All Gradle commands use `JAVA_HOME=$(mise where java@temurin-21)` and
`ANDROID_HOME=$HOME/.android-sdk`, with `--max-workers=2
-Pot3a.requireBridgeSdk=true`.

- `git fetch origin`: exit 0.
- `git switch feat/chat-actions`: exit 128, because the branch is also assigned
  to the all-hosts worktree. `git switch --ignore-other-worktrees feat/chat-actions`:
  exit 0. No files in that other checkout were accessed or modified.
- `gh run view` for all three supplied IDs: exit 0. Download of run 41292431114
  reports: exit 0; download for cancelled run 41289521493: exit 1 (no artifacts).
- `:app:testDebugUnitTest --tests '*ComposerScopeTest.durableComposerSelectionSurvivesExpandedQueueCancellationAndRecreation'`:
  exit 1 before the fix on both PR head and origin/main, as expected.
- `:app:testDebugUnitTest --tests '*ComposerScopeTest' --tests '*ComposerEditorStateTest' --tests '*ComposerParityTest'`:
  exit 0 after the fix.
- `:app:verifyRoborazziDebug :app:lintDebug :core:lintDebug`: exit 0;
  app JVM results contain 577 tests, zero failures/errors and one skipped.

Device validation remains outstanding. The device tool reports that Agent
device access is disabled; the required headless-emulator host skill is also
absent from the installed skill directories. No emulator or physical phone was
started or accessed. The device timeout test and latteReader must still be run
on API 34 once device access is available; a passing JVM regression does not
establish that the intermittent emulator hang is fixed.
