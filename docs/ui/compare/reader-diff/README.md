# Read-only checkpoint diff

Before: the Changes fold listed file names/counts only. Now Open diff reads the checkpoint
range from `changes.summaries`, calls typed `orchestration.getTurnDiff`, and renders the
native `diffReview.scopedResult` output. Rust owns range/thread validation, patch parsing,
file/line limits, rename paths, binary/missing markers and line numbers. No raw patch
parser or Git mutation is added to Android.

Source: ot3 `T3Backend.qml:1451-1456`, `T3DiffReview.qml` (unified narrow layout, default
wrapped text, whitespace filtering, selectable monospace, native row colors). Android
uses a full-screen modal, a lazy list of unified rows and a whitespace toggle. Closing
cancels the read; retry/toggle clears prior data. A changed connection generation cannot
publish an old result. Invalid thread/range tests call the actual pinned Rust library.

## Scope and remaining differences

This is the useful narrow-screen read increment, not complete desktop diff parity.
Wide-screen file tree, split layout, hunk folding, context expansion,
syntax highlighting and document preview integration remain. No live-server or phone
evidence is claimed; fixtures use real ViewModels/JNI/projection with offline transport.
The patch body is selectable. There are no stage/apply/revert actions.

## Validation

Final local validation: app 196 passed; core 282 passed / 5 opt-in skipped; lint,
compilation and golden verification passed. The real workspace instrumented reader test
passed on API 36.1: open from Changes, native lines, whitespace refetch, close back to the
same file summary, and no dispatch. JVM dialog test covers failed read/retry/filter/close;
native tests reject wrong thread/range. Tokyo Night emulator capture and 16.7 s encoded
video (`diff-read.mp4`) show filtering and return to the reader. Dark/light golden captures
are in `ReaderScreenshotTest/diff.*.png`; light full-modal emulator capture is included in the navigation increment below.
Native input/output fixture: `timeline/diff.input.json` and
`diff.view.json`; ReaderContractTest checks it against the actual JNI.

## File navigation increment

The modal now follows ot3's narrow file navigation (`T3DiffReview.qml:130-131,178`):
previous/next controls and a path picker render one native-projected file at a time.
Selecting a file is local UI state and sends no new request. Whitespace refresh and
state restoration preserve the selected path; renamed paths fall back to native
`oldPath`, and a removed selection falls back to the first available file. Each file
starts at its header. Buttons use the shared OT3 icon/touch feedback component; file
switching itself has no animation, as in desktop.

This increment does not implement the wide-screen tree or split rendering. Existing
wrapped rows, native order, binary/missing states and read cancellation stay in place.
New dark/light `ReaderScreenshotTest/diff-navigation.*.png` images exercise the file
browser with a native projection fixture. Final device evidence is linked below.

Validation for file navigation: app JVM 210/210, screenshot verification, lint and
assembleDebug pass. The focused dialog test checks boundaries, direct selection,
binary/missing file rendering, zero extra reads when switching, whitespace refetch
and recreation. Tokyo Night and Latte goldens were visually inspected.

The existing `RichReaderDeviceTest` now walks from Home into the real chat, opens
Changes, navigates two native-parsed file patches via arrows and picker, preserves
the second selection through whitespace refetch, and returns to the timeline. Its
final assertion still requires exactly two diff reads and no command dispatch.
The expanded scenario passed on API 36.1 with real JNI and synthetic transport.

## Wrap control

Wrap defaults on, as `T3DiffReview.qml:53,137`. Turning it off uses measured Fira Code
row widths (`:83-90`) and one horizontal scroll container for the complete patch
(`:196`), leaving the dialog controls fixed. It does not refetch or parse data.
The dialog preserves wrap state on recreation; new dialogs start wrapped. The
focused JVM interaction test verifies actual overflow, swipe displacement, saved
state and unchanged read count. Full-modal Tokyo Night/Latte goldens are
`ReaderScreenshotTest/diff-controls.*.png`; these are Robolectric, not phone captures.

Final local verification after wrap: app 212/212, lint, debug/test APK builds and
all screenshot goldens pass. Existing wrapped goldens are unchanged. The two expanded
instrumented reader tests passed on API 36.1, including actual horizontal overflow
and swipe displacement.


## Rotation and device acceptance — 2026-10-26

Manual rotation exposed a real bug: the dialog lived inside a virtualized Changes
row and disappeared when that row left composition. The reader now owns the dialog,
with saved state scoped to the selected thread. The regression opens it from Home
and chat, chooses the second file, disables wrap, rotates portrait → landscape →
portrait, then closes back to the timeline. It also verifies one thread stream.
Standalone dialog restoration tests alone did not cover this failure.

Final verification: 212 app JVM tests, lint/build/goldens passed; both real-workspace
instrumented tests passed on API 36.1. The device fixture has a deliberately long
native-parsed line so no-wrap demonstrates actual scrolling. File navigation makes
no extra reads; whitespace refresh does; no command dispatch occurs.

- [Tokyo Night portrait](navigation-device/tokyo-night.png)
- [Catppuccin Latte portrait, same content](navigation-device/catppuccin-latte.png)
- [Tokyo Night landscape](navigation-device/tokyo-night-landscape.png)
- [44.47-second video](navigation-device/navigation.mp4): next file, wrap off,
  horizontal scrolling and rotations; the recording letterboxes orientation changes.

These are emulator captures with synthetic transport and real Kotlin surfaces/JNI,
not production-server or Xiaomi acceptance. Wide tree, split view, context
controls and syntax highlighting remain pending; hunk folding follows below.


## Hunk folding increment — 2026-10-27

Native `kind=hunk` headers now toggle their following rows until the next hunk.
The 48dp touch target, 0.08 accent tint, chevron and accessibility Expand/Collapse
actions follow the QML fallback `T3DiffReview.qml:71–81,101,103`. Kotlin does not
parse patch text or mutate native JSON/full patch. This increment does not claim
complete parity with ot3's native diff renderer. File navigation resets folds,
as do new content, scope and whitespace settings; A→B→A does not restore stale
folds. Saved local state survives real activity rotation for the same diff.

Verification: focused JVM `ThreadDiffDialogTest` (5) and `ReaderContractTest` (4)
passed; lint/debug APK and test APK builds passed. The three affected diff
screenshot scenarios were updated intentionally (six theme goldens); all eight
`ReaderScreenshotTest` tests then passed `verifyRoborazziDebug`. `DiffHunkFoldDeviceTest` passed
2/2 on emulator-5580 / ot3a361 (API 36.1), exercising the actual Home → thread →
Changes → diff graph, Rust JNI parsing and synthetic transport in both themes.
It verifies touch folding, an independent second hunk, portrait → landscape →
portrait restoration, file/whitespace resets, return to timeline, one stream and
no mutation dispatch. Landscape assertions explicitly scroll the virtualized
second hunk into view. This is emulator acceptance, not production transport or
physical Xiaomi acceptance.

- [Tokyo Night expanded](hunk-device/diff-hunks/tokyo-expanded.png) / [folded](hunk-device/diff-hunks/tokyo-folded.png)
- [Catppuccin Latte expanded](hunk-device/diff-hunks/latte-expanded.png) / [folded](hunk-device/diff-hunks/latte-folded.png)
- [Touch recording](hunk-device/hunk-touch.mp4): agent-device taps the first
  header text at (540,871) then (540,848), folding and reopening it while the
  second hunk remains visible. The SelectionContainer does not intercept these
  taps. [Gesture telemetry](hunk-device/hunk-touch.gesture-telemetry.json).

Split/tree/context controls and syntax highlighting remain pending.


## Wide split increment — 2026-10-27

Wide mode places Diff, whitespace, layout, wrap and Close in a single adaptive
bar, leaving more height for code on a rotated phone. Narrow chrome is unchanged.
The Split/Unified button appears at useful content width ≥650dp, after safe
insets and the dialog's 12dp padding on each side. Below the threshold the view
is unified and the button is absent; returning wide restores the split preference.
Rendering consumes Rust 0.3.5 `splitRows` directly: left old-line gutter, right
new-line gutter, null sides blank, native cell kinds for color, equal columns and
a separator. No Android parsing/alignment or native payload mutation is involved.
Reference: QML `T3DiffReview.qml:65–66,71–81,205–213`, Rust
`diff_review.rs:64–94`. This is parity with the QML fallback behavior, not a claim
of complete parity with ot3's native document renderer.

Independent UI fold sets use each layout's own native indices; toggling and
phone portrait → landscape → portrait → landscape preserve preference and folds.
Changing files/content/scope/whitespace clears both. Saved content identity is a
SHA256 fingerprint, never the full patch. Wrap stays local and no-wrap scrolls
the complete two-column patch. Rotation recreates/refetches the dialog normally
(four reads across the three recreations in this scenario); layout/wrap/file
actions themselves add no reads. There is one thread stream and no command dispatch.

Validation: 648/649dp useful width hides the control, 650dp exposes it; focused
Compose tests cover native null cells, side-specific numbers, independent folds
and unchanged payload. JNI tests verify the pinned fixture and unequal native
left/right-null output. Existing reader goldens remain unchanged; two wide split
goldens were added, with Split state and the native right cell asserted before
capture. Final verification: 9 dialog JVM tests, 5 JNI contract tests, 9 reader
screenshot tests, and 2 split device tests (25.859s); the two existing hunk device
scenarios also passed earlier in the four-test regression run. Debug/test APK build, lint, string importer check and focused
Roborazzi verification passed. Emulator evidence uses ot3a361 API36.1, real
Home/thread/Changes/dialog Kotlin UI and JNI with synthetic transport. It is not
production-server or physical Xiaomi acceptance.

- Tokyo Night: [narrow unified](split-device/tokyo-narrow-unified.png), [wide split](split-device/tokyo-wide-split.png), [folded](split-device/tokyo-wide-split-folded.png).
- Catppuccin Latte: [narrow unified](split-device/latte-narrow-unified.png), [wide split](split-device/latte-wide-split.png), [folded](split-device/latte-wide-split-folded.png).
- [24.24-second touch recording](split-device/split-touch.mp4) and [gesture telemetry](split-device/split-touch.gesture-telemetry.json): wide Unified/Split and hunk touch.

File tree, context controls, syntax highlighting and new copying features remain out of scope.


### Static split golden follow-up — 2026-10-27

PR61 build run `40460200877` failed only the Latte `wideNativeSplit` golden.
The downloaded actual/reference comparison confines all 3,099 differing pixels
to the layout button bounds `(1351,32)–(1487,112)`; split rows and the rest of the
screen match. The reference itself retained a transient pressed-control capsule.
The comparison shows different side arcs of that capsule; it does not establish
which Material feedback/shape animation contributed to the particular frame.
The original focal test passed locally with CI's required JNI/Bridge SDK options,
so the original timing failure was not reproduced locally.

The screenshot now activates the layout with its semantic OnClick action, which
does not inject a touch press, and still requires Unified action text and the
native right cell before capturing the complete golden. Device touch tests remain
unchanged. Recording both themes leaves Tokyo Night byte-identical and removes
only the accidental Latte control capsule (9,587 pixels inside the same bounds).
No production change, comparison tolerance or assertion was relaxed.

Final full `ReaderScreenshotTest` verification passed 9/9 in 17s with
`OT3A_REQUIRE_HOST_JNI=1`, `-Pot3a.requireHostJni=true` and
`-Pot3a.requireBridgeSdk=true`, using `:app:testDebugUnitTest
:app:verifyRoborazziDebug --tests '*ReaderScreenshotTest*'`. Logs on node:
`/tmp/pr61-golden-repro.log`, `/tmp/pr61-static-record.log` and
`/tmp/pr61-static-verify.log`; downloaded CI reports: `/tmp/pr61-golden-reports/`.
