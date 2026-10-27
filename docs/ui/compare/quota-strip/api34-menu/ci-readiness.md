# API34 CI readiness follow-up — 2026-10-27

PR60's API34 instrumented report failed two existing scenarios: quota navigation
waited 15 seconds for IME-hidden after landscape, and Usage tried Settings while
`nav_tab_Settings` was absent after only waiting for a Home row. The diff hunk
tests passed in that CI run. No production failure has been established here.

The CI quota log records a hide request at 05:59:52.541, followed by a composer
show request via the insets API at 05:59:52.866 and IME-shown at 05:59:53.203.
This supports hardening synchronization around rotation/IME restoration; it does
not prove a reproduced diagnosis of the original failure. Home rows can also be
present in a landscape sidebar. The app intentionally hides its navigation bar
while IME is visible, so a Home-row check does not establish Settings readiness.

Only the affected test flows changed:

- Quota focuses the current recreated composer once, awaits actual semantic focus
  and visible IME, releases current Android focus, then hides the keyboard. The
  existing IME-hidden assertion, draft check and single shared collector checks
  remain. All original pre-rotation keyboard-visible assertions remain.
- The affected Usage scenario waits for the actual Settings button to be displayed
  before tapping it. Existing rotation, period, refresh, back and no-dispatch
  assertions remain.

No timeouts were increased, fixed sleeps or failure retries added, assertions
removed, or production/fixture behavior changed.

Verification on isolated `ot3a`, Android14/API34, software keyboard, software
rendering, emulator-5580:

| Run | Result |
| --- | --- |
| Original QuotaStripDeviceTest + UsageDeviceTest | 7/7 passed, 41.940s; CI failure not reproduced |
| Final same combined classes | 7/7 passed, 32.554s |
| Debug/test APK build and lintDebug | Passed |

A discarded intermediate test assumed rotation automatically restored semantic
focus; both quota themes timed out on that added precondition. The final test
explicitly focuses the current field instead of assuming restoration. The final
combined run covers both quota themes and all four Usage scenarios, including
landscape carryover. No broad suite, physical Xiaomi or production-server
acceptance is claimed.

Sources: PR60 downloaded instrumented XML and per-test logcat reports;
`/tmp/pr60-instrumented-failure.log`, `/tmp/pr60-instrumented-reports/`,
`/tmp/ci60-baseline-api34.log`, `/tmp/ci60-final-api34.log`, and
`/tmp/ci60-fix2-build.log` on node. These temporary logs are not repository artifacts.


## Single Back dismissal follow-up — 2026-10-27

CI on `7fa2c7e` again timed out at the existing 15s IME-hidden assertion in the
Latte quota scenario; Tokyo and Usage passed. Its per-test log records restored
input at 06:53:02.776, direct hide at 02.852, a new Compose insets show at 03.208
and IME-shown at 03.655. The earlier local passing result therefore did not
establish a complete fix for the CI behavior.

After the existing focused-composer/visible-IME prerequisites, the test now issues
exactly one Espresso.pressBack instead of clearing AndroidComposeView focus and
calling closeSoftKeyboard. It immediately checks composer existence, unchanged
draft and visible sidebar, retains the unchanged 15s IME-hidden wait, then checks
composer/sidebar visibility and the draft before continuing. This ensures Back
does not silently navigate away. Collector, preference, navigation and refresh
assertions remain unchanged. No production changes, retries, sleeps or timeout
increases were introduced.

A first local Back run failed a newly added composer-display assertion placed
before IME-hidden: the component existed but was not displayed during native
window resize. That run was **6/7, not a pass**. The final version checks existence
and draft during resize, and visibility after the original IME-hidden condition.
The complete QuotaStripDeviceTest + UsageDeviceTest run then passed **7/7 in
31.905s** on isolated ot3a/API34/emulator-5580 with software keyboard. It includes
both Tokyo/Latte quota flows and all four Usage cases. Test APK build and lint
passed. This is functional local verification; the original CI-only restoration
race was not reproduced as the same timeout, and its full causality remains open
until subsequent CI evidence.

A fresh unchanged baseline was not rerun here: the first emulator boot failed its
disk-space check before Android started. After independently verified artifact
cleanup by the coordinator, the same owned unit/AVD booted normally. No AVD data
was cleared or snapshots changed. Sources on node: `/tmp/pr60-third-reports/`,
`/tmp/quota-back-final-api34.log` (discarded 6/7 run),
`/tmp/quota-back-verified-api34.log` (final 7/7), and
`/tmp/quota-back-final-build.log`.

## Current-window show completion (2026-10-27)

The subsequent PR61 CI failure was **not** an IME-hidden timeout: the immediate
post-Back composer assertion failed. Its report shows Back at 07:35:20.219 before
the recreated AndroidComposeView `95baa3f` requested show at 20.401 and before
`ImeTracker.onShown` at 20.704. Thus focused Compose semantics plus visible root
insets did not establish that the current window's restored IME could consume
Back. Source: `/tmp/pr61-quota-review-reports/`, quota Tokyo logcat.

The quota test now registers an Activity lifecycle observer before launch. Each
Activity gets its own completion gate on its decor at `onActivityPreCreated`
(with `onActivityCreated` fallback). The decor callback continues dispatch to
children and does not replace AndroidComposeView's callback. Readiness requires
an observed IME prepare/progress/end sequence finishing visible with positive
height, the current attached/focused window and input host, matching window
tokens, active InputMethodManager editor/accepting text, and current visible IME
insets. Cached visibility alone cannot complete the gate. A new preparation
invalidates completion; progress/end from unrelated animations cannot complete
it. Destruction/final cleanup removes decor and lifecycle callbacks.

This uses the documented [insets callback lifecycle and dispatch order](https://developer.android.com/reference/android/view/WindowInsetsAnimation.Callback):
layout may receive the final insets before animation completion. A transaction
barrier alone was not selected as evidence of IME show completion. The test
still sends exactly one Back and keeps the immediate compositor/draft/sidebar
assertions, the original 15s hidden wait, collector checks and no-command checks.
No product code, sleeps, retries, timeout increases or golden changes.

An initial API34 Tokyo probe passed 1/1 in 21.98s. On its new decor, show-end
uptime 71220 preceded pre-Back 71325. A first full Quota+Usage run passed 7/7 in
32.447s. All three system animation scales were read as `0.0` before and after;
no animation settings were changed. The callback still delivered IME completion
at scale zero. The final gate additionally binds progress to the prepared
animation identity and is verified by three JVM tests covering stale/new-window
state, unknown events, cancellation, zero elapsed time and overlap. Final-device
Quota+Usage verification passed **7/7 in 31.03s** after that identity refinement.
The [compact event trace](current-window-ime-trace.txt) records both themes
and the single Back after current-window show-end. The test APK build and
focused JVM run passed with required bridge SDK/host JNI flags.

These are real Android window/IME and JNI tests using WorkspaceFixtureActivity
with synthetic server transport on ot3a/API34/emulator-5580, software GPU and
software keyboard. They validate the new ordering locally; they do not claim
the unchanged failing baseline was reproduced or that the full CI suite has
already passed. No physical Xiaomi test was performed.
