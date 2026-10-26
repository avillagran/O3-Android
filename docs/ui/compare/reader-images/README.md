# Reader attachment images

Source: ot3 `ui/qml/T3MessageImage.qml` at `7b5b1de6db5e24c6b2d77bc0497b361380fead81`:
220 × 160 thumbnail, aspect fit, 640 × 480 decode hint, 8-unit radius, enlargement and
image copy menu. Android implements attachment thumbnail/enlargement/retry in the real
Home → thread reader for both prompt and assistant attachments. It uses the existing
Rust v0.3.5 binary `T3Client.image` contract; no URL generation or image HTTP client in Kotlin.

Android decodes bounds first off the UI thread, using power-of-two sampling to keep
both edges ≤640 (preview) or ≤2048 (viewer), hence ≤409,600 or ≤4,194,304 pixels.
Only two load/decode jobs may run concurrently. No bitmap/bytes in saved state,
Room or logs. Only the descriptor is saveable at the stable reader scope; rotation
cannot remove its dialog when the lazy source row leaves composition. Connection
identity changes remove pixels/dialog and late VM reads are discarded. Leaving STARTED
removes image work; native binary calls themselves have no cancellation handle (U30).

Evidence is synthetic: debug PNG + FakeT3Transport binary read, actual Android graph,
VM, Rust timeline projection, Compose timeline and platform decoder. It is not a live
NODE/VQ asset grant, physical Xiaomi acceptance, or remote image compatibility test.
Goldens in `ReaderScreenshotTest/image-preview.*.png` cover Tokyo Night and Latte.

Image clipboard was subsequently delivered in [reader-image-copy](../reader-image-copy/README.md).
Remaining reference differences: Markdown images (U30 binding gap), zoom/pan, file/video previews and full live signed-grant/revocation acceptance. The
viewer is aspect-fit enlargement with Close; no claim of zoom or original-resolution
rendering. Animated image formats show their platform-decoded first frame.


Verification (2026-10-26): full app JVM suite 227/227 passed; core suite 294 total,
289 passed + 5 opt-in skips, then the two additional NativeImageTest cases passed.
The host JNI fixture proves actual WS `assets.createUrl` metadata and same-origin
binary GET without Authorization/DPoP, plus foreign-grant rejection before any GET.
That is local synthetic protocol integration, separate from Android fake-server UI.
Focused image tests cover bounded decode/invalid bytes, failure/retry, fullscreen,
and cancellation/dialog dismissal across a connection generation switch. Full
Roborazzi verification, lint and APK builds passed; the last null-generation guard
also passed all three focused tests and compiled into the device-tested APK.

API36.1 emulator `ReaderImagesDeviceTest`: 2/2 passed (Tokyo Night and Latte), actual
Home/chat navigation, preview, fullscreen, landscape/portrait retention, close, and
zero message dispatches. Four final device PNGs and `device/navigation.mp4` (13.7 s)
are checked in. Captures wait 600 ms for Android window animations after Compose
idle; the video is normal wall-clock UI evidence, not animation-duration measurement.
`device_open` with renderer auto failed SIGSEGV; the project emulator script with
software rendering booted successfully and was awaited. Emulator stopped afterward.
Xiaomi was not operated. No live NODE/VQ grant or image clipboard claim.
