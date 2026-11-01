# P06 exports for P20

Current consumer update (v0.3.7, 2026-11-01): `ThreadScreen.ConnectedComposer` now uses native completion/recall and plan gates/builders, durable Chat command sequences and acceptance-gated navigation. The historical handoff below is superseded for these paths by [chat-actions.md](../../../../../../../../../../docs/verification/chat-actions.md). Separate background delivery has no matching audited Desktop/native binding and is omitted.

Baseline: origin/main 9e078ca; audit Desktop 55c0ce3e. This is a partial package,
not reachable from ThreadScreen until P20 wiring. No ThreadScreen/ViewModel,
navigation/AppGraph/Gradle edits. Existing composer behavior remains intact.

## Ownership and callbacks

- `ComposerParity(snapshot, capabilities, onEdit, onRequest)` renders source text,
  selection/IME-preserving editor, removable upload chips, native-projected reference
  chips, completion choices and explicit touch actions. The reference chips are a
  separate strip; inline object replacement/rendering awaits U33. Source Markdown
  remains editable. Expanded editor has a send button; hardware/IME rules reuse
  ComposerEditor. No global shortcuts or clipboard reads on mount.
- Create one `ComposerSession` per stable environment/project/thread/queue-message
  identity. Publish snapshots synchronously on the UI dispatcher after edits/actions.
  onEdit must first check captured scope/current surface, call session.edit and
  publish; typing and IME updates must not be debounced. Persist separately.
  For externally restored/transform results publish text AND selection together.
- `ComposerRequest` captures expected snapshot. Revalidate current scope/version
  before actions and after async native work. `session.replace` implements this
  check for transforms and recall/quick-reply insertions. Never apply a stale read
  to a newly selected thread. Selection offsets are UTF-16; invalid surrogate
  boundaries are rejected. No Markdown, trigger or list regex is ported to Kotlin.
- Undo/redo affects text/selection only, up to 100 entries; upload cancellation and
  removal are independent. Do not present this as complete Desktop attachment undo.
- `NativeComposer` delegates existing path/catalog/link and plan projections to Rust.
  Fetch host search entries/provider catalog for the selected host/cwd first.
  U33 trigger/replacement/list/recall policy is provided by pinned v0.3.7 and consumed by ConnectedComposer; Android does not parse those transforms.
  Native-returned history text and configured quick replies can use
  `ComposerTextChoices`; keep a scratch snapshot when entering/leaving recall.
  Quick reply language/detection/custom-list editing still needs shared projection
  and P03 preference wiring; the choices composable does not invent those policies.
- `ComposerAttachmentImporter` spools an explicitly selected content URI into a
  private temporary file, enforces 50 MiB while reading (not trusting metadata),
  and deletes it on completion/failure/cancellation. Bind `ComposerBinaryUpload` to
  the SAME connected host's `T3Client.upload(fd,name,mime,size)` on IO; fd is borrowed
  only during the call. No native client is created by the UI. T3Transport currently
  hides that handle and lacks upload; P20 must add its transport method or an adapter.
  Staging crash cleanup and account-wide disk quota belong to P20 lifecycle/storage.
  Progress is indeterminate because the existing binary ABI exposes no byte progress.
- Picker result holds origin snapshot; a thread change drops it. Restore/rotation
  while the picker is open cancels the pending selection safely (choose again).
  For a multi-file selection, validate origin once and enqueue the batch on UI;
  sequential beginUpload calls must use the current snapshot after each insertion.
  Mint a fresh UUID per attempt, publish beginUpload, then call importer; finishUpload
  rejects another scope or a removed/nonpending attempt. Errors become Failed chips;
  remove and select/paste again retries explicitly. Never auto-retry upload after
  reconnect. Failed/pending chips gate send. Catch errors and cancellation in owner;
  never show a spinner forever. Persist interrupted uploads as failed, not pending.
- Reference click must select its native-projected source range in the editor;
  no regex reconstruction. Do not remove text references with attachment metadata
  until a native transform updates both atomically. Completions need the same stale
  snapshot check; model-command completion should open model options, not insert text.
- `ComposerProjectDrafts` exports refresh/restore/delete with entry revision tokens.
  Listing/transactions depend on the shared store in core/composer/SHARED_DRAFTS.md.
  No local replacement is provided. Host errors remain visible; no fabricated success.
- PlanBuild/ImplementPlan/PlanFeedback must use authoritative plan/provider gates,
  NativeComposer planView/followUp/attachSourceRef and durable command receipts;
  the production owner revalidates provider/permission and ready-plan state, then uses these helpers. A Plan/Build draft preference is local until the durable mode/message command sequence is accepted.
- Send capabilities are opt-in. Default sends and all alternate delivery modes must
  use a durable attachment-aware outbox command. Current MessageSender.send is
  text-only: NEVER route an attachment snapshot through it. OppositeOnce applies
  once without changing persisted default. A separate Background action remains unbound and hidden. SendAndNew uses the native atomic launch initialMessage; navigation waits for acceptance. Plan implementation in a new thread checkpoints launch then message, because the pinned InitialMessage has no sourcePlanRef field. Existing outbox retry is
  byte-identical. Publish commandState during sending/unknown; preserve original
  text/attachments until receipt reconciliation. Do not clear based on UI click.

## Remaining acceptance

JVM reducer tests cover IME locks, UTF-16 selections, stale async results, upload
failure/removal/completion, thread/queue identity, unknown sends and attachment-safe
undo. Existing editor tests cover selection restoration/hardware keys and queue
surface state. They do not prove IME/OEM behavior or rotation on a device with the
new chip surface. No phone/emulator touched. No live upload/host, shared-draft,
end-to-end queue edit or reconnect/send integration acceptance is claimed.

P06 remains incomplete until host/lib work, P20 wiring and that acceptance pass.
