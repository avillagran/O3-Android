# Reveal pace — 2026-10-27

Appearance now exposes **Transition speed: Slow / Normal / Fast**, using the
pinned AAR's translations through `ot3-strings.tsv` and the existing importer.
The setting is persisted in the appearance DataStore. Default and unknown stored
values resolve to Normal, preserving existing behavior.

Reference: ot3 `T3Motion.qml:10–11`, `T3Reveal.qml:34` and
`T3Preferences.qml:273`. Only show/hide reveal durations multiply by 1.6 / 1 / 0.6:

| Existing reveal path | Slow | Normal | Fast |
| --- | --- | --- | --- |
| Nebula | 896ms | 560ms | 336ms |
| Spring fallback | 832ms | 520ms | 312ms |

The same policy method serves SidebarReveal and VerticalReveal, covering workspace
sidebars/drawers, quota strips and task expansion. The root Ot3AppTheme propagates
the saved value; the real-graph debug fixture now consumes the same appearance
motion settings. Row/shelf, color, border, switches, status and navigation pane
animations retain their original durations. Off and system reduced motion still
snap both paths. Power saver retains the existing Full→Light cap; pace does not
change the resolved motion level.

This adds pace only. Additional reveal styles and their selector are not implemented
or declared complete. Existing shader/fallback choices and easing remain unchanged.

Verification:

- AppearancePreferencesTest **6/6**: default, all values across readers, actual
  DataStore file reopen, and unknown value fallback alongside existing settings.
- Ot3MotionTest **4/4**: exact durations for both reveal paths, unchanged other
  animation specs, Off/reduced-motion snapping and power-save cap.
- VerticalRevealTest **1/1**: reduced motion snaps without closed-panel space.
- RevealPaceApplicationTest **1/1**: production Ot3AppTheme propagates updated pace
  and duration through the real ThreadWorkspace, while sidebar actions retain the
  chat composition and draft. This checks application wiring, not just a kit demo.
- API34 MainActivity **1/1**, 7.561s: real Settings → Appearance, Normal→Slow,
  recreation retains Slow, Fast persists through returning Home. Preference restored.
- API34 WorkspaceFixtureActivity **1/1**, 20.115s: real Home→thread→workspace,
  controlled clock frames exercise close/open with saved Slow and Fast; the Fast
  sidebar has greater revealed width at the same 160ms sample. Closed width is
  zero; one thread collector and no command dispatch. Animation scale and settings
  are restored. The frame comparison is functional timing evidence with synthetic
  transport, not an assessment of physical-device feel or all reveal styles.
- Debug/test APKs, lintDebug and string importer check passed. Host JNI/Bridge SDK
  were required for the JVM checks. No existing golden images needed replacement.

[Slow selected](appearance-slow.png) and [Fast selected](appearance-fast.png) are
actual MainActivity screenshots from the isolated API34 emulator. They document
the control and persisted choice. The initial increment did not include video;
the real-time follow-up below adds two short clips. Complete visual motion parity
and the other reveal styles remain pending. The optional `revealEvidence` test
argument remains separate from these recordings; it was not used for them.

A discarded JVM geometry experiment advanced the clock in one large jump and did
not settle the workspace geometry as intended. The final JVM test checks actual
root policy/durations and composition; the frame-by-frame Android workspace test
above supplies the real geometry comparison. No product regression was removed
or hidden by that test change.

Logs on node: `/tmp/reveal-unit.log`, `/tmp/reveal-final-unit.log`,
`/tmp/reveal-device-settings.log`, `/tmp/reveal-workspace-final.log`,
`/tmp/reveal-device-build.log`. No Xiaomi, production dispatch, shared library or
Vault work occurred.


## Real-time touch recording — 2026-10-27

The verified debug candidate at `2624a18` (APK SHA256
`03b5312f780824410900557d3bc624b84f5247d4ed2706b576d4bd385712ba62`)
was installed on isolated emulator-5580 / ot3a, Android14/API34. Software rendering
used gfxstream with lavapipe/swangle. Both clips are 2400×1080 landscape, Tokyo
Night, the same node/chat fixture content and reading position. The flow is the
actual Home → thread → workspace with Rust JNI and synthetic transport.

- [Slow, 4.896s](device/slow-sidebar.mp4): collapse and reopen through two real
  agent-device touches. [Selected setting](device/slow-settings.png),
  [touch telemetry](device/slow-sidebar.gesture-telemetry.json).
- [Fast, 4.558s](device/fast-sidebar.mp4): the same actions and surface.
  [Selected setting](device/fast-settings.png),
  [touch telemetry](device/fast-sidebar.gesture-telemetry.json).
- [Capture metadata/checksums](device/verification.json).

Both accepted clips contain visible intermediate reveal frames and settle back
to the open sidebar. They were inspected across full contact sheets. The shorter
Fast reveal phase is observable, but software-emulator capture is not a physical
performance benchmark: total clip length includes pauses and is not an animation
duration measurement. No Compose test clock, frame stepping, test instrumentation,
retiming, manually rendered animation or production code change was used. The
agent-device capture records actual touch injection and native UI rendering.

QA initially found Android Accessibility **Remove animations** enabled. The first
two attempts correctly snapped and were rejected as two-pace motion evidence;
those discarded clips are outside the repository. The system option was disabled
through the Android Settings UI for the accepted recordings, then restored to its
original enabled state. The app's original Normal pace was also restored through
Appearance and observed selected. This explicitly distinguishes QA setup from the
correct reduced-motion behavior. No developer-mode setting was enabled.

No test suite was rerun: this follow-up adds evidence only to the already verified
APK. No Xiaomi, physical keyboard, production transport, extra reveal style or
complete aesthetic parity acceptance is claimed. QA contact sheets remain on node
at `/tmp/pace-slow-accepted-sheet.png` and `/tmp/pace-fast-accepted-sheet.png`.
