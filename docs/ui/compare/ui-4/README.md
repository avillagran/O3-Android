# UI-4 — requests and plans

## Approval increment (2026-10-26)

The real thread screen renders the first `timeline.view.approvals` entry immediately
above the composer. It displays the native kind, app name, detail, count and provider
choices. Accept/decline are visible; other native decisions live in More approval
options with their warnings. The detail is selectable mono text, bounded at 100 dp.
A non-resumable provider shows the source warning and disables all decisions.

Reference: ot3 `7b5b1de` `ui/qml/T3Approvals.qml:9-72`, UI_SPEC 4.5.1.
No request options, default decisions, pending checks or capability rules are recreated
in Kotlin: the rows come from Rust, and `approvals.command` validates the latest held
projection before the durable sender dispatches anything. A posted notification can
only open this visible card; no background approval action exists.

The session exposes a defensive projection snapshot for native validation. Mutations
run in app scope and persist their stable command id and exact bytes in the outbox.
Unknown outcomes offer the existing retry/discard UI and block fresh mutations.
Accepted responses show Answered while the stream removes the pending request.

## Evidence scope

- JVM integration tests call the actual v0.3.4 Rust builder against a fake transport:
  gone requests, non-resumable providers and explicitly empty option lists are rejected
  before persistence/dispatch; unknown outcomes retry byte-identical with one id.
- Compose tests verify explicit visible choices, session-warning options, disabled
  unavailable providers and accepted-result gating. App/core JVM and lint passed.
- No emulator, physical or live approval evidence yet. The integration owner still has
  the device session; these tests do not close visual or end-to-end acceptance.

## Remaining differences

- Question hardware-number shortcuts, persistent form restoration beyond SavedState, and request lifecycle/error presentation acceptance on device.
- Secret-card native request flow, ready-plan follow-up banner and Edit from here.
- Approval retry/confirmation UI enrichment and device proof through notification deep links.
- Same-theme screenshots and short interaction video on emulator, plus live test-thread
  approval/question verification without operating unrelated user threads.

UI-4 remains open; this increment is not a parity claim.

## Questions increment (2026-10-26)

The first native `timeline.view.questions` entry now renders above the composer, which
is hidden while a question is pending. One page shows at a time, with native option
values, multi-select, custom/initial text, a collapse control, previous/next and the
source 200 ms single-choice advance. All pages submit together through questions.command.
Dismissibility is independent from resumability, as provided by Rust; unavailable
providers cannot answer, but an explicitly dismissible request can still be dismissed.
Question prose uses markdown.blocks and the reader renderer. Page/answers survive
rotation with SavedState. No native pending-request or command rules are reconstructed
from stream events in the UI.

The native questions.command validates command/answer shape; the server remains
authoritative for request freshness and permissions. Unlike approvals.command, it
does not take the projection. The UI sends only explicit native form choices, and
outbox retries reuse the exact mutation id and bytes. Device/live proof remains pending.

Tests cover single-page auto-advance followed by multi-select and one combined answer,
explicit dismissal with no invented answers, unavailable-provider gating with separate
dismissibility, native payload shapes and identical unknown-result retries.

Form UI tests inject renderer-neutral Markdown blocks to avoid native library loading
across Robolectric sandbox classloaders. Production still uses Rust markdown.blocks;
its native contract is exercised by core tests. This is not a combined device proof.

## Active task strip and motion (2026-10-26)

The real composer stack now displays `timeline.view.tasks`: native current task, counts,
ordered steps and duration values. The 48dp touch header opens a bounded 180dp list;
wide layouts show the source segmented progress, with no count/status animations.
T3Tasks.qml and T3Reveal.qml at 7b5b1de are the source. Native icon assets are attributed.

VerticalReveal ports natural child measurement/clipping and the existing reveal shader
on its vertical axis: default nebula (560ms InOutCubic), source spring fallback (520ms
OutBack1.3), and direct instant geometry with reduced motion. No sky is added to chat.
State/scroll are held outside the revealed region. Exact video/GPU acceptance remains
pending; Robolectric exercises the software fallback and reduced-motion layout only.

The ready-plan implementation actions remain a real common-builder dependency, verified
against v0.3.5 and recorded in LIB_REQUESTS §2. No read-only substitute is claimed as a
completed follow-up workflow. Other UI work continues independently.
