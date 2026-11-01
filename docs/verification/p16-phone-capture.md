# Phone capture and offline Mermaid (2026-10-31)

## Delivered scope

The thread attachment composer offers Dictate, Choose screenshot and Take photo,
including its expanded editor. Compact landscape places these actions in the
Phone capture overflow menu to preserve the reader’s height. A screenshot is an image selected through Android's
content picker: this does not record the display or request MediaProjection.
The existing arbitrary-file attachment picker remains available.

Dictation presents an explicit speech-service disclosure before requesting
RECORD_AUDIO. It prefers Android's on-device recognizer (API 31+) when available;
otherwise the selected Android speech service receives EXTRA_PREFER_OFFLINE.
That flag is a preference, not an offline guarantee: the disclosure explains that
a service may use the network. O3 does not implement audio transport, retain audio,
or send it to the connected host. Only final results enter an editable review;
Insert into draft replaces the originally selected range, after matching scope,
text and editor revision. No message is dispatched automatically. After insertion, text follows the normal
draft storage and synchronization settings. Cancel, lifecycle stop,
service errors, permission denial, missing service, timeout (60 s) and retry release
the recognizer and invalidate late callbacks. A changed draft rejects insertion.

Images and successful camera results call ComposerRequest.ImportUri and the
existing ComposerAttachmentImporter / T3Client.upload path. The importer stages
at most 50 MiB, measures the stream and passes a borrowed read-only descriptor to
the native authenticated upload. Uploading/failed/missing descriptors block Send;
the user reviews the attachment and explicitly sends after successful upload.
The camera receives only a transient grant to one file under the non-exported
phone-capture FileProvider's dedicated cache directory. No storage-wide grant.
Camera cancellation deletes that file; import completion (success/failure) deletes
it too. Old capture orphans are swept after 24 h when starting another capture.
Process death does not restore a capture or upload it automatically. Android owns
the external camera activity; its own storage/retention policy is outside O3.

## Mermaid isolation

Show preview renders completed Mermaid blocks locally; streaming blocks remain
source-only. No host renderer, remote script or network request is needed.
The pinned unmodified Desktop artifact is Mermaid 11.17.2, SHA-256
`581ed7d74bd9048d0e3a91363927d72ef22942d7722546b27f7cc29e35390eb8`.
Its MIT license and distribution's bundled notices are packaged in About.

The WebView has no app bridge, file/content access, storage, navigation, downloads,
window creation, permission grants or network loads. Every subresource request
returns an empty local response. CSP defaults to none and restricts scripts to a
fresh per-preview nonce; only the vetted bundled renderer and fixed bootstrap
execute. Diagram text is JSON-quoted and HTML delimiters escaped. Strict Mermaid
security, no HTML flowchart labels, no initialization directives/frontmatter/click
callbacks. Limits: 16,384 source UTF-16 units, 200 lines/edges, 1 MiB generated SVG,
4,000 DOM elements, 8,192 per axis / 16 MP geometry and 5 s rendering. A watchdog
terminates the WebView renderer on timeout; the surface is destroyed on disposal,
backgrounding and zoom surface changes. Rendering requires API 29+ for renderer
termination; older Android versions retain copyable source. Unsupported syntax,
engine failure and limits fall back to source with a localized error. Copy code
is exact; the enlarged viewer supports pinch zoom. This delivers static diagrams,
not clickable diagrams or arbitrary HTML/QML execution.

## Verification and limits

Measured results are recorded below after the final verification run. Fixtures
exercise real bundled JS in system WebView, source escaping, locked settings,
syntax failure, native upload callback integration with a private camera URI,
provider boundaries, speech consent/cancel, and dictation lifecycle state guards.
They do not establish real microphone quality, OEM camera/provider behavior,
Android speech-service availability/downloads, physical-device permission UX,
TalkBack, RTL visual acceptance or live authenticated host upload acceptance.

Host transcription, host speech-model lifecycle and host screenshot capture
remain explicitly out of scope and unavailable. P09 and P16 remain Partial for
these broader parity/acceptance exclusions.

## Connection settings and peer identity

Connections opens OmaBridge administration directly, with corrected explanatory
text in all 20 locales. Peer rows show full IDs, management status and the
peer-advertised `remote_services` `drafts` grant. A valid full ID with that grant
can be selected as the draft authority without typing it. Local `services`
represents the opposite grant direction and never enables draft selection.
Advertisements guide the UI; the remote authority still authorizes each request.
No grant escalation, implicit selection or unsupported administration fallback.

## Delivery verification (2026-11-01)

Java Temurin 21.0.13+9 (`mise where java@temurin-21`),
`ANDROID_HOME=$HOME/.android-sdk`, one Gradle invocation at a time,
`--no-daemon --max-workers=2`, and two JVM processors were used.

- Core debug and release: each 516 cases, 511 passed, 5 explicit live/import opt-in skips, zero failures.
- App JVM debug: 537 cases, 536 passed, 1 external-lab skip, zero failures; 17 additional JVM cases.
- `:app:verifyRoborazziDebug`: 144 goldens passed; six existing images updated for the capture row and license count.
- `:core:lintDebug` / `:app:lintDebug`: zero errors; core 2 warnings, app 109 warnings and 1 hint.
- `tools/import-ot3-strings --check`: 261 imported strings × 20 locales passed.
- Capture/administration/connections resource key parity: 12/30/8 keys respectively × 20 locales passed. About check: 125 packaged notices passed.
- `:app:assembleDebug -Pot3a.requireBridgeSdk=true`: passed.
- **Full instrumented suite is not approved.** The latest completed unfiltered run
  had 128 cases: 117 passed, 9 opt-in skips, 2 failures. These were the existing
  `TranscriptDeviceTest.landscapeImeKeepsMultilineDraftAndReaderReachable` IME
  restoration wait and `OfflineMermaidDeviceTest.sequenceRendersOffline` cold
  renderer completion wait. An earlier complete run had 118 passed/9 skipped/1 failed;
  the Transcript class passed 5/5 in isolation without a production change.
- Nine new instrumented cases passed in earlier full runs: three capture/provider
  fixtures and six real-WebView fixtures. This does not override the failures above.
- Test preparation now explicitly focuses/requests the post-rotation IME; the raw
  engine correctness fixture allows 15 s for cold system WebView loading. The
  production UI's 5 s fallback and strict hung-renderer termination test remain
  unchanged. Clipboard tests wait on a fresh PNG clipboard and verify exact bytes;
  phone geometry waits for a hidden IME; PR fixture uses Compose v2. No assertions
  were removed from the functional checks; transient clipboard feedback was replaced
  with durable clipboard/MIME/byte verification. The full suite after the latest
  test changes remains unverified:
  one attempt received SIGTERM (Gradle) and the emulator exited 137; the subsequent
  smaller-memory attempt stopped after starting the suite. Neither is a pass.

The dedicated API 34 emulator used two cores and 2 GiB RAM, later reduced to
1.5 GiB with a 1 GiB Gradle heap and `--no-parallel`. Final engine was 36.6.11,
serial `emulator-5584`, Vulkan disabled, 1080×2400 / 420 dpi, software IME enabled.
Earlier 37.2 runs also encountered frame waits. No emulator or Gradle process
remained at delivery. The physical phone and alternate checkout were not used.

APK: `/home/node/.cache/o3-android-artifacts/mermaid-capture/o3-android-mermaid-capture-debug.apk`

SHA-256: `be9d38ee03f2b27d5f0c474f8246bab57f20c3923e0d2dae42dba185afdc139b`.
Logs and earlier failure reports: `/home/node/.cache/o3-android-artifacts/mermaid-capture/`.
Next verification: run the complete unfiltered instrumented suite on a stable
host; retain the physical/live-host acceptance exclusions above.
