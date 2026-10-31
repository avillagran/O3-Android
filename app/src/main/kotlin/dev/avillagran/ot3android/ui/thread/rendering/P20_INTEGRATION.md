# P09 visual Markdown integration

Reference: Desktop `feat/o3-brand` read 2026-10-31, `src/lib.rs:markdown_render`,
`vendor/t3-protocol/src/latex.rs`, `ui/js/MarkdownStyle.js`, `T3QmlBlock.qml`,
`T3InlineHtml.qml`, `T3RenderPanel.qml`, `src/documents.rs`.

## P20 wiring (outside P09 ownership)

- In `TimelineReader.MarkdownBlocks`, replace the code branch with
  `VisualCodeBlock(block, index, streaming)` and math branch with
  `VisualMath(block.optString("tex"), enabled = mathRenderingEnabled)`.
  Pass authoritative streaming status; unfinished QML/Mermaid stays source-only.
- For inline math spans (`type=math`, `tex`), append `mathText(tex)` to the existing
  annotated text run with serif/italic styling when the setting is enabled. Preserve other span styles/links
  and source order. Use source TeX when disabled. P02 already owns preference UI;
  P20 supplies its persisted local value. Do not parse Markdown again in Kotlin.
- Install `LocalCodeActions` only when terminal integration can route the native
  `command` to the selected host/thread. The callback receives the original block;
  no local shell, new command inference, or automatic execution. P14/P15 own terminal.
- Install `LocalVisualRenderer` per selected environment/thread/authority/theme.
  Change `generation` on every one of those changes, including settings. Its
  `isCurrent` must consult current authority, not a captured constant. Expose
  Offline/Expired/Unsupported from authoritative state. Keep expired grants out of
  logs/UI; the adapter never accepts a remote script, HTML, SVG or frame URL.
- Connect host document attachment descriptors to `VisualRequest(Html, ...)` using
  the original attachment in the integration callback (not a new network protocol).
  Route generated Mermaid and finished QML similarly. `VisualGallery` is the phone
  picker adaptation of Desktop's side panel. Host sessions and frame resources must
  be closed/cancelled by the integration when inactive or disposed.
- `render` performs no network I/O and returns at most 10 MiB PNG/JPEG. Shared image reader enforces 40 MP source,
  sampled 16 MiB inline, 2048 fullscreen, shared 32 MiB pixel reservations, viewport
  and lifecycle cancellation, retry, actual-MIME clipboard and generation guards.
  Engine calls run on Dispatchers.Default with a cooperative 10-second timeout.
  Engine allocations themselves must also be bounded by their implementation.
- The optional `open` callback can open a fully supported host document destination.
  Do not expose it for an unavailable route. Interactive document input, host session
  creation, frame updates and remote accessibility need the real SDK document API;
  this static frame adapter does not claim interactive HTML parity.

## Actual limits / prerequisites

Math is native Compose spans using Desktop Qt's Unicode/script/fraction approximation,
not KaTeX. Unknown commands remain literal; malformed/oversized/deep groups preserve
source. Root overbars use combining marks, which can differ from Qt overline metrics.
No new dependency required for math or code highlighting. The bounded generic lexer
handles common language comments, strings, numbers and keywords; it is not a complete
language grammar. Desktop's inspected Qt Markdown path does not syntax-highlight.

**Mermaid engine remains unimplemented.** Desktop bundles Mermaid JS and executes it
inside its isolated document renderer. No compatible engine/dependency is installed
in Android. No network rendering or remote scripts are introduced here. P20 must
adopt a vetted offline engine (with its assets/license and allocation/time limits),
and supply already-delivered frames to this adapter. A future versioned SDK host
document/frame API can supply QML/HTML frames outside the render path; it must not
be used as an online Mermaid rendering service. A partial flowchart-only parser would
misrepresent Mermaid support; none is substituted. No Gradle edits were made.

QML/HTML previews likewise require the host runtime/document API and JNI exposure.
This package defines a native raster boundary, not that API. No arbitrary HTML/QML
runs on Android. Full gallery session behavior, input, open-window adaptation and
live-device look acceptance remain blocked on those prerequisites.

P09 is therefore **partial**, not a completion of audit rows 065–067/326/365/368.
Images/audio/video from PR78 remain unchanged. Their existing tests are rerun; no
new physical-device, live host, codec, interactive document or JS-engine acceptance
is implied by unit tests or raster fixture goldens.
