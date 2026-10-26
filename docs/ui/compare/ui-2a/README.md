# UI2a — native timeline and permanent sidebar access

Bounded increment on `800c33e`. This is **not global OT3 parity**.

Source-confirmed access gap: `ThreadWorkspace.kt` only provided a nullable control in
landscape; the child toolbar had to invoke it. `ThreadWorkspaceTest.kt` manually invoked
the local and explicitly asserted no portrait control. This only tested the fixture's
cooperation, not MainActivity's reachable control. The workspace now owns a persistent,
48 dp minimum button sharing the existing chat toolbar action area, independent of loaded/error state or child toolbar.
The portrait panel overlays the reader at 280 dp with a dismissible scrim; both orientations use the existing nebula reveal.
Chat composition and its ViewModel/session stay mounted when the panel changes. Opening or closing the sidebar clears Compose focus and hides the IME without erasing the draft.
This architectural weakness is proven from source; it does not establish exactly what
happened on Xiaomi. No physical device was inspected, operated or installed.

The real sidebar uses HomeScreen → ThreadCardRow → RowFrame → ThreadAura, inside the
same Ot3Theme ticker as the reader. Selected styling is supplied by the production graph's
`LocalSelectedThreadKey`. Press now takes precedence over selection, making touch feedback
visible on the currently selected chat too. Shader/default reveal, working comet, attention
glints, state transitions and reduced motion reuse UI1i implementations.

The reader consumes every row in `ThreadView.timeline.timeline`, keyed by Rust id.
It draws the supplied neutral blocks, not parsed Markdown or reconstructed protocol.
Activities and reasoning summaries fold; inspector bodies are fetched with Rust's
`toolInspector.request` and validated with `toolInspector.parse`. No sends are needed.
Changed files show Rust's path/kind/addition/deletion fields. Unknown roles remain explicit.
Reverse layout and stable ids preserve the reading anchor; jump-to-latest is explicit.

## Comparison inventory

Source reference pinned `~/Work/omarchy-t3-code-ref` at `7b5b1de`.

| Reference | Android implementation | State / remaining differences |
|---|---|---|
| `ui/qml/T3Panel.qml:1655-1753` user surface, assistant prose | `ui/thread/TimelineReader.kt:70` | Implemented; user width is capped at 85%, attribution displayed; automation/agent auto-fold pending |
| `ui/qml/T3Panel.qml:1893-1905` sender/actions | `ui/thread/TimelineReader.kt:80` | Sender/time/copy implemented; fork/rewind/quote pending |
| `ui/qml/T3ActivityBlock.qml:1-62` | `ui/thread/TimelineReader.kt:117` | Folded activity and inspector sections implemented; source icons, active text shimmer, per-entry drilldown polish pending |
| `src/timeline.rs:92`, `src/markdown/blocks.rs:1` (lib 0.3.4) | `ui/thread/TimelineReader.kt:168` | Headings/lists/quotes/links/emphasis/fences/selection/copy implemented; table rich layout, math typesetting, inline file/thread navigation and image/document preview pending |
| `ui/qml/T3ThreadRow.qml:179-211` | `ui/kit/ThreadRows.kt:200`, `ThreadAura.kt:22` | Real chat sidebar selected fill/aura shared with Home; selected-row touch feedback corrected. Hover previews/actions remain desktop-specific |
| `ui/qml/T3Reveal.qml` | `ui/navigation/Ot3Reveal.kt:26` | Default nebula on landscape and portrait implemented; selectable effects pending |
| `ui/qml/T3Composer.qml` | `ui/thread/ThreadScreen.kt` composer | Existing draft/outbox and six-line editor preserved; model/options/attachments/queue/steer/source controls pending UI3 |

History cursor metadata is present. Paging is still pending client transport/session integration:
`history.path` and `history.envelope` exist, but `T3Transport` has no authenticated generic
GET/history page operation. No fake load button is shown. See LIB_REQUESTS U18 and addendum.

Fixture and production evidence must be read separately. The debug fixture uses the real
navigation graph, screens, VMs, Room and JNI Rust; FakeT3Transport supplies deterministic
shell/snapshot/inspector responses. It is not a live server or authentication validation.
Production MainActivity test uses an unknown environment deep link and exercises unloaded
access. Live authentication/send and Xiaomi acceptance remain pending.

## Verified evidence and exact limits

Android paths in the table are relative to `app/src/main/kotlin/dev/avillagran/ot3android`.
The original horizontal path **does** invoke the local in ThreadContent's toolbar. Source
inspection alone cannot explain the exact missing-control incident on Xiaomi. What is
proven is the missing portrait control and fixture coupling to a child toolbar. The new
workspace-owned path was tested in real MainActivity while unloaded, plus the real graph
fixture while loaded/error/rotating. Physical Xiaomi acceptance is still pending.

- [Before/after, identical rich content and Catppuccin Latte](comparison-reader.png).
  Before uses the exact three UI sources from `800c33e` with the enriched current offline
  fixture; after uses this PR. Source files were restored immediately after the baseline build.
- [Portrait reader](after-portrait-reader.png), [drawer](after-portrait-sidebar.png),
  [inspector](after-portrait-inspector.png), [real IME](after-portrait-ime.png).
- [Landscape sidebar](after-landscape-sidebar.png), [collapsed](after-landscape-collapsed.png),
  [real IME](after-landscape-ime.png), [inspector](after-landscape-inspector.png).
- [Portrait reveal/effects/reader/inspector/scroll](portrait-final.mp4).
- [Final portrait IME and draft-preserving sidebar control](portrait-ime-final.mp4),
  captured from the delivered APK after final validation.
- [Landscape reveal/effects/IME](landscape-final.mp4) and
  [landscape reading/expansion/jump](landscape-reading.mp4).
  Landscape screenshots/videos use debug Activity `landscape=true`, because the driver's
  orientation command returned `unconfirmed` and reverted. Actual rotation/restoration is
  covered by ThreadWorkspaceDeviceTest, not inferred from these static launches.
- Existing [OT3 reference](../../reference/ot3-timeline-shelves-composer.png) and pinned QML
  are structural comparison sources, not a newly captured desktop with this exact fixture
  or theme. The before/after Android pair is same-content/theme; a fresh identical-content
  desktop capture remains pending. Light/dark native reader goldens are committed.

`final-validation.txt` records the final combined successful run (including eleven emulator
tests). The post-root IME rerun is in `ime-validation.txt` (2/2). All device operations target emulator-5580; no Xiaomi inspection or installation,
no authenticated live server and no live send. Fake server data is explicitly synthetic,
while the graph, VMs, ThreadSession, Room and Rust rendering/inspector validation are real.

Commands used (JAVA_HOME=~/.local/jdk, ANDROID_HOME=~/.android-sdk):

```sh
OT3A_REQUIRE_HOST_JNI=1 ANDROID_SERIAL=emulator-5580 ./gradlew \
  :app:verifyRoborazziDebug :core:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:connectedDebugAndroidTest -Pandroid.injected.device.serial=emulator-5580 \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.avillagran.ot3android.MainActivitySmokeTest,dev.avillagran.ot3android.ui.navigation.ThreadWorkspaceDeviceTest,dev.avillagran.ot3android.ui.navigation.VerticalWorkspaceLayoutTest,dev.avillagran.ot3android.ui.thread.RichReaderDeviceTest,dev.avillagran.ot3android.ui.thread.ThreadScreenTest
```

119 app JVM tests passed, including the light/dark goldens and pinned Rust view export
contract; core: 230 passed, four explicit opt-ins skipped (three live credentials and the
Omarchy import task). NativeSmokeTest passes with libraryVersion=0.3.4, ABI 3; rebuilt host
provenance is in `lib-build.txt` and `host-jni.properties`. No missing-JNI skip was used.
MainActivity access test uses the real persisted OFF animation preference and restores it;
animated sidebar evidence uses AUTO in the fixture. Focal layout test also incorporates
root's 928e868 focus/window/explicit-IME request while retaining gap/viewport assertions.

Debug APK: `artifacts/ui2a/ot3-android-ui2a-debug.apk` (not committed).
SHA256: `d9076fc8a2fc43ea823e9fbadfa2cf06e4f9cfe0941180baaf37704000e189ee`.
This APK includes the debug fixture, not release signing. The optional CI APK-upload step
was removed before publication because GitHub rejected the current PAT without workflow
scope. The tested APK is delivered locally with its checksum; no new credential was needed.


## Root review: compact workspace chrome (latest APK)

Review found that the first UI2a control added a separate 48 dp row above the 56 dp
chat toolbar. This repeated the vertical-space problem. The workspace-owned icon now
occupies a reserved action slot in that existing toolbar; it is still rendered by the
workspace above the drawer/scrim, remains reachable when unloaded, and does not overlap
Stop. Source icons are ot3's panel-left/panel-left-close, with 48 dp touch targets.
A device assertion requires the control and title to overlap vertically, preventing
restoration of a second header row. Root repeated 2 workspace JVM tests, compilation,
11 focal emulator tests (including real IME, MainActivity unloaded access and
rotation/draft/scroll/one stream) and lint successfully. No golden assets changed.

Latest raw captures: [portrait reader](compact-portrait-reader.png),
[portrait drawer](compact-portrait-sidebar.png),
[landscape sidebar](compact-landscape-sidebar.png),
[landscape collapsed](compact-landscape-collapsed.png).
Latest actual reveal recordings: [portrait](compact-sidebar-portrait.mp4) and
[landscape](compact-sidebar-landscape.mp4). Both use the same real graph/offline-rich
fixture; landscape is explicitly launched as before. The earlier comparison/recordings
above document the initial implementation with the separate control strip, **not**
this final compact chrome. Root used device_screenshot for inspection and the exact
launcher returned by device_open for capture. Keyboard geometry is verified by the
repeated instrumented test; earlier keyboard videos predate the compact toolbar.

New APK: `artifacts/ui2a/ot3-android-ui2a-compact-debug.apk`.
SHA256: `efd7e3afcd159458acad9e1913798912d11e41fae4492b5b8cdc5088299502b5`.
This supersedes the earlier d9076f APK for this branch. Emulator stopped and automation
session closed. Xiaomi, live server and aesthetic approval remain unverified.
