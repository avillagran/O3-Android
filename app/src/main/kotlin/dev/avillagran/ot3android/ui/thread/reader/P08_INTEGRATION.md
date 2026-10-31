# P08 transcript integration

Baseline: Desktop `55c0ce3e81e5aaf348e263e8896cceec6d52a4e6`, audit 2026-10-31;
Android starts at origin/main `6959f82`. Native lock v0.3.5 source
`d4bf6054b4480efdcd8a436cb66cb7f2e23664b1` is authoritative.

## P20 wiring

`TimelineReader(..., actions = TranscriptActions(...))` adds optional capabilities.
Absent callbacks hide their controls. Find, previous/next occurrence, position slider,
current row count and the loaded-window search work without graph changes. Matches
are identified by row ID and occurrence offset; a streamed revision does not select a
new occurrence or resume following latest. Search excludes secret/tool/error rows.
The slider follows reverse-list indices, newest at zero. It suspends following latest;
the existing jump button restores it. Existing saved row ID/offset remains authoritative.

- `quote(text)`: insert into the composer through P06's native reference/edit contract;
  do not send immediately or overwrite an existing draft. Currently offered on user
  messages only because assistant terminal eligibility is missing (below).
- `openThread(childThreadId)`: navigate in the **same selected environment** using P20's
  route. Resolve access/scope through the graph. No stop button is offered without a
  native eligibility contract. Parent/sibling discovery remains with navigation.
- `followUpPlan(readyPlan, feedback, newThread)`: the exact ready artifact comes from
  `view.planView.ready`, matched to row plan ID AND thread ID. Call existing
  `NativeComposer.followUp` / `planWorkflow.followUp` then `attachSourceRef` on a
  `message.dispatch` command; preserve the source thread for a new-thread operation.
  Recheck eligibility in the scoped action holder before dispatch. P20 owns command IDs,
  model, runtime, dispatch mode, outbox and connection generation. No Markdown inference.
- `planOperation`: immutable snapshot for that plan ID; show busy, cancel, explicit retry
  for known failures, or uncertain delivery. Never expose retry while uncertain. P20 owns
  operation cancellation/reconciliation; callbacks cannot undo an already sent command.
- `secrets = remember(environmentGeneration) { TranscriptSecrets(selectedEnvironmentTransport) }`: direct typed
  `secrets.answerRequest` (save/decline), replay never. **Do not use an outbox, analytics,
  JSON tracing, exception rendering or a logging transport decorator.** Scope the reader
  and transport together by environment/thread/generation; dispose on switch/reconnect.
  P20 must gate the callback on current connection and orchestration:operate permission.
  The card accepts only pending local requests. The native API owns request validation
  and delivery references, including file delivery; Android never resolves secretRef.
  Retain this controller across row/dialog recreation; its in-memory outcome-only map
  suppresses duplicates and contains no secret. Reconcile through the live projection
  after process death/reconnect before creating another attempt-capable controller.

Secret input lives only in `remember`, inside a SecureOn dialog (blocks captures/recents),
uses password keyboard/masking and disables autocorrect. It clears on submit, dismissal,
background, disposal, request identity/status change and recreation. CharArray and JSON
payload clear in finally; JVM/IME/native String copies cannot be guaranteed zeroized.
Failures render fixed resources and suppress replay, even when delivery is uncertain.
No secret value is placed in composer, reading position, saveable state or clipboard.

Context dividers render the native handoff/compaction active state and endpoint instance/
model identifiers; missing token/provider display-name metadata is not fabricated.
Secret outcome cards render native saved/declined/ended states without showing values.

## Unbound work (intentionally not emulated in Kotlin)

- **U18**: history.path and history.envelope exist, but authenticated historyPage transport
  plus history.mergeView are absent. No paging/merge UI or Kotlin precedence algorithm.
  Native prerequisite must cover duplicate page revisions, live wins, cursor exhaustion,
  stale generation rejection, never replacing approvals/queue/control state, and anchors.
  Therefore streaming-while-paging and duplicate-history-revision acceptance are blocked.
- **Terminal response actions**: timeline.view supplies streaming and forkRunId, but no
  commentary/showMessageActions eligibility. No new assistant quote/fork/merge-back,
  rewind, resend/edit/delete actions. Existing assistant copy/date behavior is retained.
  Native eligibility must distinguish a completed commentary item from terminal response.
  Fork/merge-back also require authoritative thread workflow target/run metadata and
  scoped management dispatch in P20; no inferred target or raw command recipe here.
- **U35**: prompt/quote/file/thread links need shared destination/routing bindings.
  Existing web/GitHub link rendering remains; no Kotlin URI classifier or prompt-link
  rewrite. Quote callback is plain composer text, not a fabricated t3-context link.
- Subagent stop and parent/sibling discovery require native action eligibility and scoped
  graph/navigation integration. Native childThreadId navigation is exported above.

## ThreadScreen wiring after main integration

ThreadScreen now passes the transcript actions through ThreadContent. User quotes append
plain quoted text to the existing draft without sending or replacing attachments; quoting
is unavailable while editing a queued message or sending. AppScreens routes child-thread
navigation through the same environment. Find and the position slider remain available.

The ViewModel retains the secret controller for the connection lifetime, including screen
recreation. Each answer checks thread identity, current connection, live screen state and
orchestration:operate permission, then rechecks connection ownership after authorization.
No outbox or logging adapter is used. A changed/unavailable controller clears private input
and closes its dialog, even if the request ID stays unchanged.

Plan implementation/new-thread controls remain unbound: v0.3.5 followUp/attachSourceRef
cannot build the full interaction-mode + dispatch sequence with durable retry identities
(see docs/LIB_REQUESTS.md, common submission builder). Do not expose a partial dispatch
as a successful plan implementation. Other U18/U35/terminal eligibility gaps above remain.
Live-host secret delivery, physical IME/security and accessibility acceptance are unverified.
No AppGraph, Gradle, Desktop, shared lib, all-hosts checkout or phone changes in this wiring.
