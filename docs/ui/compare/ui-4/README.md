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

- Paged questions with multi-select/custom inputs, 200 ms auto-advance and native command.
- Secret-card native request flow, ready-plan follow-up banner, tasks and Edit from here.
- Approval retry/confirmation UI enrichment and device proof through notification deep links.
- Same-theme screenshots and short interaction video on emulator, plus live test-thread
  approval/question verification without operating unrelated user threads.

UI-4 remains open; this increment is not a parity claim.
