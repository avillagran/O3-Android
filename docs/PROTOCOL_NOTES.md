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

### Sidebar (lib-t3-rust v0.3.3 `sidebar.view`)

Since v0.3.3 the sidebar is the lib's `sidebar.view` (`core/shell/SidebarView.kt`): the
host passes each environment as `{id, name?, shell?, state?}`, where `shell` is the shell
the app holds (the HTTP snapshot plus the stream events, reduced by the temporary
`ShellReducer`), and the lib returns ot3's sections in order. Rules that used to be
listed here (hidden rows, settled, inbox anchor, order, the ten details) now live in the
lib (`src/sidebar.rs`, `src/sidebar/view.rs`) **[lib]**. What the app relies on:

- every thread row must have a string `title` and a unique valid `id`, otherwise the
  environment's state is `malformed` and it contributes no rows **[lib]**;
- archived and deleted rows are dropped; subagent rows only appear nested under their
  parent (`depth`, `subthreadParentKey`) when it is expanded **[lib]**;
- `sectionCounts` count top-level rows; the section lists include nested children
  **[lib]**.

Live (2026-10-25, 233 shell threads, 203 of them subagents, 16 projects): `active` 36,
`working` 0 (not grouped), `snoozed` 0, `settled` 24 rows; `counts` working 1, done 3,
waiting 0, failed 0; 16 project identities **[observed]**.

## Pairing

Marked as in the thread section below: **[observed]** was seen live on 2026-10-25 against
T3 0.1.3-nightly.20261025.3669 (the same machine's server, on the LAN address the link
named) through lib-t3-rust v0.3.2, by one run of `core/.../pairing/LivePairingTest.kt`;
**[lib]** is read from lib-t3-rust v0.3.2 (`src/pairing.rs`, `src/connection_catalog.rs`)
or the T3 CLI bundled in that release, and checked against local fixtures
(`NativePairingTest`, `PairingTargetsTest`); **[inferred]** is what this app assumes.

### The link

- `t3 pair --label <label> --ttl 5m` prints `Pairing with <server label> (<origin>).`, a
  QR code, `Pairing URL: <link>`, `Token: <code>`, `Expires: <ISO time>` and a note
  ("This URL works only from this machine's local network. From elsewhere, re-run with
  --tailscale or use T3 Connect.") **[observed]**.
- The link is `<origin>/pair#token=<code>`: `http://192.168.1.177:8643/pair#token=…` with a
  12-character code, no query **[observed]**. The CLI builds it as `new URL(origin)`, path
  `/pair`, any `token` query parameter deleted, and the hash set to
  `URLSearchParams([["token", code]])`, so the code is form-encoded in the fragment and never
  reaches a server in a request line **[lib]** (CLI source). `--tailscale` gives the same shape
  on the tailnet's HTTPS name **[lib]**.
- The QR code encodes the link (error correction M, quiet zone 2) drawn with half-block
  characters in the terminal's text colour **[lib]**: on a dark terminal it is light on
  dark, which the scanner must read inverted.
- Accepted link forms are those of `connection_catalog::pairing_target` **[lib]**: the
  code from the fragment's `token`, else the query's `token`, or a separately typed code,
  which wins; a hosted link (`https://t3.codes/connect?host=<origin>#token=<code>`) points
  at its `host` parameter, but only when there is a code; `ws`/`wss` map to `http`/`https`;
  a bare `host:port` gets `https`. Path, query and fragment are dropped from the origin;
  user info, port 0 and other schemes are `invalid_endpoint`, and a code with control
  characters or over 16 KiB is `invalid_pairing_code`. The app calls it as the domain
  function `connectionCatalog.pairingTarget` (`core/pairing/PairingTargets.kt`).

### The exchange (`pairBearer`)

1. **Descriptor first, no credential**: `GET /.well-known/t3/environment`. Its
   `environmentId` must be a UUID (`invalid_t3_environment_id`) and equal to the expected
   one when given (`t3_environment_mismatch`); only then is the code sent **[lib]**. The app
   reads the descriptor once to show the server before the user confirms, and passes its
   `environmentId` to `pairBearer`, so a different server answering by then is refused
   **[inferred]**.
2. **`POST /oauth/token`**, `application/x-www-form-urlencoded`, `Accept:
   application/json`, no `Authorization` **[lib]**, asserted in `NativePairingTest`:
   `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`, `subject_token=<code>`,
   `subject_token_type=urn:t3:params:oauth:token-type:environment-bootstrap`,
   `requested_token_type=urn:ietf:params:oauth:token-type:access_token`, and
   `client_label`, `client_device_type`, `client_os` when set. The app sends
   `ot3-Android · <device model>`, `mobile`, `android`.
3. **Answer**: `token_type` must be `Bearer` and `access_token` a token of at least
   8 characters without whitespace (`unsupported_pairing_token` otherwise); `scope` and
   `expires_in` are passed through **[lib]**. The lib's operation returns `{accessToken,
   tokenType: "Bearer", environmentId, descriptor, scope, expiresIn, connected}`; with
   `connect: true` it also connects the handle. The app pairs with `connect: false` on a
   short-lived handle and lets the environment's supervisor connect with the stored token
   **[inferred]**.
4. The issued token is a long-lived bearer session: it worked for `connect(Bearer)` and
   the shell GET right away (16 projects, 227 threads) **[observed]**. The one-time code is
   spent: `t3 auth pairing list` showed no active link afterwards **[observed]**. The
   session shows in `t3 auth session list --json` as `{client{label, deviceType}, connected,
   expiresAt, issuedAt, lastConnectedAt, method: "bearer-access-token", scopes, sessionId,
   subject}`, with the label `ot3-android-pair-test` **[observed]**; the run used that label
   for both the link and the client, so which of the two names the session is not settled.
   It was revoked with `t3 auth session revoke <sessionId>` right after the run.

### Errors

None was seen live (one successful pairing). From the lib, checked against the fixture
**[lib]**: `invalid_pairing_code` (empty, whitespace or control characters, over 4096
bytes), `invalid_pairing_client` (a blank or over-256-byte label or OS, a device type
outside `desktop|mobile|tablet|bot|unknown`), `invalid_endpoint` (not an origin),
`unreachable`, `not_json`, `malformed` (descriptor without `environmentId`),
`invalid_t3_environment_id`, `t3_environment_mismatch`, `pairing_rejected` (any non-2xx
answer to the exchange, including 401 and 403: an unknown, used or expired code),
`unsupported_pairing_token`. Input errors are found before any request. The app groups
them into `PairingFailure` (link, no code, bad code, rejected, other server, incompatible,
unreachable, not T3, internal, other) and shows one sentence for each.

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
7. **Working and Stop** come from `timeline.view` `threadState` since v0.3.3, which applies ot3's `active_run_id` (`src/lib.rs`) **[lib]**: the newest
   `runs[]` row in `preparing, starting, running, waiting`; `thread.activeRunId` only when
   `runs[]` is empty. The lib's apply rule also keeps `thread.activeRunId` (set by a
   `run.created|updated` with an active status, cleared when that run leaves it) **[lib]**,
   but the snapshot's `thread` has no such key **[observed]**, so `runs[]` comes first.
   Run statuses seen: `completed`, `running` **[observed]**.
8. **Needs the user**: `timeline.view` `approvals` and `questions` since v0.3.3 (pending
   `runtimeRequests[]` joined with their `approval_request` / `user_input_request` turn
   item) **[lib]** (only `resolved` rows were seen **[observed]**). Read-only for now.

### How events are applied (lib-t3-rust v0.3.3)

Since v0.3.3 the session hands each update's `event` objects (`{type, payload}`) to the
lib's `projection.apply` in one call and builds the screen with `timeline.view`
(`core/thread/ThreadTimeline.kt`); there is no Kotlin event table any more. Types the lib
does not know (`thread.visited`, a `thread.*` naming another thread) change nothing and
only the cursor moves, which the lib reports (`ThreadUpdate.cursor`) **[lib]**.

No event ever triggers a refetch. A fresh `boundedThread` is read only when the stream
ends with `stream_backpressure` or `stream_closed`, a snapshot is malformed, or the lib
refuses the projection (`timeline.view` → `malformed`), and at most once per 10 s (a later
one waits for the end of the interval) **[inferred]**: the lib states no such limit. A
stream that closes with no error was cancelled (its handle was connected again or
dropped): it reopens from the held sequence after 250 ms with no snapshot, backing off
after three quick cancels in a row **[lib]** (the transport's contract). Transient errors
(`stream_timeout`, `disconnected`, `rpc_connection_failed`, `unreachable`,
`subscription_limit`) resume from the held sequence with the supervisor's jittered
backoff; `unauthorized`, `invalid_thread_id`, `http_404` and `internal_error` wait for
Retry.

Live (2026-10-25, a running thread, bounded projection of 1.6 MiB: 104 turn items, 346
nodes, 20 messages): `timeline.view` 45 rows (15 assistant, 5 user, 20 activity, 5
subagents), each with a `rev`; about 35 ms per `timeline.view` and 50 ms per
`projection.apply` on node (x86_64, host library) **[observed]**.

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
