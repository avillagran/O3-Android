# Structured tables in the reader

The previous reader printed the Rust-provided Markdown source in monospace. It now renders the same block as a selectable table with aligned columns, bold headers, inline formatting, horizontal scrolling and the two copy actions from ot3.

Reference: `omarchy-t3-code-ref/ui/js/MarkdownStyle.js:33-44` (12/14 text scale, 7 × 12 padding, bottom dividers), `src/lib.rs:163-167` (Markdown/CSV copy). Android uses 12 sp, 7 × 12 dp and theme divider roles. Columns share bounded widths to wrap long cells while wide tables scroll on phones.

The pinned Rust `markdown.blocks` supplies `header`, `rows`, `alignments`, `markdown` and `csv`. Android neither parses nor serializes tables. The test fixture is exported from JNI and checked against the pinned native library. Copy labels come from the AAR's i18n catalog in all 20 locales.

## Evidence

- `app/src/test/snapshots/ReaderScreenshotTest/table.tokyo-night.png`
- `app/src/test/snapshots/ReaderScreenshotTest/table.catppuccin-latte.png`
- Robolectric verifies both clipboard formats and horizontal scrolling to the final column.
- `ReaderContractTest` verifies the committed block against native Rust.

These images use representative offline data; they are not live NODE/VQ discovery evidence. Emulator API 36.1 rich-reader verification passed: CSV quoting, actual horizontal range/swipe, code copy/scroll, reasoning and changes inspectors, no dispatch. Full app JVM: 192 passed; lint, build and reader golden verification passed. `emulator-tokyo-night.png`, `emulator-latte.png` and `table-scroll-copy.mp4` (28.33 s) show the real reader with offline transport. The video shows the final numeric column after swiping and the CSV action. No phone or live-server table test was performed. Math, file/thread destinations, image previews, earlier-history pagination and other reader differences remain open.
