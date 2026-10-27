# Native web link activation — 2026-10-27

The Android reader previously ignored Rust's `link.kind` and enabled only hrefs
starting with lowercase `http://` or `https://`. Pinned Rust 0.3.5 classifies web
schemes case-insensitively and preserves the original href. A native web/GitHub
link such as `HTTPS://GitHub.COM/Owner/Repo` consequently became plain text.

`inlineSpans`, shared by paragraphs and table cells, now enables only native
`web` and `github` kinds. For those destinations it passes the original href
through Android `Uri.normalizeScheme()` before creating the URL annotation.
Host/path/query/fragment case and native JSON are preserved. Kotlin does not
classify schemes, hosts, files or threads. Native file/thread/anchor kinds and
rejected or unknown kinds remain non-clickable; U35 reference routing remains open.

Android framework intent scheme matching requires lowercase schemes. The
[official Uri documentation](https://developer.android.com/reference/android/net/Uri#normalizeScheme())
recommends `normalizeScheme` before external URIs become intents and explicitly
states that this method is not URL validation. The resolved Compose UI Android
1.11.4 `AndroidUriHandler` bytecode was inspected locally: its opening path creates
ACTION_VIEW with `Uri.parse(uri)` and starts the activity, without normalizing the
scheme. Normalization therefore belongs in this platform adapter after Rust's
classification, rather than in a new Kotlin link parser.

Reference review: `lib-t3-rust/src/markdown/blocks.rs:44–66`, ot3
`ui/js/MarkdownLinks.js:6–16` and `ui/js/MarkdownStyle.js`. Web/GitHub classification
uses the shared native kinds. Existing Android annotation styling is retained;
no glyphs or custom styling were added. This increment does not claim complete
ot3 link-decoration parity. Strong/emphasis/code/strike spans are unchanged.

Verification:

- `ReaderContractTest` 6/6 with real required host JNI. The new case calls
  `markdown.blocks` on `timeline/links.md` and compares the complete result to
  `links.blocks.json`, exported with the pinned AAR's T3Client. It confirms
  original mixed-case hrefs, web/GitHub kinds, rejected JavaScript/data/mailto
  destinations, native nonweb kinds and mixed-case table links.
- `ReaderWebLinksTest` 4/4 captures LocalUriHandler calls; no browser/network is
  opened. Real text-coordinate taps activate paragraph and table links, with
  exact scheme-only normalized URLs. It verifies nonweb/rejected clicks do not
  open, unknown/missing kinds do not acquire links, native JSON is unchanged,
  and strong/emphasis/code/strike attributes remain on linked spans.
- `ReaderScreenshotTest` 9/9 verification passed with existing goldens unchanged.
  lintDebug passed. Host JNI and Bridge SDK were required for the focused checks.

A first table interaction test tapped the center of the wide Text cell, outside
its short link label, and correctly observed no URI opening. The final test taps
the label's actual TextLayoutResult character bounds instead of weakening the
expected URI assertion.

Node logs: `/tmp/reader-web-focal-final.log` (10 focused tests, 6s),
`/tmp/reader-web-final.log` (reader goldens/lint, 11s),
`/tmp/reader-web-compose-dependency.log` and `/tmp/reader-web-uri-handler.txt`.
No emulator, physical device, production server or native reference contract was
modified. File/thread activation and the rest of U35 remain pending.
