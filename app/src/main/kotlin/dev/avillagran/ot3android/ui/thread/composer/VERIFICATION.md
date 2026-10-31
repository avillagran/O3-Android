# P06 verification, 2026-10-31

Environment: NODE, Java Temurin 21.0.13 from `mise where java@temurin-21`,
ANDROID_HOME=$HOME/.android-sdk, Gradle `--max-workers=2 --no-daemon`.
No phone, ADB, emulator or live server interaction.

Commands:

```sh
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug lint :app:assembleDebug -Pot3a.requireBridgeSdk=true --max-workers=2 --no-daemon
tools/import-ot3-strings --check
```

Final result: BUILD SUCCESSFUL. Core 201 PASS / 170 SKIP; app 379 PASS /
16 SKIP; zero failures. The 22 new package tests all pass. Native host/opt-in
fixtures remain skipped; a host libt3_jni.so is not available in this checkout.
Roborazzi verifies the 94 existing golden PNGs without changes; no new composer
visual goldens are claimed. Lint: zero errors, core 2 warnings, app 77 warnings +
1 hint (including a String.toUri style suggestion in the importer). Bridge-required
assembleDebug passes. Catalog check: 261 generated strings × 20 locales; separate
XML check verifies 24 hand-maintained composer strings × 20 locales with identical
keys and format placeholders. `git diff --check` passes.

The package adds 14 core tests and 8 Android JVM tests. Saved-state regression was
first observed as a range changing from (2,7) to (7,7) after restoring with a file
chip. The previous input connection sent a late collapsed selection; the restored
saved UI range now takes precedence over that echo. The assertion still requires
the original full range. File-provider fixtures use an attached Android provider;
real content-resolver reads and temporary-FD cleanup run under Robolectric.

Coverage:

- IME composition gates transforms/send; reversed/astral selections, surrogate
  boundaries and undo/redo. Existing hardware key/IME tests remain unchanged.
- File chips plus selection across simulated saved-state restore; failed-upload
  send gate and removal; unknown-send disables editor and alternate dispatch.
- Actual content import measured length/name/MIME, empty/oversize rejection,
  native callback failure without retry, temporary-file deletion and URI scheme gate.
- Async transform rejection after typing, another thread or queue scope; late
  upload completion after removal, different scope and coexistence with new typing.
- Unknown-send locks preserve text and invalidate pre-send transform tokens;
  loading drafts are not editable; text undo never resurrects removed file metadata.

Not established: physical IME/OEM/rotation behavior; inline reference rendering;
real binary upload/JNI failures; durable attachment send or full queue-edit,
thread-switch and reconnect integration; shared-draft revision/conflict behavior
on multiple devices. Those require the host/lib contracts and P20 wiring specified
in INTEGRATION.md and core/composer/SHARED_DRAFTS.md. The current ThreadComposer's
Room/outbox behavior is unchanged. Test success does not close P06.
