# P20 composer and merged contributions — 2026-10-31

PR91 integration checkout: `/home/node/Work/ot3-android`, branch
`feat/parity-wiring`, merged `origin/main` d4d425e. No PR merge, physical phone
operation or changes to the other checkout.

## Root cause and repair

CI run 41200984966 failed Send and Undo enabled assertions in
`ParityWiringDeviceTest`. Both failures reproduced locally on API34 with the
original composer. Temporary diagnostics showed the connection ready, view fresh,
no pending command/storage error, and the session moving through `composing=true`
after text replacement. Espresso closing the keyboard can return before Compose
receives the final composition callback; a delayed composition can also arrive
without the keyboard ever having been observed visible. A bounded enabled-state wait alone was
insufficient: the second repeat left Undo disabled for five seconds.

The product now commits the editor's composition range when the previously visible
IME is hidden, and on focus loss. That final value is published to the composer
owner, preserving the text and native selection handling. Active IME composition still blocks
transforms and sending; pending commands/storage/offline gates remain intact.
Tests explicitly clear input focus before hiding the IME, then wait at most five
seconds for the actual product enabled state; controls that remain disabled still fail. Undo/redo, quick replies, both
themes/orientations, attachment unknown outcome, no automatic replay and identical
retry bytes remain asserted.

Merged PR79's header receives P02 glyph, sober/vivid and master/effective motion
settings, in both Home orientations. A regression verifies reduced motion retains
the selected glyph/style. A focused editor regression verifies that blur publishes
the committed text to its owner. Removing only the new blur publication makes
that regression fail with the owner still holding composition `TextRange(0, 7)`;
restoring publication clears it. Reveal pace remains scoped to reveal transitions.

Merged PR85's Providers page is reachable through production Settings/navigation.
The explicit host selector binds one controller to the selected connected
transport; selection/connection changes discard prior controller/UI state. The page
has no project/all-host mutation scope. P07 native binding limitations remain as
documented in `P07_BINDINGS.md`; this is not live provider auth/install acceptance.

## Validation

Java21 from `mise where java@temurin-21`,
`ANDROID_HOME=$HOME/.android-sdk`, `ANDROID_SERIAL=emulator-5580`, Gradle
`--max-workers=2`. Full connected suite uses a 2,400-second outer deadline and a
120-second per-test deadline.

- Both failed device tests: three consecutive successful runs, 6/6, with both themes
  and orientations, unknown-command recovery and byte-identical explicit retry.
- Core JVM: 251 passed, 176 skipped, zero failures (427 total).
- App JVM: 464 passed, 16 skipped, zero failures (480 total).
- Roborazzi: all 128 references verify, no reference changes.
- Lint: zero errors; app 94 warnings/one hint, core two warnings.
- Blur regression: fails without publication (composition remains `TextRange(0, 7)`),
  passes with the fix. Existing native/opt-in omissions remain skipped.
- First full connected suite: 94 passed, nine skipped, zero failures, 4m47.745s wall.
  XML has 103 actual cases; the Gradle console incorrectly adds skipped cases again
  when reporting 112 finished tests. Counts here come from testcase entries.

An intermediate full repeat had one timeout in the unchanged PR-list anchor
assertion on landscape → portrait (93 passed, nine skipped, one failed, 4m23.740s).
That case passed three consecutive isolated repeats. Its exact index/pixel assertion
and 15-second deadline remain; failure diagnostics now include the actual anchor.
No PR-list product change or claim of a proven cause is made. Final full suite: **94 passed, nine skipped, zero failures**, 103 XML cases,
**4m7.761s wall**, below the 40-minute deadline. Logs and count evidence:
`~/.cache/o3-android-artifacts/p91-repair/`.
