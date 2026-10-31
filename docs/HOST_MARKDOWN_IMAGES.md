# Host Markdown media parity

Desktop reference: `omarchy-t3-code`, `feat/o3-brand`, inspected 2026-10-31.

Desktop uses pulldown-cmark to replace Markdown images with indexed internal links
(`src/lib.rs:markdown_render`). `src/markdown_images.rs` produces stable source-hash
image descriptors. `ui/qml/T3Panel.qml:renderMessage` replaces those links with local
cached `<img>` elements once bytes arrive, sized to the lesser of available width and
original width, preserving aspect. Images retain their position in lists and tables.
The link opens a fit-to-window image popup, with a filename/alt heading and copy action.
It does not implement gesture zoom; Android keeps its existing pinch/pan/double-tap viewer.
Raw HTML is escaped, so HTML `<img>` is not an additional image source.

`src/images.rs` handles three source classes:

- Host paths, including `file:` URLs: `workspace_path` then
  `Connection::media_image(thread, path)`.
- Public HTTP(S): a separate credential-free client, no proxy, at most three redirects,
  a 20-second timeout and 10 MiB response limit.
- `data:`: base64 PNG/JPEG/GIF/WebP only, 14 MiB encoded and 10 MiB decoded limits.

The authenticated host channel is `assets.createUrl` with resource
`{"_tag":"media-file","threadId":"…","path":"…"}`. Rust obtains the grant using the
connected environment's session and fetches its same-origin `/api/assets/` URL without
forwarding session credentials. HTTPS gateways and nondefault ports use that same
connection origin. There is no local host file read on Android, SSH read, new endpoint,
or credential in a generated URL/log.

Server evidence: `t3code-nightly-3512/apps/server/src/ws.ts` supplies
`thread.worktreePath ?? project.workspaceRoot`; `assets/AssetAccess.ts` resolves relative
paths against it. Absolute paths remain host absolute, subject to the same server grant
policy, canonical file identity and authorization used by Desktop. A relative source
must not be joined to the phone's working directory or to the HTTP gateway URL.

## Android implementation

The pinned v0.3.5 AAR already exports `T3Client.assetBytes(resource,maxBytes,timeoutMs)`.
`NativeT3Transport.mediaImage` uses it with the exact media-file resource and a 10 MiB
limit. Rust retains grant creation, same-origin checks and authenticated transport;
Android adds Desktop's MIME/signature allowlist and its existing bounded bitmap decode.
No lib-t3-rust checkout or binary was changed/rebuilt. The new Kotlin path adapter is a
port of Desktop's small `workspace_path` function, not a filesystem resolver: relative
paths remain relative for the server. The previously requested dedicated U30 API would
centralize this adapter, but is not required to use the existing binary channel.

`MarkdownBlocks` consumes Rust image blocks and spans. It reuses `AttachmentImage`,
`ImageRead`, `ReaderImageDialog`, clipboard and connection-generation/lifecycle guards.
Inline images become their own phone-width line, preserving surrounding text order.
Tables keep their existing horizontally scrollable cells. Thumbnails are bounded to
640 pixels per edge and fullscreen decodes to 2048; originals over 40 million pixels
are refused before allocation. The original compressed bytes are not retained in UI
state. Public HTTP uses no environment transport, credentials, proxy or cookie store.
The manifest permits HTTP media to match Desktop; HTTPS is still certificate-validated.

Video/audio attachments use the same dialog owner, reader callback, generation guards
and read semaphore, with a native VideoView/MediaController. The signed attachment
resource is bounded to Desktop's 50 MiB. Playback files live only in the app's private
cache and are deleted on dialog disposal/retry/backgrounding. Platform codecs determine
which containers play. Markdown images pointing to video/audio remain invalid images,
as on Desktop.

## Capabilities

| Capability | Desktop | Android |
| --- | --- | --- |
| Remote host through authenticated HTTPS gateway/port | Signed media-file grant | Same connected Rust asset channel |
| Absolute host path | Server-authorized exact host file | Supported; never a phone file |
| Relative path / `./` / `../` | Server thread worktree, otherwise project root | Passed unchanged to same server resolver |
| `file:///` / percent escapes / query and fragment | Decoded host path; query/fragment stripped | Same adapter, JVM tests |
| Unsupported schemes, network path, home shorthand, controls | Rejected by Desktop adapter | Same rejection policy |
| HTTP(S) | Credential-free, max 3 redirects, 10 MiB, 20 s | Separate public client; same bounds |
| Base64 data images | PNG/JPEG/GIF/WebP; encoded/decoded bounds | Same formats/bounds |
| PNG/JPEG/GIF/WebP host/attachment | MIME and magic validated | Same allowlist, bounded platform decode |
| HEIC/HEIF/AVIF host/attachment | Qt plugin dependent; ftyp validation | Android OS codec dependent; ftyp validation |
| SVG, HTML img, blob URLs | Not supported by native loader | Rejected / escaped |
| Animated GIF | Static Qt Image | Static bitmap |
| Width / aspect | Available/native width, preserved aspect | Lesser of phone/cell and original width; preserved aspect |
| Click to enlarge | Fit popup | Existing fullscreen fit viewer |
| Zoom / pan | No dedicated gesture zoom in inspected popup | Existing pinch, pan, double tap, accessible controls |
| Loading / error | Loading state; alt link remains; popup error | Spinner, alt/name, unavailable state and explicit retry |
| Caption / alt / title | Alt is placeholder and popup name; no separate caption/title | Same alt/name; localized empty-alt fallback |
| Multiple images / reference syntax | Parser descriptors and indexed links | Rust blocks/spans; independent reader states |
| Lists / quotes / headings / tables | Rich text positions | Nested blocks/cells, ordered text runs around images |
| Linked images | Internal image action | Image opens shared viewer |
| Image attachments | 220×160 thumbnails, popup, copy | Existing thumbnails, same viewer/copy |
| Copy image | Normalizes supported raster formats to PNG, 16 MP clipboard limit | Existing private content URI with actual MIME; 16 MP limit; touch-visible action |
| Video/audio attachments | Separate signed attachment resource, 50 MiB, Qt playback controls | Same grant/bound, native playback controls; codec acceptance unverified |
| Image cache | Private immutable attachments, 128 entries/128 MiB; fresh host reads | No persistent read cache; sampled viewport bitmaps; fresh reads on fullscreen/retry |
| Lifecycle / connection changes | Viewport-gated loading and cache ownership | STARTED-only reads; generation checks, disposal and media cleanup |
| Security limits | 10 MiB / 40 MP images; no session on signed GET | Same bounds and grant origin rules; no environment credentials on public HTTP |

## Verification boundaries

The real message at position 4447 contains eight absolute-host images (seven PNGs and
one JPEG). Its content was read through the linked environment, not by opening host
files. The first exact path is a JVM path fixture and a native signed-grant test case.
The phone was not inspected, installed or controlled. No live host-image grant was
requested with another client's saved credentials.

The workspace has the pinned Android AAR and bridge JNI but lacks the desktop
`libs/host/linux-x86_64/libt3_jni.so`. Native Rust-domain/grant JVM tests therefore skip;
they are included for CI with host JNI. No binary rebuild was performed to hide this
limitation. Public HTTP, source/path rules, Compose media placement/retry/viewer and
screenshot goldens can run without that library. Playback codecs and remote live byte
access still require device/integration acceptance.

Final local validation (Java Temurin 21, 2026-10-31):

| Check | Result |
| --- | --- |
| `:core:testDebugUnitTest` | 345 discovered: 175 passed, 170 skipped, 0 failures/errors |
| `:app:testDebugUnitTest` | 378 discovered: 362 passed, 16 skipped, 0 failures/errors |
| `:app:verifyRoborazziDebug` | Passed; 94 stored goldens including two new host-image snapshots |
| `lint` | Passed for core and app |
| `:app:assembleDebug -Pot3a.requireBridgeSdk=true` | Passed; arm64-v8a and x86_64 bridge JNI present in APK |

Skipped tests include absent host JNI domain/grant tests and existing opt-in live/import
checks. Those skips are not evidence of native grant or live-server acceptance.
