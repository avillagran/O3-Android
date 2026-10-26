# Provider quota strip — 2026-10-26

The production Home list now has a stable first item for provider usage. It scrolls
with the threads. The real thread reader hosts the same component immediately below
its toolbar, capped at 200 dp. Overflow on both screens and Appearance control the
same persisted preference, off by default. Tapping a group opens Usage on Limits;
the thread passes its environment. Home observes all saved environments.

## Data and ownership

`AppGraph.quotaSource` shares one native `QuotaReader` reducer/server-config stream
per observed environment across Home, thread and the real Limits view model. Each
collector acquires a reference. The last release cancels and joins that entry before
another acquisition may replace it; no zero-consumer entry or background stream is
retained. Connection replacement/disconnect cancels the old read; stream close/error
clears old observations and retries with bounded backoff. The application-owned
ProviderRefreshGate remains independent of screen/entry lifetime, with failed probes
counting toward its five-minute interval. Shell subscriptions and reader view models
are unchanged. The strip slot is never used as a key around reader/composer content.

All quota folding/projections remain Rust `quotaSources.apply`. Android renders
sanitized windows only. lib-t3 0.3.5 source `d4bf6054b4480efdcd8a436cb66cb7f2e23664b1`,
AAR SHA256 `b72a5207eaf7c075c5a24e097e150214d36e0cbf4450087b6adf9d1ab2ff4f09`.
Bridge SDK source `f7d93a9ea8600991a229cadb9d189306965d4195`, production flavor, both
ABIs verified by the existing build task. No PR40 SDK or shared-library edits.

## Evidence provenance

Goldens in `app/src/test/snapshots/QuotaStripScreenshotTest` show the same empty
Home/thread content with the strip off/on, in Tokyo Night and Catppuccin Latte,
plus empty/offline and reduced-motion-hidden cases. Inputs are the **genuine frozen
native sanitized output** `debug/assets/usage/limits.view.json` inherited from PR41;
these screenshots do not run JNI in Robolectric. Test time zone is explicitly
America/Santiago and verification runs under host TZ=UTC. Separate core JNI tests
exercise the common reducer pipeline.

Device evidence uses WorkspaceFixtureActivity: real navigation, production Home /
thread / Usage components, real view models/connections and JNI reducer, **synthetic
transport events and conversation**, not a live provider, NODE, bridge, or Xiaomi.
Before/after captures preserve the same content and palette. Instrumentation covers
persist/recreation, Appearance/overflow, thread draft preservation, landscape,
Limits routing, background/resume, empty observations and disconnect/reconnect.

## Exact desktop differences / continuity

- U26 `quotaSources.present` is absent in 0.3.5. There is one group per native
  provider/account observation, no account pooling or deduplication across hosts.
- Bars show **used** percent, explicitly labelled, with static accent fill; there is
  no remaining calculation, pace color, expected marker, relative reset, or notice
  synthesis in Kotlin. Reset timestamps use the Android platform absolute formatter
  retained from f240491; narrow rows ellipsize the visible reset text.
- Home shows at most two native windows per observation; thread shows all windows
  in a 200 dp scroll area. Native window order is preserved. Account details and
  additional Home windows remain on Limits via the group tap, rather than inline
  account expansion. No new refresh control is added to the compact group; Limits
  has the existing Refresh, with the same five-minute gate.
- Loading/offline/read-failure state is shown per environment. A connected observation
  with no windows is hidden. No desktop tooltip/external-link notices are invented.
- Reveal uses the existing vertical Ot3Reveal implementation and reduced-motion
  policy. Bars/numbers never animate. The existing Usage/Limits **160 ms tab fade
  remains pending**, separate from this strip/lifecycle increment.
- Physical-device and productive provider/network evidence remain separate work.

## Verified result

- JVM: **218 app tests passed**, **287 core passed / 5 skipped** (including the
  native quota pipeline and focused shared-source/refcount/reconnect tests).
- `assembleDebug`, `assembleDebugAndroidTest`, production bridge verification,
  `lintDebug` and **all** `verifyRoborazziDebug` passed, sequentially before device QA.
- API 36.1 `ot3a361`, explicit serial `emulator-5580`: **6/6** instrumented tests
  passed (`QuotaStripDeviceTest` 3 and existing `UsageDeviceTest` 3). The run includes
  actual Appearance persistence/recreation, both themes, empty/offline/reconnect,
  draft retention, overlap in the landscape Home sidebar/thread, Limits/Back,
  zero config collectors in background and provider probe pacing across navigation.
- The source change to composable Usage dispatch is regression-covered by the typed
  route/Back test. A full non-incremental Kotlin compile was used before final QA;
  both production and debug overrides were verified to match the interface signature.
- Debug APK SHA256:
  `5287d9c9203091ffbe6daf8b9d58163f6fde7c2bcad8d4a4f85285565dc31775`.
  This is a development fixture build, not a phone delivery or release.
- Helper/app closed, Device panel detached and emulator stopped after export.

| Surface | Tokyo Night | Catppuccin Latte |
|---|---|---|
| Home | [before](device/home-before.tokyo-night.png) / [after](device/home-after.tokyo-night.png) | [before](device/home-before.catppuccin-latte.png) / [after](device/home-after.catppuccin-latte.png) |
| Thread, same draft | [before](device/thread-before.tokyo-night.png) / [after](device/thread-after.tokyo-night.png) | [before](device/thread-before.catppuccin-latte.png) / [after](device/thread-after.catppuccin-latte.png) |
| Landscape shared sidebar + thread | [Tokyo](device/thread-landscape.tokyo-night.png) | [Latte](device/thread-landscape.catppuccin-latte.png) |

[Actual toggle and navigation video, 18 seconds](toggle-navigation.mp4) is a trimmed,
scaled recording of the successful instrumented run, covering both palettes. It
includes real orientation changes, with Android's screenrecord letterboxing. No
failed-run video or captures are included. The captures above were overwritten by
and exported from the final successful run; synthetic fixture provenance applies
throughout.

## Rotation synchronization regression

CI run `40393834706` (API 34) timed out opening Limits after the Latte
landscape tap. The test treated Compose idle as completion of Android rotation
and tapped a quota node without checking visibility. On API 36.1, the unchanged
test with software IME enabled passed, but its captured landscape frame was still
halfway through Android's rotation animation with the keyboard visible. This
confirms the missing synchronization; the original CI timeout was not reproduced
locally.

The test now waits for landscape configuration **and** the split workspace's two
quota groups/sidebar, dismisses the IME, waits for hidden IME insets, and requires
the tapped group to be displayed. Draft, single shared subscription, Limits/Back,
and background-release assertions remain in place. No timeout was increased.
API 36.1 validation enables `show_ime_with_hard_keyboard=1` to exercise software
keyboard input and verifies the settled landscape capture after dismissal. API 34
CI remains the cross-version check; this does not establish keyboard-open strip
usability at every viewport height.
