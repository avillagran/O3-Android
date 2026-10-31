# Phone thread layout repair

## Evidence and causes

Read-only reference: Desktop `feat/o3-brand:ui/qml/T3Composer.qml`.
Phone evidence: `~/.cache/o3-android-artifacts/phone/landscape-thread.png`
and `landscape-thread-open.png` (2712 × 1220, density 3.4).

1. Find was a `TopEnd` overlay whenever the reader fell below 160 dp. The
   bubble occupied that same rectangle. Thread Find now belongs to the header;
   standalone compact readers reserve a row, and search/navigation opens in a dialog.
2. The connected composer mounted a separate parity editor which did not receive
   the compact policy. Its text action toolbar, model toolbar, field and loading
   indicator accumulated vertical height. Short windows now move model controls
   to header overflow, use a 60 dp composer with 48 dp icon targets, and put editing
   actions in overflow. Model options use Desktop's sliders icon. Intentional
   expansion opens a full-window editor with a close action instead of enlarging
   the bottom bar. Draft and selection remain in the same editor owner.
3. The green segments were Material's indeterminate `LinearProgressIndicator`,
   driven by `ComposerSnapshot.loading`. The connected owner also set `loading`
   for stale/disconnected/storage-blocked state. This was neither progress nor
   context usage. Removed it from the composer; attachment chips retain explicit
   upload state.
4. `ComposerParity` passed `!snapshot.editable` as `sending` to `ComposerEditor`.
   A blocked empty draft therefore displayed a send spinner. Editing permission
   and actual busy state are now separate. Only the real busy state shows a
   spinner and the localized “Sending…” label; blocked/uncertain drafts retain
   their gates without pretending to send.
5. Cached-thread validation bounded receipt of the first frame only. An idle or
   cursor-only frame consumed the deadline without validating the cache, allowing
   “Out of date · refreshing” to remain indefinitely. The 3-second deadline now
   covers the entire unvalidated interval; valid resume/snapshot cancels it,
   otherwise the existing bounded-snapshot path validates the thread. The physical
   phone's transport was not inspected; this is a reproduced code path, not a
   claim about its exact frames.

## Coverage

- `PhoneThreadLayoutDeviceTest`: exact 1220 × 2712 / 2712 × 1220 pixel windows,
  density 544 dpi (3.4), Xiaomi-style three-button side navigation in landscape,
  real split workspace and connected composer. Display and navigation overrides
  are restored. Asserts reader >=60% of the whole landscape window, checks Find
  against every laid-out message, checks empty-draft send state and action overflow.
- `CachedThreadValidationDeviceTest`: real Rust timeline projection, cached open,
  idle-first fallback and acknowledged resume without unnecessary snapshot.
- Existing parity device coverage now opens action overflow and tests expansion
  and return across both orientations and both themes.
- JVM regression distinguishes blocked empty drafts from active sending. Existing
  cached-session regression now sends an idle frame before validation deadline.
- Four new density-3.4 phone goldens (two orientations × two themes); existing
  thread/new-chat/expanded editor goldens updated for header Find and icon controls.
  Robolectric dp qualifiers round physical dimensions by at most two pixels;
  instrumentation uses the exact requested pixel dimensions.
- Screenshot test JVM heap is 1 GiB for multiple full-size image comparison buffers.

## Verification

Environment: Temurin 21 (`mise where java@temurin-21`),
`ANDROID_HOME=$HOME/.android-sdk`, `ANDROID_SERIAL=emulator-5580`, API 34.
Every Gradle invocation used `--max-workers=2`.

- Full `:app:connectedDebugAndroidTest`: **106 passed, 9 opt-in skipped, 0 failures**
  (115 XML cases), **242.583 seconds instrumentation / 4m14s Gradle**, with an
  external 39-minute deadline. The console's final “124 tests” includes duplicate
  skip notifications; counts here come from the XML cases (no duplicate cases).
- Landscape reader: **743 / 1220 px = 60.90164%** of the entire window, bounds
  `(1097, 272)–(2549, 1015)`, density 3.4. Find does not intersect any laid-out message.
- `:core:testDebugUnitTest` and `:core:testReleaseUnitTest`: **290 passed / 176 skipped
  each**, zero failures. Host libt3 JNI is absent; native/opt-in JVM cases remain
  skipped. The two new cache validation device cases run the bundled real Rust JNI.
- `:app:testDebugUnitTest`: **488 passed / 16 skipped**, zero failures.
- `:app:verifyRoborazziDebug`: **136 unchanged goldens**, including **4 new** phone
  goldens; **10 existing** goldens intentionally updated and inspected.
- `:app:lint :core:lint`: PASS, zero errors (app 99 warnings / 1 hint, core 2 warnings).
- `tools/import-ot3-strings --check`: PASS, 261 keys × 20 locales.
- `:app:assembleDebug -Pot3a.requireBridgeSdk=true`: PASS.
- `git diff --check`: PASS.

Artifact directory on NODE: `~/.cache/o3-android-artifacts/thread-layout-phone/`.
The device test restores display overrides and the original gesture navigation;
final emulator state was 1080×2400 / 420 dpi with no display overrides.
No physical phone, all-hosts checkout, or Desktop source was changed.
