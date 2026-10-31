# P08 verification — 2026-10-31

Java: `mise where java@temurin-21` (Temurin 21.0.13+9).
Android SDK: `$HOME/.android-sdk`. Gradle: `--no-daemon --max-workers=2`.

| Check | Result |
| --- | --- |
| `:core:testDebugUnitTest` | 212 passed, 176 skipped, 0 failures/errors |
| `:app:testDebugUnitTest` | 390 passed, 16 skipped, 0 failures/errors |
| New P08 JVM cases | 8 passed |
| API 34 emulator: TranscriptDeviceTest, RichReaderDeviceTest, ThreadCacheDeviceTest | 5 passed, 0 skipped/failures; 2 new P08 cases |
| `:app:verifyRoborazziDebug` | Passed; 94 goldens, 4 reader images updated |
| `lint` | Passed; app 0 errors / 80 warnings / 1 hint; core 0 errors / 2 warnings |
| `tools/import-ot3-strings --check` | Passed; 261 native keys × 20 locales |
| Hand-kept transcript resources | 31 keys × 20 locales; identical key sets and valid XML |
| `:app:assembleDebug -Pot3a.requireBridgeSdk=true` | Passed |
| `git diff --check` | Passed |

APK: `app/build/outputs/apk/debug/app-debug.apk`, SHA256
`633c570b5a220b3b14cc4275a27d55736522793ebd480dd86a5e2ade01feca9d`.

JVM cases cover occurrence offsets and stable identity during streaming, secret/tool
exclusion from search, plan Markdown search and null endpoints, direct secret call/payload
cleanup/duplicate suppression on failure, loaded-window find anchoring, quote/subagent
callbacks, ready-plan identity and uncertain retry suppression, and absence of new assistant
actions without terminal eligibility. Existing follow-latest and row-ID restoration tests pass.

The secret text-entry/recreation scenario is tested on the emulator rather than JVM:
Robolectric SDK 36 exhausted its heap while draining text/dialog render work in the initial
attempts. No existing test was ignored. The instrumented equivalent checks actual recreation,
secure-window flags, empty restored input, one-call delivery, payload cleanup, no transport
logging and no duplicate send after a second recreation.

Four reader goldens were refreshed for the navigation strip, with both themes inspected.
An intermediate find test selected duplicated preview/body text; final assertions address
the message row ID, preserving the requirement that the actual older message remains visible.

## Limits

See [P08_INTEGRATION.md](P08_INTEGRATION.md) for exact P20 wiring and native prerequisites.
History merge/duplicate-revision/paging acceptance is blocked by U18; terminal-response
semantics, fork/merge-back and U35 prompt/reference links remain unbound. No host JNI,
live-host secret delivery, P20 end-to-end dispatch/navigation, physical device/IME/security,
provider display-name/token metadata parity or exhaustive accessibility acceptance is claimed.
No phone, all-hosts checkout, Desktop source, shared library or Gradle edits.
