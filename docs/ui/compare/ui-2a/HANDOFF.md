# UI2a implementation handoff

Branch `feat/ui2-reader-sidebar`, based on `800c33e` (`origin/feat/ui-1`).
PR: https://github.com/avillagran/ot3-android/pull/23, base `feat/ui-1`.
No subdelegation, extra worktree, UI1g cherry-pick, library source change or catalog wiring.
All commits: Andrés Villagrán <andres@villagranquiroz.cl>.
Root owns the Dev Vault update; this child did not write it concurrently.

## Delivered

- Workspace-owned permanent accessible sidebar button in portrait and landscape, independent
  of the child toolbar and loaded/error state. Portrait 280 dp overlay/scrim and landscape
  split both reuse default nebula reveal. The same chat composition/session remains mounted.
- Toggling the panel clears Compose focus and hides the real keyboard, retaining the draft.
- Actual chat sidebar uses Home/RowFrame/ThreadAura and the existing shared ticker and reduced
  motion. Selected rows now show pressed feedback (pressed precedes selected fill).
- Native reader consumes every Rust timeline row. Message sender/time, distinct user surface
  and assistant prose, headings/lists/links/emphasis/quotes, selectable code with Fira Code,
  real horizontal scroll and copy; explicit unknown-role presentation and streaming semantics.
- Activity/reasoning folds use Rust references and `toolInspector.request` → typed call →
  `toolInspector.parse`; sections and copy are read on demand. Changes list Rust file counts.
  Plan/context/error/subagent/secret roles have bounded presentations, with remaining rich
  behavior explicitly listed in README. No protocol/projection/eligibility derived in Kotlin.
- Reverse list with stable Rust ids, saved list state, jump-to-latest. Streaming away from
  latest does not take the reading anchor; rotation/collapse/recreation/one-stream tests.
- Existing six-line composer, DraftRepository, outbox and uncertain-send behavior preserved.
  The verified APK is delivered locally with SHA256. GitHub rejected the optional CI artifact
  step because the PAT lacks workflow scope; it was removed from the unpublished commit.

## Sidebar diagnosis and proof limits

The original production graph already wrapped ThreadScreen in ThreadWorkspace and its
ThreadContent toolbar already invoked LocalThreadSidebarControl. Therefore source review
cannot establish the exact horizontal incident on Xiaomi. The proven gaps were: no portrait
control at all, and horizontal access coupled to the child's toolbar. The fixture manually
invoked that local and explicitly expected no portrait button: it did not establish physical
reachability. New access is owned by the workspace and covered through the real MainActivity
with an unknown environment/unloaded chat, plus a real graph/VM/session/Rust offline fixture.

No Xiaomi operation, inspection or installation. Physical acceptance remains pending.
No live credentials were requested; no live authenticated read/send was performed.
Fixture evidence is synthetic server data through real Android screens, VMs, Room and Rust.
Media and exact source comparison are in README. Fresh same-content desktop capture is
pending; the Android before/after pair is same-content/Catppuccin Latte, while existing
OT3 screenshots and QML 7b5b1de provide structural comparison.

## Verification

See `final-validation.txt`, `ime-validation.txt`, `validation.json`, `lib-build.txt` and
`host-jni.properties`. JNI host rebuilt at 0.3.4/e2ec546, ABI 3, independently reported
libraryVersion 0.3.4; AAR lock/release unchanged, all four Android JNI ABIs packaged.
119 app JVM tests passed (including light/dark goldens and JNI view contract); core 230
passed with four explicit opt-ins skipped (three live tests and Omarchy import).
Final device checks include MainActivity, rotation/draft/scroll/one stream, rich reader
inspector/code scroll/copy, actual IME gap/viewport and legacy ThreadScreen state controls.

The focal IME layout test includes root's 928e868 equivalent, preserving this branch's
keyboard-top geometry fix and the original viewport/gap assertions. Do not overwrite it
with an older complete file while integrating PR24.

## Exact remaining scope

- UI3: model/provider/effort/permission controls, attachments, mentions/skills, queue/steer,
  source-control controls and their typed command/outbox integration. Existing contracts
  are available; they are client work, not reopened lib requests.
- History page authenticated transport/session integration. `history.path/envelope` exist;
  `T3Transport` needs `historyPage(threadId, cursor)` (see LIB_REQUESTS U18 addendum).
- Fork/rewind/quote actions, interactive approvals/questions, document/image/MCP previews,
  rich tables/math, file/thread link routing, automation/agent message auto-folding.
- Rich plan/subagent/context/error/secret workflows; current role coverage is bounded read
  presentation, not complete feature parity. Icons/active text shimmer need visual polish.
- Selectable reveal/effect variants and remaining UI1i motion inventory; default nebula only.
- Fresh identical-content desktop capture, live authorized test-thread validation and Xiaomi.

## Integration boundaries

Root reported bridge work in lib PR11 and Android PR24 (catalog cache/refresh only, not wired
production; publisher and Android binding/enrollment remain pending). NODE is not declared
resolved by this UI increment. No AAR/JNI/dependency changes for that work were imported.
Potential conflicts are additive UI_SPEC/LIB_REQUESTS documentation and the shared IME test;
merge the focus/window/explicit-show fix together with the keyboard-top metrics fix.
UI1g remains separate, untouched, for root coordination.

Emulator shutdown is performed through `scripts/emulator.sh stop` before final delivery.
APK and SHA256 are listed in README and `artifacts/ui2a/`; APK is intentionally untracked.
