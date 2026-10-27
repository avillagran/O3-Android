# Sky behind the chat and the whole sidebar — 2026-10-27

Andrés: "en OT3 el fondo animado (estrellas, estrellas fugaces, etc) se ve en el fondo del
chat y en el fondo del sidebar, en ot3-android solo se ve en el header del sidebar."

Accepted fix: ot3's panel sky now covers the whole panel, so stars, comets and the
working drift are visible behind the reader and behind the entire sidebar, in portrait
and landscape, on Home and in the sidebar opened from a chat. The sidebar header keeps
its own brighter band over the same field. This supersedes the UI1i "Do not draw sky in
chat" exclusion; it is not a claim of full chat parity.

## Reference

ot3 draws two skies from one clock (`omarchy-t3-code-ref` @ 7b5b1de):

- Panel: `ui/qml/T3Panel.qml:842-856` — `anchors.fill: parent; z: -1`, brightness 0.5,
  meteor rate 0.3, no nebula and no fade (`ui/qml/T3Starfield.qml:35,49-52`), transparent
  base over `Color.popups.background`.
- Sidebar header: `ui/qml/T3Panel.qml:1113-1128` — darker base, brightness 1.25 (0.9 on
  light themes), nebula 0.55, meteor rate 0.85, `fade = 1 − brand.height × 0.85 / height`,
  `leader: sky` so the two never drift apart.
- Rows, search and timeline add no surface of their own (`ui/qml/T3ThreadRow.qml:176-180`);
  only cards, bubbles and fields keep a colour.

Android already had the header band (`SkyHeader`, UI_SPEC 3.5) and the shader port. This
tranche adds the field that band sits on:

- `SkyField` (`ui/sky/SkyField.kt`) owns the clock, the energy of every region that
  registers work, the Auto downgrade and the effective level; `LocalSkyField` shares it.
- `SkyBackdrop` draws the panel with the panel constants above; `SkyHeader` keeps the
  band and follows the same field, so stars align at the seam and only one ticker runs.
- Full-bleed surfaces became transparent so the field reads through them: the Home and
  reader Scaffolds, the chat toolbar, the assistant row container and the composer
  surface. Cards, bubbles, fields, sheets and dialogs keep their roles.
- The phone's drawer floats over the chat, so it draws the same field with an opaque base
  (the theme background) instead of a plain `Surface`: it hides the reader and its stars
  sit where the panel's would.
- A shader that cannot be created, does not take its uniforms or fails to draw leaves its
  region on the existing Canvas fallback (the Mali/Android 16 failure mode seen before).

Reduced motion, battery saver, lifecycle pause, touch targets, scroll, selection, drafts,
composer/IME insets, safe areas and the sidebar reveal are unchanged. No protocol, bridge,
notification, cache or reader-content path was touched.

## Before / after, emulator, fixture transport

Isolated API 34 emulator (`ot3a-sky`, a private COW clone of `ot3a`; originals only read),
debug `WorkspaceFixtureActivity` (real graph, screens, view models and Rust views over a
deterministic transport). System animator scale is 0 on this AVD, so every still is one
static frame of the same field the device animates. Both APKs are debug builds: the
"before" one is main 106f692 before any edit.

| Surface | Before | After |
|---|---|---|
| Home, Tokyo Night | [before](emulator/before-tokyo-night-home.png) | [after](emulator/after-tokyo-night-home.png) |
| Home, Catppuccin Latte | [before](emulator/before-catppuccin-latte-home.png) | [after](emulator/after-catppuccin-latte-home.png) |
| Chat, Tokyo Night | [before](emulator/before-tokyo-night-chat.png) | [after](emulator/after-tokyo-night-chat.png) |
| Chat, Catppuccin Latte | [before](emulator/before-catppuccin-latte-chat.png) | [after](emulator/after-catppuccin-latte-chat.png) |
| Sidebar drawer over the chat, Tokyo Night | [before](emulator/before-tokyo-night-sidebar.png) | [after](emulator/after-tokyo-night-sidebar.png) |
| Sidebar drawer over the chat, Catppuccin Latte | [before](emulator/before-catppuccin-latte-sidebar.png) | [after](emulator/after-catppuccin-latte-sidebar.png) |
| Landscape split, Catppuccin Latte | [before](emulator/before-catppuccin-latte-landscape.png) | [after](emulator/after-catppuccin-latte-landscape.png) |
| Landscape split, Tokyo Night | — | [after](emulator/after-tokyo-night-landscape.png) |
| Landscape, sidebar collapsed | — | [after](emulator/after-tokyo-night-landscape-collapsed.png) |
| Composer with the real IME | — | [after](emulator/after-tokyo-night-ime.png) |

Empty regions of the same captures, counting pixels that differ from the region median by
more than 5 of 255 (star dots, no text in the band):

| Empty band | Tokyo Night | Catppuccin Latte |
|---|---|---|
| Home list, 1400–2000 px | 0 → **1796** | 0 → **2861** |
| Sidebar drawer, 1400–1950 px | 0 → **698** | 0 → **1046** |

## Motion, emulator

Recorded with animations on (animator scale 1, restored to 0 afterwards), 720 × 1600,
6 Mbps; the guest encodes at 7.6–8 fps under SwiftShader, so timing is not a smoothness
measurement. Never a physical device.

- [Chat and sidebar, Tokyo Night](video/sky-chat-tokyo-night.webm) — reader, drawer opens
  and closes, one field behind both.
- [Chat and sidebar, Catppuccin Latte](video/sky-chat-latte.webm) — light theme, dark ink.
- [Rotation](video/sky-rotation-tokyo-night.webm) — portrait → landscape → portrait.
- [Collapse](video/sky-rotate-collapse-tokyo-night.webm) — landscape sidebar collapse and
  expand with the chat full width.
- [Keyboard](video/sky-ime-tokyo-night.webm) — composer focused, IME up, text typed, IME
  hidden; timeline, composer and sky keep their areas.

## JVM stills (Robolectric, not a device)

`app/src/test/snapshots/SkySurfaceScreenshotTest/` and the copies here: Home alone,
a workspace with the sidebar revealed, and the phone drawer, each in Tokyo Night and
Catppuccin Latte (`jvm/`). Robolectric has no system bars, IME or compositor, so these are
composition and colour evidence only. Recording them changed two existing goldens, both
intended: `QuotaStripScreenshotTest/home-*` (Home now has the panel behind the list) and
`ReaderScreenshotTest/markdown.*` (the assistant row container is transparent).

## Verification

- `SkyPanelTest` (JVM) 6/6: panel constants, no fade, transparent and opaque base, stars
  lit in the sidebar and chat columns, field reaching the bottom rows, comets crossing the
  chat column.
- `SkyRegionsDeviceTest` (API 34) 3/3: star pixels behind the chat **and** behind the
  sidebar in landscape with an empty fixture (text or buttons cannot satisfy it); the
  portrait drawer shows the field and hides the chat (no magenta pixel of the reader
  behind it); Home's list has stars behind it.
- Existing device sky coverage unchanged and passing: `SkyRenderingTest` 2/2 (AGSL and
  forced Canvas), `SystemReducedMotionTest` 2/2, `StarfieldShaderCompileTest` 1/1,
  `VerticalWorkspaceLayoutTest` 2/2 (now asserts `workspace_sky` behind the chat).
- JVM: `:app:testDebugUnitTest` and `:app:verifyRoborazziDebug` green; `:app:lintDebug`,
  `:app:assembleDebug` and `:app:assembleDebugAndroidTest` green.
- The stills and clips were captured from the branch before it was rebased onto
  `origin/main` 88692ce (PR69/PR70); that rebase only adds the reader's conversation
  follow, which does not change any sky surface, and the JVM suite, the goldens, lint and
  both APK builds were re-run after it.
- The device runs above used the same sky sources as this commit. The only later edits are an
  import tidy-up, two comments, the private composable's name and a guard that skips
  redundant energy-map writes; no behaviour changed, and the JVM suite and lint were re-run
  after them. The AVD could not be rebooted afterwards because the host had 1.8 GiB free
  and the emulator refuses to start below its disk check.

## Emulator session

- `device_list` → `device_open("ot3a-sky")` did not start the AVD: the hub launcher exited
  with code 1 because the guest asked for 7.37 GB of free space for a userdata partition
  and the host had 2.58 GB. The AVD was then started with the project's launcher,
  `OT3A_EMU_AVD=ot3a-sky OT3A_EMU_STATE_DIR=~/.cache/ot3a-sky-emulator
  scripts/emulator.sh start --headless` (port 5580, swiftshader, `-no-snapshot`).
- The clone is private: `~/.android/avd/ot3a-sky.avd` holds qcow2 overlays whose backing
  files are `ot3a`'s images, copied small raw images and its own `config.ini`. `ot3a`'s
  `userdata-qemu.img.qcow2`, `cache.img.qcow2` and `encryptionkey.img.qcow2` were hashed
  before and after the session and are byte-identical (mtime unchanged). No other AVD was
  started or stopped, and nothing on `/tmp` or a shared checkout was modified.
- Guest limits: pixel_6 1080 × 2400 @ 420 dpi, 2048 MB RAM, 4 vCPUs in the clone only
  (`ot3a` keeps its 2). The emulator process peaked around 5.0 GB RSS; host free space
  went from 2.7 GiB to 2.0 GiB with a 473 MB clone. The guest was stopped at the end
  (`scripts/emulator.sh stop`), leaving no qemu or netsimd process; the clone is kept for
  the next reproducible run and the animation settings are back to the AVD's 0.

## Pending and boundaries

- Physical Xiaomi 23090RA98G (Android 16 / Mali) was not run in this tranche. The Canvas
  fallback is the same one the AGSL path already had, now also taken when the shader
  constructs but fails to take its uniforms or to draw.
- Not ported: ot3's scroll parallax (`sky.follow(conversation.contentY)`), Star speed and
  Shooting stars preferences, FPS meter.
- The fixture proves layout and drawing from a deterministic transport; it is not live
  server evidence and not aesthetic sign-off. Parity is not claimed from this kit.

Machine-readable detail: [verification.json](verification.json).
