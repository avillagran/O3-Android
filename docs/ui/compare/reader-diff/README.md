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
File tree/navigation, split layout, wrap preference, hunk folding, context expansion,
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
are in `ReaderScreenshotTest/diff.*.png`; light full-modal emulator capture is still pending.
Native input/output fixture: `timeline/diff.input.json` and
`diff.view.json`; ReaderContractTest checks it against the actual JNI.
