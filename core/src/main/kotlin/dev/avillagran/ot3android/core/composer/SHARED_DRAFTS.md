# Shared drafts through OmaBridge

The pinned OmaBridge SDK now supplies `shared-drafts-v1`. Draft storage needs an
explicit authority endpoint and a separate `drafts` grant; selecting a catalog or
having a management grant does not grant draft access. No T3 draft RPC is needed.

`SharedDraftRepository` stores a private Room journal per authority (schema 4).
It persists the complete command, original expected revision, anchored mutation
ID and local branch before I/O. Interrupted requests retry unchanged. Snapshot
pagination commits its cursor only after all pages. Snapshot/retention recovery
moves every unsynced command into a durable visible conflict, including absent
keys and uncertain create-if-absent. It never resets a command's base or ID.
Conflicts retain the original branch; the repository exposes explicit resolution
with a new anchored ID. Queue scopes are rejected.

Connections persists an independent explicit authority. Startup polling resumes exact pending
commands without reopening a thread; process-lifetime per-thread workers publish and follow
text, model/delivery choices and durable upload metadata automatically. Legacy local text is
create-only and cannot overwrite an existing shared copy. Structural JSON comparison avoids
feedback writes caused by Rust/Android key order. Authority changes stop old workers.

Local editor branches retain their original CAS base. Only acknowledged own writes or an
actually adopted remote document advance it. Editor generations, pending sends and IME
composition guard callbacks. Keep-local issues a newly anchored conditional operation;
keep-remote archives the local command. Transfers freeze the original source privately
before I/O; that copy never enters the wire request. If the source disappeared,
the conflict panel can preserve the branch as a fresh project stash instead of
issuing an invalid transfer. Both preserve durable recovery material. Cursor
expiry fetches a complete snapshot and surfaces every unsynced branch as a conflict.

Send first synchronizes the submitted payload, then freezes its revision/cursor with the
private send recipe. An accepted send stages a conditional delete with a durable local
send identity, so repeated acknowledgements cannot create extra deletes. Newer remote
revisions conflict and survive. Unknown orchestration sends are never replayed by draft sync.

Shared attachments contain only id/name/sourceEnvironment. The connected source host must
reauthorize and read each bounded resource before it becomes sendable on a receiving device;
no local URI, token or file descriptor enters the shared payload. Restored descriptors remain
bound to the captured connection/authority and editor generation. Explicit restore to another connected thread prepares source-authorized bytes and native
target uploads before staging the atomic bridge transfer. A live target gets a fresh backup.
Canonical attachment references are rebuilt through NativeComposer; a noncanonical reference
refuses transfer, preserving the source. Upload failures never mutate shared documents.
Uploaded resources can remain unreferenced if a later upload/CAS fails; no upload is silently
replayed as an orchestration send. Live authenticated cross-host acceptance remains pending.

Project stash/restore retains the existing atomic backup transaction. Remaining work:
new-chat shared identity/consumer, full metadata migration, real two-device
transport acceptance, whole-process interruption and Doze/OEM background acceptance.
Workers run while the app process lives and resume pending operations on its next startup;
this is not an OS wakeup guarantee. Queue edits, approvals and outbox identities stay local.

Validation includes process restart after a lost receipt, retention expiry of an
offline create with an absent remote key, incomplete snapshot pagination and queue
isolation. Two-device host transport and physical-phone acceptance remain pending.
See OmaBridge `docs/shared-drafts.md` at source cd0de3a for the authority protocol.
