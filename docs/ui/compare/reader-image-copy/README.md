# Reader image clipboard

Reference checkout `omarchy-t3-code-ref` at `7b5b1de6db5e24c6b2d77bc0497b361380fead81`:
`ui/qml/T3MessageImage.qml` exposes the copy overlay on hover/focus and a right-click
Copy image action; `T3CopyButton.qml` sends an explicit request, disables busy work,
shows check/error, then resets after 1800 ms. `T3Backend.qml:32` sends the attachment
copy request; `src/clipboard.rs:73–88` loads the original private cached attachment,
normalizes supported non-PNG images to PNG and offers them to `wl-copy`.

Android uses an always-visible 48 dp shared kit copy icon on the actual thumbnail
and viewer so touch users can find the action. There is no extra thumbnail row.
The viewer truncates its filename to leave Copy and Close reachable. All state labels
use ot3's translated catalog. Failure retains the prior clipboard and allows retry.

An explicit press re-reads validated original attachment bytes through Rust
`T3Client.image(id,name,mimeType)`, sharing the existing two-job semaphore with image
loads/decodes. Preview pixels are never reused for copying. No Kotlin URL/network
logic was added. The native-validated MIME is preserved for PNG/JPEG/GIF/WebP/HEIC/
HEIF/AVIF. This intentionally differs from desktop's PNG-only clipboard offer:
Android supports a MIME-described Content URI resolved by the receiving application.
See [Android clipboard framework](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste)
and [FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider).
Receiver format support remains receiver-dependent.

Only `cacheDir/clipboard-images/` is exposed by the non-exported grant-enabled
FileProvider. UUID filenames cannot expose attachment names, IDs or arbitrary paths.
The provider accepts read-only opens. Cache cleanup revokes read grants before
removing old files, runs on startup and each copy, retains at most eight files and
removes files aged at least 24 hours on that next cleanup; there is no expiry timer.
Each file is limited to 10 MiB, so the bound is 80 MiB.
Clipboard offers are temporary and may stop resolving after expiry/eviction or OS
cache reclamation. Unpublished files are deleted on error/cancellation. UI generation
and the VM's current connection are checked immediately before clipboard publication;
lifecycle/connection changes dispose pending action work. Already published clipboard
offers deliberately remain usable across disconnect until cache cleanup/reclamation.
No bytes/bitmap enter saved state,
Room or logs, and previews/viewer opening never copy automatically.

## Verification — 2026-10-26

- Full app JVM: 233/233, zero skips/failures; full Roborazzi verification, lint, normal
  and instrumented APK builds passed. Two preview goldens updated for the overlay
  and inspected in Tokyo Night/Latte.
- JVM clipboard tests round-trip byte-identical payloads and all seven MIME values
  through ClipData URI/ContentResolver; reject writes, sibling cache/files paths and
  traversal; verify non-exported/grant-enabled manifest settings, current URI retention
  at the eight-file bound, age/count cleanup, failed-generation cleanup and prior clip
  preservation. MIME fixtures deliberately test binary preservation, not native format
  validation (already owned by Rust).
- Compose regression proves there is no automatic copy, busy prevents duplicate
  presses, a generation change cancels pending copy without publishing, and an
  explicit subsequent press offers exact original PNG bytes with success feedback.
- API36.1 software emulator: copy suite 2/2 passed initially, then 4/4 combined with
  the two existing RichReaderDeviceTest diff regressions (root's isolated e288745
  action-visibility fix). Visual QA caught an overlay alignment consumed by the kit's
  tooltip wrapper; a direct aligned Box fixed it. Focused JVM 7/7, lint/build,
  two recorded preview goldens and final copy device 2/2 passed after that correction
  and read-only provider delete rejection. The full 233-test run preceded those two
  small final adjustments. A final cleanup deletion-success guard then passed nine
  focused JVM tests (including both preview goldens), lint, APK build and golden
  verification; it adds failure handling before another offer, with no successful-path
  UI change.
- Ten final device PNGs show thumbnail, busy, copied, failure and viewer in Tokyo Night
  and Latte. `device/copy.mp4` is a 10.6-second trimmed normal-speed recording of the
  final copy tests; the Android clipboard overlay visibly changes from the test's
  sentinel text to the PNG. Exact original 1280×800 PNG bytes are asserted via URI
  readback (the displayed preview is sampled to at most 640 px).
- Readback is a programmatic paste in the foreground app fixture using the actual
  Android ClipboardManager and ContentResolver. No personal app or external receiver
  was operated. This proves platform clipboard state/URI readability, not insertion
  through a third-party editor or IME.

Data is synthetic: deterministic debug fixture transport/PNG, actual Home→thread
screens, VM/native timeline projection and Android clipboard/provider. There was no
live NODE/VQ image grant, physical Xiaomi run, or production configuration change.
`device_open` GPU auto failed SIGSEGV; the owned project script software renderer
booted API36.1 and its boot result was awaited. Emulator was stopped afterward.

Still open: Markdown media-file bindings (U30 / lib-t3-rust issue 15), zoom/pan,
video/file previews, live signed-grant/revocation acceptance and physical/receiver
compatibility. Native image reads still have no per-read cancel handle; UI cancellation
drops results and prevents publication while Rust retains its deadline.

## Android 14 integration regression (2026-10-26)

CI and a local API34 run both failed the existing diff rotation test before rotation,
waiting for file controls. Diagnostics showed the dialog open, one getTurnDiff call
and a stuck Loading state; adding timing-sensitive logging could make it pass. The
JNI continuation was observed on a worker thread. The dialog now explicitly publishes
its completed result on Dispatchers.Main.immediate, preserving cancellation and
leaving native parsing unchanged. No timeout increase or automatic retry was added.
The unchanged two RichReader and two image-copy device tests pass together on API34
after this change (4/4). The precise original snapshot scheduling race remains an
inference from those observations; CI verifies the final branch independently.
