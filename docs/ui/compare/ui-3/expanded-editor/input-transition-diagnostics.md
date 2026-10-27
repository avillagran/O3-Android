# Input transition diagnostics — 2026-10-27

## Finding

No cause is established for the two intermittent failures from PR63 attempt 1
(run `40471408621`, job `122079788694`, head `7234188`). Attempt 2 passed the same
head; main `31b9b547c9dce5972e8b2ca0ee2150b77de60ca6` has the same tree.
No production code or readiness condition changes are justified by this evidence.

The original reports under `/tmp/ot3-pr63-reports` show:

- `ExpandedComposerDeviceTest.latteSoftEnterNeverSendsPhysicalKeysFollowTheExactMode`:
  the expanded surface did not appear within 15 seconds after `composer_expand`
  was clicked (original line 107). Logcat shows IME hide at 08:19:44.643,
  hidden at 08:19:44.750, a new show request at 08:19:45.134 and shown at
  08:19:45.324. It has no touch coordinates, target geometry or action timestamp.
  Those IME events alone do not prove where the expand gesture landed.
- `ThreadCacheDeviceTest.reopeningOfflineKeepsMessagesDraftAndReadingPositionWithoutAnotherSnapshot`:
  the chat collector count did not reach zero within 5 seconds after the header
  Back click (original line 41). Earlier reading-position assertions passed.
  IME shown at 08:22:25.126 is followed by repeated `ATTACH_NEW_INPUT` requests;
  there is no navigation/gesture trace proving whether the header click changed
  the route or whether the VM remained alive. This is not evidence of a cache
  position defect or a collector teardown deadline defect.

The earlier quota-system-Back diagnosis was supported by a Back timestamp before
current-window IME completion. Equivalent evidence is absent here, so
`CurrentWindowIme` is not generalized or applied to these clicks.

## Diagnostic change

`InputTransitionDiagnostics.kt` scopes observation to the existing click and its
existing assertion. It records `InputTransition` logcat entries containing:

- monotonic time, the target bounds in the Compose root and injected local center;
- injection start/return and assertion success/failure;
- window identity, geometry, focus/input-active state and IME insets;
- route surface markers (thread timeline versus home row), expanded composer
  presence, and the chat collector count where relevant.

The route markers describe observable UI surfaces, not the internal navigation
back stack. Together with the collector count they distinguish a thread that
remained visible from a returned home surface whose collector did not stop.
Window changes are capped at 16 per transition; the listener is removed in
`finally`. No draft/message text is logged. Diagnostics preserve the original
failure and introduce no retries, sleeps, timeouts or acceptance tolerances.

The single gesture uses `performTouchInput { click() }`. Inspection of the resolved
Compose UI test 1.11.4 AAR bytecode verified that Android `performClickImpl` uses
this exact gesture. This remains physical touch injection, not a semantics
`OnClick` invocation. A `Window.Callback` observer was rejected after verification
showed that Compose dispatches the injected touch directly to its root view.
The trace records injection, not proof that the intended handler consumed it.

## Validation and limits

API 34 Google APIs x86_64, software renderer, software Gboard and all three system
animation scales `0`; private read-only clone, serial `emulator-5580`:

- `:app:assembleDebug :app:assembleDebugAndroidTest`: passed with
  `-Pot3a.requireBridgeSdk=true -Pot3a.requireHostJni=true`.
- Final targeted Latte expanded-composer plus thread-cache tests: **2/2 passed**,
  21.339 seconds (`/tmp/input-readiness-final-api34.log`).
- Tokyo keyboard-mode test, also using the changed helper: **1/1 passed**,
  7.896 seconds (`/tmp/input-readiness-tokyo-api34.log`).

The final trace (`/tmp/input-readiness-final-trace.txt`) records Latte expand
bounds `(785,1238)-(911,1364)`, injected center `(63,63)` at uptime 369904 and
`expanded=1` at 370240. Cache header Back bounds were `(22,149)-(127,254)`,
injected center `(52.5,52.5)` at 375021; at 379204 the timeline was absent,
home row present and collectors zero. Both existing assertion deadlines passed.

An earlier local run failed the Latte test before reaching expansion: its soft
Enter assertion lacked the expected newline. A system-window inspection during
that run found a Nexus Launcher ANR dialog holding focus, and the diagnostic
window had no focus. Cache passed. That run does not reproduce either CI failure.
After the launcher was stopped in the private guest, the final tests above passed.
No failure was hidden by replaying an action inside a test.

The sole CLI boot completed. Initial cgroup `MemoryHigh` limits caused measured
reclaim pressure; the same guest was changed to the previously validated 6 GiB
maximum without a high limit, and no reboot was performed. This local resource
observation does not establish any cause for CI. The full suite was not repeated.
The original intermittent failures remain unresolved pending a failing run with
these scoped traces; a passing diagnostic run is not proof of a fix.

After validation, explicit `systemctl --user stop ot3-input-readiness-qa` released
port 5580 (`MainPID=0`, no ADB transports). The emulator aborted during shutdown,
with terminal `Result=core-dump`, `ExecMainCode=3`, `ExecMainStatus=6/ABRT`;
the journal records stop/snapshot-disabled messages before ABRT. This was after
the completed tests, not a clean emulator exit. Terminal evidence is preserved
in `/tmp/input-readiness-emulator-stop-journal.txt`; the failed unit was not reset.
