# P11 integration slots and binding boundaries

Baseline: Desktop feat/o3-brand 55c0ce3; audit rows 082–084 and P11, plus
T3DiffReview, T3WorkingReview, T3CheckpointRevert and T3GitControls. Android
starts at origin/main 58dbad0, pinned AAR v0.3.5, source d4bf6054. Existing
out-of-v1 prose in UI_SPEC does not exclude scripts or review options here.

## P20 wiring (required before claiming reachable parity)

- Replace the legacy three-argument ThreadDiffDialog call with ThreadReviewDialog.
  The legacy wrapper retains its existing layout/golden contract; the exported
  slot defaults to all review controls. Supply DiffReviewSettings and its shared
  settings owner; narrow surfaces display unified while retaining split preference.
  Wrap, context, file tree, previous/next file, previous/next hunk, folding,
  whitespace refetch and explicit refresh are available. Reads receive copies.
- Register GitControlsSurface / GitWorkingReviewDialog / CheckpointRollbackSurface
  in ThreadScreen/navigation/AppGraph. Those files and Gradle were not edited.
  Supply only the exact authenticated environment's transport. Never silently
  resolve another environment. Pin that connection/generation through each write.
- Keep one GitJournal in credential-protected app-private noBackupFilesDir and one
  service pair for it. Do not log/export/backup its JSON: rollback records contain
  prompt and attachment descriptors. Keep terminal records as consumed IDs.
  Use P20's global per-checkout AND per-thread/composer guard, shared with existing
  outbox, queue, send, branch switching and other Git clients. The local journal
  guards P11 operations, not unrelated senders or another device's Git process.
- Read GitOperations.read from an explicit refresh. Drop late completion by
  target/request generation; do not publish an old host's response into a new one.
  Render native actions, file counts, branch, permissions and scripts. UI choices
  use native scope/revision and one UUID. confirm reauthorizes and invokes native
  buildVerifiedAction after fresh shell/settings/status reads, then reserves before
  sending. Native default-branch confirmation and file selection are preserved.
- Bind NativeGitStackedAction to a resolver returning the pinned authenticated
  T3Client handle for that exact environment. It uses subscribeFn, never call or
  shell, and correlates actionId/cwd/action. Feed phase to P20 state. This adapter
  is supplied in core/git because T3Transport does not expose its private handle.
  With no adapter, commit/push/create-PR/composite controls stay disabled. Pull
  and script terminal.open/write use existing T3Transport.call bindings.
- Script delivery is pending, never process success. Open native terminal using
  the record's planned terminalId. Unknown/partial script execution never resumes
  remaining steps. Observe real native execution, then explicitly reconcile.
- GitReviewReader calls review.getDiffPreview, diffReview.present and vcs.listRefs.
  For a file read it refetches the whole source summary then that file's patch;
  native summary presentation retains unavailable file rows. Pass rename
  previousPath when old servers omit statistics. The native parser's existing
  limits apply (256 files/2 MiB/12,000 lines); errors remain unavailable, never
  truncated into an apparently complete review. Wire refs by target, query, kind,
  cursor and generation; replace each page on demand. No invented local refs.
- Offer the message action only after native checkpointRevert.canPreview.
- Rollback preview receives the actual raw projection, source message ID and live
  authenticated/composer safety. Native reference and recoveryMessage capture
  the exact prompt/attachments before any dispatch. Display the returned gate;
  do not assume permission/provider capability. Preview owns a stable requestId.
- Before confirm, preflight all attachment bytes/availability and reserve composer
  recovery capacity. Pass recoveryPrepared only after that succeeds. Supply fresh
  projection/safety and validated Git target. Restore files requires explicit
  destructive acknowledgement even when the last workspace status was clean.
  Non-null worktree is not isolation proof; the native host enforces real isolation.
- Rollback dispatch acknowledgement means pending. On reconnect invoke observe
  with an authoritative raw projection and captured environment. Native observation
  alone moves to failed/superseded/recovery_ready. Missing messages do not prove
  completion. Reconnect/navigation/process restart must NEVER call confirm again.
- recover invokes CheckpointDraftRecovery.restoreOnce only after correlated native
  completion. Implement atomic durable composer application keyed by recovery ID,
  combining text with native appendPrompt, attachment restoration and preserving
  newer typing. False means definitively not applied and permits recovery retry;
  throw/cancel leaves recovering and requires reconciliation, never blind append.
- Keep journal ownership outside composition. On cold start unknown/pending and
  recovering remain unresolved. On UI detach/cancellation do not delete entries.
  Refresh reads only. Explicit verified native reconciliation may invoke
  journal.reviewed(id, environment, true); this releases a guard without re-sending.
  P20 must reconcile draft recovery separately before marking a rewind reviewed.

## Unbound / deliberately excluded

- Desktop working_review::present/execute/review snapshot registry and sandboxed
  Pierre document renderer are not AAR domain bindings. No native renderer,
  syntax highlighting, complete oversized statistics projection or reviewSnapshotId
  emulation is added in Kotlin. Native bounded patch review is supplied instead.
- Desktop git_operations::validate_result / durable execution observer, terminal
  exit/output projection and checkpoint attachment/draft recovery transaction are
  not shared bindings. Native stream terminal event correlation is implemented;
  full Desktop result-schema validation/toast/link projection stays out. Script
  exit status is never inferred from terminal.write returning.
- Remote status CAS against external clients is absent. Fresh native revision
  checks reduce stale confirmations but cannot eliminate another client's race.
- Shared review-setting persistence, global outbox locks, authenticated handle
  ownership and draft storage are P20 integration, not new library gaps. No
  ThreadScreen/navigation/AppGraph/Gradle edits or placeholder destinations here.

## Acceptance still required

Live host/JNI, all-host permission/capability changes, network cut during stream,
process death during filesystem persistence/restore, physical UI/RTL/accessibility,
large/truncated/renamed working diffs, terminal lifecycle and real PR creation.
No phone, all-hosts checkout, Desktop source or server configuration was touched.
