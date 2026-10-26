# UI1i: vertical density and real motion

PR21 reconciled: commits `0947436` and `a59c9a0` are cherry-picked here (retaining UI1f/UI1h docs and this evidence).

Base: `9df6d39` on `origin/feat/ui-1` (PRs 18/19 integrated). Production `HomeScreen`,
`ThreadContent` and the real navigation/workspace are changed, not just the catalog.
No UI1g files were read or integrated. No Kotlin protocol logic was added.

## Evidence and reproduction

`before-home.png`, `before-chat.png`, `before-chat-ime.png` were captured **before UI
edits**, with the original UI1h fixture (one Home row, 60 chat messages). Their `after-*`
partners use that same content and Catppuccin Latte theme, font scale 1 and emulator.
`baseline-rich-home.png` is an additional reconstruction: UI files from `9df6d39`, with
only the expanded debug fixture, compared to `after-rich-home.png` with exactly those
five threads (idle, approval, failed, running, settled). It is not passed off as the
original pre-edit capture. The original before images remain unchanged. Relative elapsed labels advance with the
clock; timestamps and all fixture thread identities/content are unchanged.

The fixture uses the actual graph, screens, ViewModels, Room and Rust `sidebar.view` /
`timeline.view` over a deterministic fake transport. It is **not aesthetic acceptance
by itself**, and it does not validate live transport or a physical display.

- AVD `ot3a`, API 34, 1080 × 2400 px, 420 dpi = 2.625 px/dp, portrait.
- `scripts/emulator.sh start --headless`; `device_list` → `device_open`; UI commands use
  the exact returned agent-device launcher, serial, config and session.
- Open `.debug.workspace.WorkspaceFixtureActivity` explicitly. `--ez rich true` selects
  the five-thread fixture; `--ez dark true` additionally selects Tokyo Night. Defaults
  reproduce the original one-thread Latte comparison. These are debug-only extras.
- Debug catalog has no MAIN/LAUNCHER filter. Only MainActivity is a launcher.
- The emulator is the only device used. Xiaomi was neither operated nor inspected.

## Measurements

Coordinates are window/root pixels. dp = px / 2.625; rounding to one decimal in this
report. Home bounds come from the accessibility hierarchy and the layout instrumented
test; final chat/IME bounds are emitted by `VerticalWorkspaceLayoutTest` as
`UI1iMeasures`. Baseline chat sizes are the source layout confirmed by the before PNG.

| Region | Before px / dp | After px / dp | Finding |
|---|---:|---:|---|
| Window | 2400 / 914.3 | 2400 / 914.3 | same device/orientation |
| Top safe area (status/cutout) | 128 / 48.8 | 128 / 48.8 | real Pixel 6 cutout, retained |
| Bottom system navigation | 63 / 24.0 | 63 / 24.0 | retained, not an app footer |
| Useful height outside system bars | 2209 / 841.5 | 2209 / 841.5 | no fake full-screen gain |
| Home first row y | 572 / 217.9 | 460 / 175.2 | 112 px / 42.7 dp reclaimed |
| Home header below top safe area | 444 / 169.1 | 332 / 126.5 | removes duplicate safe inset; real brand needs 70 dp for touch |
| Home brand actual height | 147 / 56.0 incl top margin | 184 / 70.1 | 48 dp actions + 11 dp top/bottom, not a blank reservation |
| Search editor | 147 / 56.0 | 126 / 48.0 | borderless source composition, 48 dp target |
| Home list available height | 1555 / 592.4 | 1667 / 635.0 | same 80 dp tabs + system navigation |
| Example card row height | 218 / 83.0 | 218 / 83.0 | row anatomy/touch density preserved |
| Home horizontal list inset | 21 / 8.0 | 21 / 8.0 | preserved |
| Chat toolbar below safe area | 168 / 64.0 | 147 / 56.0 | two-line title/status, 48 dp back action |
| Chat timeline top y | 296 / 112.8 | 275 / 104.8 | 8 dp reclaimed from chrome |
| Chat composer body (no system bar) | 189 / 72.0 | 180 / 68.6 | minimal editor + send, reduced outer padding |
| Chat timeline bottom y | 2148 / 818.3 | 2157 / 821.7 | no global navigation footer |
| Final timeline/composer gap | — | 0 / 0 | also 0 with actual IME visible |
| Final timeline with IME (top→bottom) | — | 275→1337 / 404.6 high | real keyboard reduces viewport |
| Final composer with IME (top→bottom) | — | 1337→1517 / 68.6 high | sits directly above IME |
| Final keyboard+bottom inset | — | 883 / 336.4 | consumed once; no doubled keyboard padding |

Root cause in Home: the inner Scaffold applied its default safe top, and Header applied
statusBarsPadding again. The inner Scaffold now owns zero implicit insets; the header
owns the top once, and safeDrawing horizontal insets protect landscape cutouts. Outer
navigation padding is consumed. Chat already had no footer after UI1h; this correction
does not falsely claim to remove another one. Chat now consumes Scaffold padding,
uses safeDrawing chrome, and retains the working IME/navigation inset chain. In split
landscape, ThreadWorkspace consumes horizontal cutout insets once before both panes;
the sidebar keeps 280 dp of useful content and chat does not pay that inset again.

Empty space below five Home rows is unoccupied list viewport, not a reserved footer.
Removing it by stretching rows or adding invented content would destroy source density.
The New thread action moves from an overlay FAB to the search toolbar, as in ot3.

## Comparison to the real ot3 source

Read-only source: `~/Work/omarchy-t3-code-ref`, commit `7b5b1de`.

| Source | Shipped here | Deliberate mobile difference / remaining work |
|---|---|---|
| `T3Panel.qml:1121-1187`, `T3Starfield.qml`, `starfield.frag` | SkyHeader and real T3 logo/Code/OMARCHY in production Home; sky includes search; energy is lib working count > 0 | status/cutout + 48 dp actions; no sky in chat |
| `T3ThreadRow.qml:176-324` | existing three-line card/slim roles retained, host/branch/status/PR; real aura layered under content | 48 dp minimum slim rows; no desktop hover requirement |
| `threadaura.frag`, `T3ThreadRow.qml:144-211` | AGSL port of comet, attention glints, 1300 ms state-change star; static attention when motion is off | safe Canvas pulse fallback; shader math/provenance in ThreadAuraShader.kt |
| `T3Motion.qml`, `T3Reveal.qml:27-64` | fixed-width default nebula reveal, declared spring fallback; 130 ms pressed/selected feedback; source row durations | Navigation Compose defaults for push/pop; 260 ms only for tablet panes; style selectors and full arc flight remain follow-up |
| `T3Panel.qml:1655-1753`, `1893-1905` | user surface, unboxed assistant text, 6 dp corners, 14 sp body; compact chat toolbar/composer | sender/time, Markdown, activity and action rows are UI2; chat is not visually complete |
| `T3Composer.qml`, typography/tokens | focused composer role, multiline text, integrated 48 dp send control, smaller margins | full option/model/attachment/queue row is UI3 |

Compare `../../reference/ot3-sidebar-and-thread.png` and
`../../reference/ot3-timeline-shelves-composer.png`. Reference captures have different
content, viewport, theme/font and some predate the pinned source. They establish
composition/roles, not a pixel golden of Android. Source wins when captures differ.
See [CONTINUITY.md](CONTINUITY.md) for the concrete UI2/UI3 contracts and acceptance.

## Motion and fallback safety

Home's lib count activates drift/comets; idle keeps stars without inventing working
state. Row aura consumes the already mapped status/icon; it never infers protocol
state. AGSL construction is guarded; older APIs and shader compilation failures use
Canvas. Canvas sky halos are sampled circles, avoiding one radial GPU shader per star.
The original radial fallback repeatedly coincided with emulator termination during
its pixel test; with sampled halos, isolated and full pixel tests complete. No claim
is made about an unobserved physical GPU.

System animator scale 0 and app Off stop sky clocks, cursor and kit motion. Attention
retains a static outline. Power saver caps the sky to Light. One shared requested-rate ticker drives sky, spinner, shimmer, status and auras. It uses
Compose's infinite-animation frame API so test clocks can quiesce them instead of
hanging on a custom perpetual frame loop. Reduced-motion instrumentation changes and
restores the real animator setting and checks identical sky pixels across time.

## Validation

Toolchain: `~/.local/jdk`, `~/.android-sdk`, NDK `28.2.13676358`, AAR pinned v0.3.4
SHA-256 `b90064ae893cf0239a3d19b3bf213ebb8c92d6e14da0a8d4a4ff9ae083bfc036`.
The initially available host JNI was v0.3.2; it was rebuilt from clean v0.3.4 before
valid JVM runs. Early mismatched-JNI failures and interrupted emulator runs are not
counted as successful validation.

Final commands/results and video index are recorded below after the final capture.

Final acceptance run (2026-10-26, emulator only):

```sh
JAVA_HOME=~/.local/jdk ANDROID_HOME=~/.android-sdk \
ANDROID_NDK_HOME=~/.android-sdk/ndk/28.2.13676358 \
ANDROID_SERIAL=emulator-5580 OT3A_REQUIRE_HOST_JNI=1 \
./gradlew :app:assembleDebug :app:testDebugUnitTest :core:testDebugUnitTest \
  :app:lintDebug :app:verifyRoborazziDebug \
  :app:connectedDebugAndroidTest :core:connectedDebugAndroidTest \
  --max-workers=1 --no-parallel
```

- **PASS:** app JVM 114/114; core JVM 234 discovered, 230 passed, 4 intentionally
  skipped (three opt-in live tests and the theme importer). No failed JVM tests.
- **PASS:** app instrumentation 24/24, core 4/4. Includes real IME reducing the
  viewport without a gap, horizontal cutout consumed once with 280 dp sidebar content,
  draft/reading position/one stream through rotation, collapse and recreation,
  only one launcher, AGSL compile including reveal/aura, nonuniform AGSL and forced
  Canvas star pixels, system animator scale zero and shared ticker phase.
- **PASS:** lintDebug and verifyRoborazziDebug. Eight catalog goldens were intentionally
  updated for the source attention outline behind rows in card/sheet/dialog scenes;
  the image differences were visually inspected. No blanket baseline update.
- The rotation test awaits the expected draft after lifecycle recreation with a 5 s
  bound; it still asserts exact text, reading position and one stream. An earlier
  immediate assertion raced asynchronous lifecycle collection; the bounded check
  and the full suite pass. UI automation is closed during instrumentation to avoid
  competing UiAutomation registrations, and reopened only for captures.
- **PASS:** final debug APK installed and exercised with the fixture. Local delivery:
  `artifacts/ot3-ui1i-debug.apk` (ignored build artifact, not committed).
  SHA-256: `fb26547c96d39e3676a51ba7acdf1701c5fcc2a3188c2cea122935870b1aa2a3`.
- Emulator stopped with `scripts/emulator.sh stop` after evidence. No physical device
  was inspected or operated. Signed release/final distribution build, live server
  behavior and Xiaomi/performance/aesthetic approval remain unverified.

## Reviewable visuals

- [Home vs reference and baseline](comparison-home.png), generated by
  [make-comparisons.py](make-comparisons.py), with the theme/content limits labeled.
- [Same-content chat before/after](comparison-chat.png).
- [Final landscape](after-landscape.png), [measurements.json](measurements.json).
- [Home effects, 8.0 s](home-effects.mp4): real stars/comets, working orbit/dust,
  approval glints; pills remain static.
- [Native vertical push, 9.8 s](vertical-navigation.mp4): production screens and
  Navigation Compose defaults, not a custom 260 ms fade.
- [Nebula close/open, 3.1 s](panel-nebula.mp4),
  [frame contact sheet](nebula-contact.png): default shader reveal, natural-width
  content, edge dissolve and gap timing. Two consecutive actual captures are joined
  at the collapsed endpoint; playback speed and effect duration are unchanged.

The two vertical clips are consecutive portions of one real screen recording, with
no retiming. The panel clip was regenerated after fixing horizontal inset ownership.
Contact sheets were decoded from the videos, not generated illustrations. The original
pre-edit screenshots are kept separately and were never overwritten.
