# P03 verification — 2026-10-31

Base `b42c08036df199b87338c87ce47c8fc94700712f`, branch
`feat/general-preferences`. Java Temurin 21.0.13, ANDROID_HOME=$HOME/.android-sdk.

Executed together with `--max-workers=2`:

```
:core:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug lint
:app:assembleDebug -Pot3a.requireBridgeSdk=true
```

Final run: BUILD SUCCESSFUL, 56 seconds, 114 tasks (38 executed, 76 up-to-date).
Core: 351 tests, 178 passed, 173 skipped, 0 failures/errors.
App: 378 tests, 362 passed, 16 skipped, 0 failures/errors.
P03 additions: 14 passed, 6 skipped (20 tests total).
Core lint: clean. App lint: 0 errors, 76 warnings, 1 hint; no new P03 issues.
Roborazzi verification passed against the existing goldens; no goldens changed.
`tools/import-ot3-strings --check`: PASS, 261 generated strings × 20 locales,
pinned catalog SHA256 prefix f9f6ee013ea9. Separate P03 hand-kept XML: 115 strings
in each of 20 locales, including a translated uncertain-write message.
`git diff --check`: PASS.

Fixtures execute missing connection/no fallback, native descriptor pass-through
(including mixed/unwritable), partial saves, unknown write receipts, failed
read-back, dropped writes, project file/inherit arguments, reset forwarding,
reload without redispatch, stale scope responses and invalid scope handling.
DataStore tests execute all 25 keys through update/reset/new reader, validate
quick reply bounds and enums, and close/reopen the disk-backed store.

The six additional pinned native-policy fixtures are **skipped**, because this
checkout has no host `libt3_jni.so`. They cover native capability filtering,
missing fields, aggregation, inheritance preserving other overrides, reset and
dropped-write rules, and incomplete-page writability. Android JNI and the pinned
AAR are packaged; host-native policy execution remains unverified locally.
The other baseline skips include native-dependent and opt-in contract tests.

No phone or emulator interaction. Actual host writes, device/RTL/font/IME/manual
accessibility acceptance, new-screen visual comparison, P02/P20 wiring and
runtime effects in composer/timestamps/legacy mode remain integration work.
No claim of full runtime Desktop parity or Desktop client-settings sync.
