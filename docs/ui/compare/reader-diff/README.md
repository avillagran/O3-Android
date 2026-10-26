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
not production-server or Xiaomi acceptance. Wide tree, split view, hunk/context
controls and syntax highlighting remain pending.
