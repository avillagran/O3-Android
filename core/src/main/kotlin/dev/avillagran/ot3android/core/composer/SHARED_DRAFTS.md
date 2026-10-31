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

Connections exposes explicit authority selection. The composer Project drafts
panel caches snapshots offline, explicitly saves/adopts text, lists project stash
entries and uses atomic transfer for stash/restore. A live target gets a fresh
backup stash in the same transaction. The editor checks its local version before
adopting a result. Opening or refreshing never replaces active text.

Remaining work (not claimed complete): automatic edit synchronization and Room
legacy migration, explicit resolution controls for all conflict operations,
model/delivery preference payloads, send-ack conditional clear, durable upload
metadata and cross-host attachment transfer. The panel conservatively disables
saving with attachments. It does not synchronize local URIs, outbox identities,
approval recovery or queue edits. Adopt only text explicitly; metadata is not a
resource grant. Authority changes take effect when the composer is recreated.

Validation includes process restart after a lost receipt, retention expiry of an
offline create with an absent remote key, incomplete snapshot pagination and queue
isolation. Two-device host transport and physical-phone acceptance remain pending.
See OmaBridge `docs/shared-drafts.md` at source cd0de3a for the authority protocol.
