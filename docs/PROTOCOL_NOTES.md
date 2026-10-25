# Protocol notes

What ot3-Android relies on from a T3 Code server, as seen through lib-t3-rust. Each
section says what was verified live and against which server; everything else is read
from the lib or ot3. The lib owns the protocol (see [ARCHITECTURE.md](ARCHITECTURE.md));
these notes exist so the Kotlin side knows what to expect, not to re-implement it.

## Connection and shell

Verified live on 2026-10-24/25 against T3 0.1.3-nightly.20261024.3630 (orchestration
protocol 2) through lib-t3-rust v0.3.2 (`NativeT3Transport` and `T3Client` directly),
read-only. Counts below are from that server and only illustrate sizes.

### URL + token route

1. **Normalize the URL to an origin**: `https://host[:port]`, or `http://` for LAN hosts.
   The lib rejects anything else with `invalid_endpoint`. `normalizeOrigin` only trims,
   drops path, query and fragment, and picks a default scheme (`http` for loopback,
   private IPv4, `100.64/10`, `.local`, `.lan` and bare names; `https` otherwise); the
   lib validates.
2. **`fetchDescriptor(baseUrl)`**, no credential:
   `{environmentId, label, platform{os, arch, machine}, serverVersion,
   orchestrationProtocolVersion, capabilities{…about 47 booleans}}`. It proves the
   origin is a T3 server and gives the environment identity. With an `environmentId`,
   another server at the URL fails with `t3_environment_mismatch`.
3. **`connect(Bearer(baseUrl, token))`** does no network I/O. A bad token only surfaces
   on the first request, as `T3Exception("unauthorized")` for both 401 and 403.
4. **First request: the shell GET** (`T3Transport.shell()`, `GET /api/orchestration/shell`
   through the lib, which adds the protocol header). This is how the connect screen
   proves a token.

Error codes the UI maps: `invalid_endpoint`; `unreachable` (network); `unauthorized`;
`not_json` (answered, but probably not a T3 origin); `http_<code>` (any other status);
`t3_environment_mismatch`; `not_connected`; `subscription_limit`; `stream_closed`; and
the app's own `missing_token` (no stored token) and `internal_error` (an exception that
is not a `T3Exception`). Anything else is shown as a generic failure with its code.
Codes never carry the token. When the supervisor will not retry (`unauthorized` and the
fatal codes: `invalid_endpoint`, `t3_environment_mismatch`,
`invalid_connection_options`, `missing_token`), the sidebar offers "Edit".

### Shell snapshot

`GET /api/orchestration/shell` → `{schemaVersion: 2, snapshotSequence, projects[],
threads[], archivedThreads[]}`. Here: 16 projects, 216 threads, 0 archived, about
446 KB, so the sidebar list is virtualized. Treat every field as optional.

- **Project keys**: `id, title, workspaceRoot, repositoryIdentity, faviconPath,
  projectIcon, defaultThreadEnvMode, defaultModelSelection, autoPull, scripts,
  createdAt, updatedAt`.
- **Thread keys**: `id, projectId, title, status, activeRunId, latestRunId,
  pendingRuntimeRequest{id, kind, blocking}|null, hasActionableProposedPlan,
  pendingBackgroundTasks[], lastError, lastErrorClass, lastErrorCode, settledAt,
  settledOverride, unsettledAt, snoozedAt, snoozedUntil, pinnedAt, pinOrderKey,
  activeOrderKey, archivedAt, deletedAt, createdAt, updatedAt, latestUserMessageAt,
  latestUserAuthoredMessageAt, lastVisitedAt, lineage{parentThreadId,
  relationshipToParent, rootThreadId}, forkedFrom{nodeId, type}|null,
  modelSelection{instanceId, model, options?}, providerInstanceId,
  providerInstanceHistory[], runtimeMode, interactionMode, branch, worktreePath,
  pullRequests[], linkedPullRequest, itemCount, visibleItemCount`; also seen:
  `activeProviderThreadId, activityRunStartedAt, activityRunStatus,
  autoSettleDisabledAt, blockedOnThreadWait, createdBy, creationSource, goal,
  latestRunCompletedAt, latestRunRequestedAt, latestRunStartedAt,
  latestRunStopRequested, latestVisibleMessage, limitRecovery, opens,
  titleRegeneration, usageLimitResetAt`.
- `status` is an open set. Seen: `idle` (152), `completed` (51), `failed` (8),
  `cancelled` (2), `running` (2), `interrupted` (1).
- `settledOverride`: `"settled"`, `"active"`, `true` or null (seen as a string).
  `relationshipToParent`: `"fork"`, `"subagent"` or null (186 of the 216 threads here
  are subagents).
- Timestamps are ISO-8601 strings (`toISOString()`, UTC with milliseconds). The app
  reads them as epoch milliseconds when it parses a row (ISO-8601 with any offset, or an
  epoch-millisecond number, as the lib's `stamp` does) and never compares them as text.

### Shell stream

`shellStream(afterSequence, completionMarker = false)`
(`orchestration.subscribeShell`).

- **First update**: `start = "snapshot"`, items `{kind: "snapshot", snapshot:
  {schemaVersion, snapshotSequence, projects, threads, archivedThreads}}`; the data is
  nested under `snapshot`. Then `idle = true` once caught up.
- **Events** carry `sequence` and the row, and apply by id:
  - `{kind: "thread.updated", location: "active", sequence, thread{…the full thread
    row, same keys as the shell…}}`. Verified live: 6 events in 40 minutes on one
    subscription; the 3 inspected had `location: "active"`. ot3 removes the row when `location` is anything else (older
    servers send archive-located deltas); ot3-Android does the same and treats a
    missing `location` as active.
  - `{kind: "thread.removed", sequence, threadId}`,
    `{kind: "project.updated", sequence, project{…}}`,
    `{kind: "project.removed", sequence, projectId}`: from ot3's reducer
    (`src/shell_state.rs`), not seen live in the window.
  - Events at or below the held `snapshotSequence` are ignored; events before any
    snapshot wait for it.
- **Metadata-only snapshots.** A `snapshot` item that carries
  `resolvedRepositoryIdentityRoots` only refreshes project identities: its `threads` is
  empty. It must not replace the sidebar, must not count as a reset and must not move
  the cursor ("a resume prefix must not skip replayed events"; ot3
  `src/shell_state.rs:87-107`). Seen live:
  - right after the authoritative snapshot, **in the same batch**
    (`items = [snapshot(threads[216]), snapshot(resolvedRepositoryIdentityRoots[16],
    threads[0])]`, `boundaries = [2]`);
  - again later in a running stream, for a single project
    (`resolvedRepositoryIdentityRoots[1]`, `projects[1]`, `threads[0]`). One
    subscription received 13 `snapshot` items in 40 minutes (2 at the start, 11 later);
    only one of the later ones was inspected, and it was metadata-only.

  Because of the same-batch case, `ThreadUpdate.snapshot` (the batch's *last* snapshot)
  is the empty metadata one and the authoritative snapshot is lost, and the lib's
  cursor moves to the metadata frame's sequence (docs/LIB_REQUESTS.md). The app
  therefore reads every shell update through the `core.transport` adapter
  `ShellUpdates.kt` (`shellSnapshot()`, `shellMetadata()`, `shellEvents()`,
  `shellCursor(previous)`), which reads `update.batch.items` in order, and resumes from
  `shellCursor`, not from `update.cursor`.
- **Repaint** only on `update.repaint` (a server chunk ended or the stream is idle),
  never mid-chunk.

### Resume and reconnect (host-owned)

- The cursor (`shellCursor`, see above) is held in memory and resumes every reconnect
  of a running supervisor. It is also written per environment to DataStore, off the hot
  path: conflated, 1.5 s after it last moved, and once more when the supervisor stops.
  Nothing reads it back yet: the shell itself is only in memory, so a cold start (or a
  supervisor started again after a reset) always takes the shell over HTTP and subscribes
  after that shell's `snapshotSequence`, which is never older than the written cursor.
- On a closed update or a stream that fails to open with a **transient** error
  (`stream_timeout`, `disconnected`, `rpc_connection_failed`, `unreachable`), keep the
  shell and resubscribe with `afterSequence` = cursor after a jittered backoff: 1 s
  base, doubling, cap 300 s, each delay uniform in [half, full] of the step, reset after
  30 s connected.
- A closed update with `error = null` is a cancel (the handle's session was switched or
  dropped, or the host closed it), not a failure: unless the supervisor itself is
  stopping, it reconnects after a fixed 250 ms with the shell and cursor kept and no
  backoff step.
- On `unauthorized`: stop and ask for a new token.
- On `stream_closed` and any other error: drop the held shell and the cursor and start
  over (shell GET, then a stream from its sequence).
- `start == "snapshot"` on a resumed stream means replace, not merge; a snapshot item
  always replaces in the reducer, so this holds without a special case.
- Call `wakeSubscriptions()` on the process's `ON_RESUME` and on every
  `ConnectivityManager` default-network change; a dead socket then closes with
  `stream_timeout` within about 3 s and the rule above reopens it. Streams are stopped
  while the app is in the background and resumed from the cursor on `ON_START`.

### Sidebar (minimum, temporary)

An approximation of the lib's `src/sidebar.rs` (`sort_timestamps`, `sidebar_status`) and
ot3's `ui/js/SidebarModel.js`, kept in `core/shell/SidebarModel.kt` and replaced by
LIB_REQUESTS.md phase 1 item 3 (`sidebar.view`):

- hide `archivedAt`, `deletedAt` and `relationshipToParent == "subagent"` (no nesting
  yet);
- settled = `settledOverride == "settled" || settledOverride == true ||
  (settledOverride != "active" && settledAt != null)`;
- **anchor** (the lib's `inboxAt`) = max(`createdAt`, `unsettledAt`,
  `latestRunRequestedAt`, `latestRunCompletedAt`); never `updatedAt`, which also moves on
  metadata writes;
- **Active**: grouped by project, projects ordered by their newest anchor; inside a
  project pinned first (`pinOrderKey` ascending, then `pinnedAt`), then rows without an
  `activeOrderKey` by anchor descending, then rows with one by that key ascending (as
  text: it is a fractional index), as ot3 orders them;
- **Settled**: a collapsed header with the count; rows by `settledAt ?:
  latestRunCompletedAt ?: latestUserMessageAt ?: updatedAt` descending;
- row status: a `pendingRuntimeRequest` with `blocking != false` and `kind !=
  "auth_refresh"` → needs you; `activeRunId != null || status == "running"` → working;
  `status` `failed` or `error` → failed (`lastError` alone is not: it outlives its run);
  otherwise nothing.

Live: 6 project groups, 7 active rows, 23 settled rows (4 failed).

## Thread stream and sending

Each fact below is marked:

- **[observed]**: seen live on 2026-10-25, read-only, against the same server (T3
  0.1.3-nightly, orchestration protocol 2) through lib-t3-rust v0.3.2, by
  `core/.../thread/LiveThreadTest.kt` on the most recently updated non-subagent thread
  (a thread with a run `running`: 106 messages, 477 turn items, 340 nodes, 9 runs, 7
  runtime requests, all resolved). The report holds keys, counts and enums only.
- **[lib]**: read from the lib's or ot3's source (`src/projection.rs`,
  `src/api/orchestration.rs`, ot3 `src/live.rs`, `src/lib.rs`, `src/write.rs`), not seen
  live.
- **[inferred]**: neither seen nor stated by the lib; what this app assumes.

**Nothing was sent to a live server**: sending and interrupting are verified only against
`FakeT3Transport`.

### Opening a thread

1. **`boundedThread(threadId)`** (HTTP, up to 64 MiB, compact turn items already restored)
   → top level `{hasMoreHistory, historyCursor, latestLocalTurnOrdinal,
   payloadBudgetExceeded, projection{18 keys}, snapshotSequence}` **[observed]**.
   `payloadBudgetExceeded` was `true` on the 106-message thread, with `hasMoreHistory =
   false` and `historyCursor = null` **[observed]**. A snapshot without `projection` or
   `snapshotSequence` is treated as malformed and read again later **[inferred]**. Paint
   from it at once.
2. **`threadStream(threadId, afterSequence = snapshotSequence, completionMarker = false,
   boundedSnapshot = true, compactTurnItems = false)`** (`orchestration.subscribeThread`).
   At most two bounded thread streams per handle, eight streams in total **[lib]**; the
   thread screen uses the environment supervisor's handle and closes its stream when it
   goes away.
3. **First updates after subscribing from `snapshotSequence`** **[observed]**: an idle
   update with no items and `start = null` (cursor unchanged), then
   `event(thread.visited)` at `snapshotSequence + 1` with `start = "resume"`, then idle
   again. `thread.visited` is not in the lib's apply table: it only moves the cursor.
4. **Items**, reduced in order from `update.batch.items` **[lib]**:
   - `{kind: "snapshot", snapshotSequence, projection, ...}`: replaces everything. This
     also covers `start == "snapshot"` on a resumed stream (replace, not merge).
   - `{kind: "event", sequence, event{type, payload}}`: applied when `sequence` is above the
     held one, otherwise ignored.
   - `{kind: "unknown-event", sequence}`: the lib could not decode it; only the cursor moves.
   - `{kind: "synchronized"}`: the completion marker (not requested here).
   `update.idle` means caught up. Paint only on `update.repaint`, never mid-chunk.
5. **Projection keys** **[observed]**: `attempts, checkpointScopes, checkpoints,
   contextHandoffs, contextTransfers, messages, nodes, plans, providerSessions,
   providerThreads, providerTurns, runs, runtimeRequests, subagents, thread, turnItems,
   updatedAt, visibleTurnItems`. `thread` had 25 keys, among them `id, title,
   modelSelection{3 keys}, runtimeMode, interactionMode, projectId, providerInstanceId,
   pullRequests[], lineage{3 keys}, lastVisitedAt`, and **no `activeRunId` and no
   `status`** while a run was `running` **[observed]**.
6. **Message rows** (`messages[]`) **[observed]**: `attachments, context, createdAt,
   createdBy, creationSource, id, nodeId, role, runId, streaming, text, threadId,
   updatedAt`; roles `user` and `assistant`; `streaming` a boolean. Streaming is a repeated
   whole-row `message.updated` with growing `text`, with no delta events **[lib]** (no
   message was streaming during the check). Order by `createdAt`, then array order
   **[inferred]**. The lib's mirror caps `messages[]` at 160 and `runs[]` at 64, dropping
   the oldest by position **[lib]**; this app keeps the 64 for runs but does not cap
   messages, since the bounded snapshot already decides how much history to send.
7. **Working and Stop** use ot3's `active_run_id` (`src/lib.rs`) **[lib]**: the newest
   `runs[]` row in `preparing, starting, running, waiting`; `thread.activeRunId` only when
   `runs[]` is empty. The lib's apply rule also keeps `thread.activeRunId` (set by a
   `run.created|updated` with an active status, cleared when that run leaves it) **[lib]**,
   but the snapshot's `thread` has no such key **[observed]**, so `runs[]` comes first.
   Run statuses seen: `completed`, `running` **[observed]**.
8. **Needs the user**: `runtimeRequests[].status == "pending"` **[lib]** (only `resolved`
   rows were seen **[observed]**); the prompt is on the request or on its latest turn item
   with the same `requestId` **[inferred]**: that join is not in the lib. Read-only for now.

### What the temporary shim applies

`core/thread/ThreadProjectionShim.kt` (until the lib exposes `projection.apply` or a
reduced view, LIB_REQUESTS.md phase 1 item 1) sorts every event the way the lib's
`projection::apply` does **[lib]**:

- **applied**: `message.updated` (whole-row upsert by `id`); `run.created`, `run.updated`
  (upsert into `runs[]`, plus the `activeRunId` rule); `run.background-work-cancelled`
  (patches its run); `runtime-request.updated` (whole-row upsert into `runtimeRequests[]`);
  any `thread.*` whose `payload.id` is this thread (the payload replaces `thread`), except
  `thread.pull-request-link-updated`, which patches the matching link in
  `thread.pullRequests[]`;
- **ignored** (the lib reduces them into rows this view does not read): `turn-item.updated`,
  `plan.updated`, `run-attempt.*`, `node.updated`, `subagent.updated`, `provider-*`,
  `checkpoint*`, `context-*`; the cursor moves, the view does not change;
- **unknown to the lib** (`thread.visited`, a `thread.*` naming another thread, anything
  new): only the cursor moves, as in the lib and ot3 (`src/live.rs`). There is no guessed
  handling of `message.created` or `message.deleted`.

No event ever triggers a refetch. A fresh `boundedThread` is read only when the stream
ends with `stream_backpressure` or `stream_closed`, a snapshot is malformed, or the shim
fails on an item, and at most once per 10 s (a later one waits for the end of the
interval) **[inferred]**: the lib states no such limit. A stream that closes with no error
was cancelled (its handle was connected again or dropped): it reopens from the held
sequence after 250 ms with no snapshot, backing off after three quick cancels in a row
**[lib]** (the transport's contract). Transient errors (`stream_timeout`, `disconnected`,
`rpc_connection_failed`, `unreachable`, `subscription_limit`) resume from the held sequence
with the supervisor's jittered backoff; `unauthorized`, `invalid_thread_id`, `http_404` and
`internal_error` wait for Retry.

### Sending a message

`call("orchestration.dispatchCommand", args)` (scope `orchestration:operate`) with

```json
{"type": "message.dispatch", "commandId": "ot3a-<uuid>", "threadId": "...",
 "messageId": "ot3a-<uuid>", "text": "...", "attachments": [], "createdBy": "user",
 "creationSource": "web", "dispatchMode": {"type": "start_immediately"},
 "deliveryIntent": "auto", "modelSelection": {"...": "the thread's current one"}}
```

- `type, commandId, threadId, messageId, text, attachments, createdBy, creationSource,
  dispatchMode` are required by the lib's validator (`src/api/orchestration.rs`,
  `Command::from_json`); unknown members fail with `invalid_input`. Ids match
  `^[A-Za-z0-9][A-Za-z0-9_:%.-]{0,2047}$`. `text` is at most 120 000 characters, has no
  NUL and is not blank **[lib]**; the app checks the same before anything is persisted.
  `modelSelection` comes from `projection.thread` (else the shell row) and is omitted
  when the thread has none.
- The response `{sequence}` means **accepted**, not finished: the user message and the run
  arrive on the stream **[inferred]**: not seen live (nothing was sent).
- `creationSource` is always `"web"`, what ot3 sends for a typed message (`src/write.rs`)
  **[lib]**. The lib's validator also allows `"mobile"` (`CREATION_SOURCES`), but no live
  server has been seen accepting it, so it is not sent (unverified live). A rejection is
  never retried with another value.
- A rejection's `reason` is free text from the server **[inferred]**: no machine-readable
  field or reason code (LIB_REQUESTS.md); the snackbar shows at most 200 characters.
- **Outbox**: `commandId` and `messageId` are minted once and the exact JSON is stored in
  Room (`outbox`) before sending, from the app scope under `NonCancellable`. Success deletes
  it. `rpc_outcome_unknown` keeps it (`state = "unknown"`) and offers **Resend**, which sends
  the stored bytes again: `describe` gives `orchestration.dispatchCommand` `replay = "id"`,
  "resend the identical payload with the same `commandId`" (lib `docs/ANDROID.md`) **[lib]**. A row still
  `sending` at the next process start becomes `unknown`. While an `unknown` row exists for
  a thread, Send is disabled for it. `rpc_rejected`, `write_not_authorized` (with
  `requiredScope`) and every other code delete it, and the composer keeps the draft.

### Interrupting

`call("orchestration.dispatchCommand", {type: "run.interrupt", commandId, threadId,
runId: the active run (above), holdQueue: true})` **[lib]**, offered while the thread is
working. It is repeatable, so it is not persisted.
