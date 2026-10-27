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
