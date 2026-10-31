# Shared drafts: blocked on authority and protocol

Checked 2026-10-31. This package does **not** implement synchronized drafts.
No shared store or private RPC is fabricated. Local edit versions in ComposerSession
are not server revisions. No server/lib/Desktop source was changed.

## Evidence

- O3 Desktop `feat/o3-brand`, `55c0ce3e`: `src/drafts.rs::path` writes
  XDG_STATE_HOME/.local/state under the Desktop app's `drafts/`, keyed by SHA256 of
  environment/thread. `src/main.rs::draft_command` handles draft-load/draft-save
  locally, not through T3. QML `T3Backend.qml:335` flushes these local commands.
- Desktop `src/prompt_stash.rs::Store::open` writes `prompt-stash/`, scoped to
  local environment/project IDs, with a local flock and recovery journal.
  `T3ProjectDrafts.qml` invokes these local adapters. This is project-wide on one
  installation, not shared across devices or hosts.
- Android `core/thread/OutboxRepository.kt::DraftRepository` uses Room `drafts`,
  text-only, keyed by local environment/thread. `ThreadComposer.kt` loads once,
  debounces writes and clears text after persisting an outbox command. Local
  model/option preferences live in the composer DataStore. No remote draft I/O.
- T3 reference `t3code-contrib/apps/web/src/composerDraftStore.ts` uses Zustand
  persist/localStorage; promptStashStore.ts uses localStorage key
  t3code:prompt-stash:v2. Server ws.ts and packages/contracts/src have attachment
  resources called draft-workspace-file, not unsent-prompt storage.
- lib-t3-rust `src/api/` and `src/domain/mod.rs` expose no draft/stash CRUD or
  synchronization subscription. The pinned Android v0.3.5 exposes composer
  paths/catalog/fileLink/attachmentReference, not shared draft persistence.
  The binary upload ABI exists, but T3Transport has no upload method yet.

## Smallest cross-host design (proposed, not an existing API)

A service reachable by every enrolled device must be the single authority for
one authenticated user's private drafts. Merely adding a table to each unrelated
T3 host does not meet cross-host sharing. Select an explicit sync authority/account
and advertise it through authenticated T3/bridge enrollment; do not infer user
identity from a display name, repository URL, LAN address or matching project path.
Other hosts proxy that authority or clients contact it through the authorized
channel. No public generic key-value store or broad filesystem-write workaround.

Store revisioned documents keyed by account + stable source-environment UUID +
project UUID + thread UUID; project stash uses account + source environment +
project + entry UUID. All enrolled clients can list documents for all authorized
hosts, including offline source hosts. A target host change is an explicit clone
into a new scope, never reinterpretation of attachment IDs on the new host.
Payload: schema version, plain source text, attachment descriptors with source
host identity, model selection, interaction/delivery preferences and optional
selection. Local composition ranges, file descriptors, content URIs, auth tokens,
pending RPCs and outbox attempts are never shared as editable draft content.

Required operations: get/list/watch since cursor, conditional put/delete and atomic
stash/restore/transfer. Every mutation includes expected opaque server revision
(or explicit create-if-absent) and a durable globally unique mutation ID. The
server checks authorization and revision in the same DB transaction. Return a new
revision and deduplicated receipt. Revision mismatch returns latest document and
conflict; preserve both the unsynced local branch and server content. No automatic
last-writer-wins, wall-clock ordering or silent merge, including delete-vs-edit.

Deletes produce tombstones; an offline stale put cannot resurrect a deleted draft.
Retain tombstones/dedup receipts until every registered client's cursor passed
that revision, or require a fresh full snapshot before an expired cursor can write.
Watch events include scope, revision and mutation ID. On reconnect, consume a
fresh snapshot/resume cursor before replaying conditional writes. An uncertain
mutation is reconciled by its ID or retried byte-identically; never mint a new ID
and overwrite the freshly fetched revision. A late read/save callback applies only
if scope, base revision, local edit generation and mutation ID still match.

Stash/restore is one authority transaction conditioned on source, entry and target
revisions. Restoring into nonempty content must preserve that content as a new
stash entry in the same transaction, subject to explicit quota failure. No partial
clear. Queue edit is a distinct local session with its backup; it must not sync
as the normal thread draft. Shared clear-after-send is conditional on the submitted
revision; a later edit from another device survives. Outbox identity remains local
and durable. Unknown sends block new dispatch until reconciled, not on every device
by sharing the outbox but through normal authoritative thread/command status.

Attachments require durable source-host descriptors/grants. Shared metadata alone
cannot upload bytes to another host. Keep source ownership; when moving hosts,
explicitly copy/re-upload under authorization and finish before committing a
sendable target draft. Do not sync local URI permissions or grant bearer URLs.
Set per-document/attachment/account quotas and private access scopes; bound list
pages, watch replay, payload sizes and conflict copies.

## Work by repository

1. T3 server/contracts: authority discovery/auth, revisioned private storage,
   CAS/dedup/tombstones, watch cursor and atomic transfer transactions above.
   If shared account identity belongs to OmaBridge, add only authority discovery
   and delegated private access there; do not replicate independent host stores.
2. lib-t3-rust: typed draft API/subscription, validation and domain merge/conflict
   projection; U33 UTF-16 reference/trigger/list/history transforms, shared quick
   reply projection, U32/dispatch builders. Publish SDK and host JNI fixtures.
3. Desktop: replace local draft-load/save and prompt-stash adapters with the shared
   repository; persist offline base revision + local branch + mutation ID, consume
   watches and surface conflicts. Preserve existing stash recovery, no blind migration:
   upload legacy files create-if-absent; conflict copies on collision, never overwrite.
   Keep send attempts/approval/question recovery local. Clear shared draft only using
   the revision submitted; use the same atomic transfer protocol as Android.
4. Android P20: inject shared repository and connected upload adapter, migrate Room
   create-if-absent with conflict preservation, wire exported composer into VM/screen.
   No new local-only stash is introduced by P06.

Acceptance must cover two devices on different hosts concurrently editing the same
source draft, offline edit/delete/reconnect, dropped write receipts, cursor expiry,
stash/restore interruption, attachment host transfer and edit-after-send racing a
clear. Current unit tests do not establish any of those distributed properties.
