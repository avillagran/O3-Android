# Attachment image touch viewer

The real message attachment viewer keeps its existing Rust binary read, bounded
platform decoder and Copy/Close path. It now supports two-finger pinch around the
gesture centroid, pan while enlarged, double-tap zoom/reset and 48 dp Zoom in/out/
Reset zoom controls. Buttons work with keyboard focus; accessibility exposes only
available zoom actions and bounded horizontal/vertical scroll actions for panning.
The labels come from the pinned v0.3.5 catalog in all 20 locales.

References: ot3 `7b5b1de` T3Panel.qml:922–954 and T3MessageImage.qml show aspect-fit
image enlargement, title, copy and close; desktop has no zoom. T3 Mobile
`680152e27`, FilePreview.tsx:106–118 and MediaImagePreview.tsx:59–66 use
react-native-image-viewing with double-tap zoom enabled and swipe-to-close disabled.
Android's immediate 1–4× range and normalized/clamped pan are explicit touch
adaptations, not constants attributed to QML or the React Native dependency.
No swipe-close, fling, rotation gesture or new animated effect is added.

ContentScale.Fit preserves the decoded image's aspect ratio. Translation is clamped
against the *fitted image*, so letterbox space is not mistaken for image content.
Zoom follows the touched image point. Resize retains normalized image coordinates
and clamps axes whose new viewport cannot pan; transient zero-sized layout does not
wipe that state. Reopening starts at fit. Rotation/recreation saves only three floats
and an ownership stamp, never bitmap/bytes. The saver validates descriptor, canonical
environment/thread and an ephemeral VM/Connection identity token even when restoring
a Bundle. A new connection or new VM/process starts at fit; tokens never enter RPC,
Room, or logs. Only the current connection token is retained in memory.

Pan/zoom does not read or decode another image. Preview/viewer caps remain640/2048,
and read/decode/copy jobs share the existing two-permit semaphore. Decoding stays off
Main and publication is explicitly marshalled to Main. No worker-publication defect
was observed in this increment; the explicit context is a defensive guarantee.
Copy still reads original bytes/MIME only on request, with existing generation and
lifecycle guards. Nothing adds Kotlin URL construction, HTTP fetch or IPC.

Markdown media images (U30), videos/other file previews, original-resolution rendering,
live grant/revocation and external productive clipboard acceptance remain pending.
Animated formats continue to show the decoder's first frame. UI-2 is not complete.

## Verified evidence (2026-10-26)

- Full app JVM265/265 passed, including16 focused image/geometry/clipboard tests.
  All existing Roborazzi goldens verified; lintDebug and app/test APK builds passed.
- API36.1 software emulator5580: ReaderImageZoomDeviceTest4/4 passed in16.005s.
  Actual two-finger touch first crosses Compose's touch slop, then a second recognized
  segment doubles finger distance and verifies2× scale. One-finger pan changes the
  exposed bounded scroll range. Fit/zoom/reset/double-tap do not issue extra image
  reads. Explicit Copy verifies original PNG bytes and MIME through clipboard URI.
  Rotation/recreation retains the scoped normalized view; failure/retry remains usable.
- `device/{tokyo,latte}-{fit,zoom}.png` show the same image and complete controls.
  Latte was recaptured alone after the preceding case's system clipboard overlay
  disappeared; no crop hides UI. `device/zoom.mp4` shows fit, pinch/pan, double-tap/
  reset, explicit copy and close. Tokyo recording case1/1 passed in7.000s;
  isolated Latte evidence case1/1 passed in5.891s. Recording excludes rotation.

These use the real Home→thread→attachment navigation, ViewModel, Rust projection,
platform image decoder and synthetic binary transport. They do not prove live grants,
OEM/physical gesture behaviour or external clipboard consumption. JVM scoping checks
exercise attachment/environment/generation changes and restored-owner mismatch;
connection revocation and external-consumer acceptance remain broader follow-up work.
