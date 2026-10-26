# UI-1h: adaptive Chat and real row metadata

2026-10-25, node. Branch `feat/ui-1h-navigation`, stacked on UI-1f `85dd6e3`.
Intended for the next UI-1 debug build **after the closing integration includes this
branch**; this PR alone does not publish or integrate that build.

## Evidence

All PNGs were captured with T3 `device_screenshot` from AVD `ot3a`, API 34,
SwiftShader, serial `emulator-5580`. Video: `navigation-final.mp4` (55 seconds),
recorded with the exact `agent-device` launcher/config/session returned by
`device_open`. Rotation CLI reports “unconfirmed”; the subsequent screenshots and
instrumented configuration assertions confirm the resulting orientations.

- `home-portrait.png`: real Home row with environment, branch and Rust PR badge.
- `chat-portrait.png`: composer and explicit Back; global footer absent.
- `chat-landscape.png`: phone landscape, selected sidebar row and chat.
- `chat-collapsed.png`: sidebar closed, visible reopen control.
- `chat-landscape-ime.png`, `chat-portrait-ime.png`: composer above the real keyboard.
- `home-back-restored.png`: Home/footer restored after leaving Chat.

These are the real HomeScreen/ThreadScreen and navigation graph, with real
HomeViewModel/ThreadViewModel, in-memory Room and Rust domain views over an
**offline deterministic FakeT3Transport**, launched by debug-only
`WorkspaceFixtureActivity`. There is no connection to a live user server and no
credential. Normal app data is untouched. The fixture is absent from release.

## Reproduce

Toolchain: `JAVA_HOME=$HOME/.local/jdk`, `ANDROID_HOME=$HOME/.android-sdk`, pinned
lib-t3-rust v0.3.4 AAR plus its host JNI library. Emulator starts only through
`scripts/emulator.sh start --headless`; T3 `device_list` → `device_open` discovers
the already booted `emulator-5580` and supplies the UI launcher. No physical phone
was opened, inspected, installed or used.

```sh
export JAVA_HOME=$HOME/.local/jdk ANDROID_HOME=$HOME/.android-sdk
export ANDROID_SERIAL=emulator-5580
./gradlew :app:assembleDebug :app:testDebugUnitTest :core:testDebugUnitTest \
  :app:lintDebug :app:connectedDebugAndroidTest :core:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --console=plain
./gradlew :app:verifyRoborazziDebug --console=plain
```

JVM navigation tests check footer restoration, same-row no-op and saved navigation;
workspace tests check portrait/landscape/collapse, saved collapsed choice/draft and
one uninterrupted composition. Metadata tests run the actual Rust `sidebar.view`
with one/multiple hosts, a draft PR and absent branch/PR fields.
`ThreadWorkspaceDeviceTest` runs the actual VMs: scroll to message m29, rotate,
collapse/reopen, reselect, recreate activity, return portrait and Back. It asserts
the reading anchor and draft plus **one thread stream and one bounded snapshot**.

Four intentional goldens (slim rows / shelves in both themes) were regenerated
and visually reviewed for the new metadata line. All other goldens are unchanged.

## Limits

Xiaomi testing remains pending by instruction. Live server/stream content and
process death with an external server were not exercised. Activity recreation
and saved-state restoration were tested. Host OS/machine glyph is unavailable in
the shared contract and omitted; [METADATA.md](METADATA.md) and
[LIB_REQUESTS](../../../LIB_REQUESTS.md) record provenance and the optional gap.
UI-1g live theme and concurrent motion/usage/OmaBridge work are outside this change.

## Verified result and APK

- App JVM: 114 tests, 0 failed, 0 skipped (includes navigation/restoration and goldens).
- Core JVM: 234 discovered, 230 passed, 4 explicitly skipped (live connection,
  live thread, live pairing and the opt-in theme importer).
- Lint debug, assemble debug, all Roborazzi comparisons: PASS.
- Emulator: app 18/18 and core 4/4; the focused workspace test was also repeated
  after moving the sidebar control into the existing Chat toolbar.
- The initial full device run on `eaaa4ea` had the known masked-token test failure;
  rebasing onto UI-1f `85dd6e3` consumed its existing fix and the full suite passed.

Debug APK (ignored build output): `app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `66b36f45817d0802217b0634072900aa2c51c4a74ed66d03d84c0fcc2c7b1675`.
It includes UI-1h on UI-1f; UI-1g and the eventual UI-1 closing build are pending.
