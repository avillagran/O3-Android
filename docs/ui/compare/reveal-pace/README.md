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
the control and persisted choice, not reveal aesthetics. No video was captured;
visual motion acceptance and a short two-pace recording remain a follow-up.
The optional `revealEvidence` argument supports captures and real-time frame
stepping for that later recording, without adding wall-clock sleeps to normal CI.

A discarded JVM geometry experiment advanced the clock in one large jump and did
not settle the workspace geometry as intended. The final JVM test checks actual
root policy/durations and composition; the frame-by-frame Android workspace test
above supplies the real geometry comparison. No product regression was removed
or hidden by that test change.

Logs on node: `/tmp/reveal-unit.log`, `/tmp/reveal-final-unit.log`,
`/tmp/reveal-device-settings.log`, `/tmp/reveal-workspace-final.log`,
`/tmp/reveal-device-build.log`. No Xiaomi, production dispatch, shared library or
Vault work occurred.
