# Requests to lib-t3-rust

This file collects what ot3-Android needs from
[lib-t3-rust](https://github.com/avillagran/lib-t3-rust): defects found while building
and running its Android bindings, contract gaps, and missing API. ot3-Android never
re-implements protocol logic in Kotlin (see [ARCHITECTURE.md](ARCHITECTURE.md)); when
the lib lacks something, the request goes here and is resolved in the lib, then
consumed through the AAR.

Each dated section records the lib version and commit it was checked against, how it
was checked, and every finding with a proposed fix. Findings are closed by a lib
release, not by workarounds in this repository.

## 2026-10-24: v0.3.0 (4b55060), ABI 3

Checked on the development machine (x86_64 Linux, JDK 21.0.11, Kotlin 2.3.21
K2JVMCompiler from the Gradle cache, NDK 28.2.13676358) with
`scripts/fetch-or-build-lib.sh` and the lib's own `tools/build-android`,
`tools/package-android`, `tools/test-jni` and `tools/test-kotlin-jni`. The lib checkout
stayed clean at the tag throughout.

**Status:** every finding below still applies to v0.3.1 (`17d3cb7`). Fixes for findings
1 to 6 are in progress upstream for v0.3.2, on the lib branch
`feat/android-bindings-fixes`, together with a workflow that compiles the Kotlin
sources on each pull request and publishes the AAR and its SHA-256 on each `v*` tag.
Update, 2026-10-25 UTC: that branch has since been merged (PR #8) and tagged v0.3.2
(`26e67a7`, with a GitHub release). A first look at v0.3.2, not yet the target: both
host fixtures pass; `classes.jar` contains `T3Api` and `T3Asset`; `assetBytes`,
`projectFavicon` and connection `options` have wrappers; JNI codes are mapped to
`T3Exception`, except `image()`'s `missing_image_bytes`, which is still an
`IllegalArgumentException`; the release has the AAR and `.sha256` but no host library
(finding 7). The findings are closed when this repository moves to v0.3.2 and
checks them again.

### What works

- `tools/build-android arm64-v8a x86_64 armeabi-v7a x86`: all four ABIs built from the
  tag; every `LOAD` segment aligned to 0x4000.
- `tools/package-android`: the rewritten `T3Client.kt` compiles against
  `android-36/android.jar` with **no errors and no warnings**; the AAR is complete.
  `T3Api.kt` together with `T3Client.kt` also compiles against `android.jar` cleanly.
- `tools/test-jni` (Java fixture `T3Native.java`): `JNI_PASS`.
- `KotlinFixture.kt` (compiled with `T3Client.kt` and `T3Api.kt`): `KOTLIN_JNI_PASS`.
- `StreamFixture.kt`, once finding 1 is corrected in a scratch copy:
  `KOTLIN_STREAMS_PASS`, 10 of 10 runs, no hang; the wake probe always cut the silent
  socket inside the asserted 2900..3400 ms window. Both Kotlin fixtures also pass with
  org.json 20250517 instead of 20180813.
- Smoke through the AAR's `classes.jar` and the host `libt3_jni.so`: `abiVersion 3`,
  `libraryVersion 0.3.0`, `apiVersion 1`, `functions 10`, 18 `operations` (the 16 ABI
  2 and transport ops plus `call` and `describe`), `pollFields` with `boundaries`,
  `idle`, `completionMarker` and `errorMessage`; `describe()` lists the 10 functions.

### 1. `tools/test-kotlin-jni` does not compile (blocker for the Kotlin fixtures)

```
bindings/android/tests/dev/avillagran/t3/StreamFixture.kt:150:13: error: 'val' cannot be reassigned.
            complete += chunks.accept(last)
            ^^^^^^^^
bindings/android/tests/dev/avillagran/t3/StreamFixture.kt:150:22: error: assignment type mismatch: actual type is 'List<List<Any?>>', but 'MutableList<List<Any?>>' was expected.
            complete += chunks.accept(last)
                     ^^
```

`complete` is a `val MutableList<List<Any?>>` and `chunks.accept(...)` returns
`List<List<Any?>>`. Because `List<List<Any?>>` is itself a `List<Any?>` (covariance),
both `plusAssign(element)` and `plusAssign(elements)` apply, so the compiler falls back
to `complete = complete + ...`, which a `val` rejects. Even if it resolved, the
single-element overload would add the whole batch as one chunk.

Proposed fix (verified: with it, `StreamFixture` passes):

```kotlin
complete.addAll(chunks.accept(last))
```

Also worth adding: a compile of the fixtures in CI (the same K2JVMCompiler command as
`tools/test-kotlin-jni`), so Kotlin sources are never tagged uncompiled.

### 2. `T3Api` is not in the AAR

`T3Api.kt` (the generated typed wrappers over `call`) lives in
`bindings/android/tests/` and `tools/package-android` compiles only `T3Client.kt`, so
`classes.jar` has no `T3Api`. The app would have to copy generated protocol code to use
it, which this repository does not do.

Proposed fix: generate `T3Api.kt` into `bindings/android/kotlin/dev/avillagran/t3/`
(update `tests/api_describe.rs` and the path in `tools/test-kotlin-jni`) and compile it
in `tools/package-android` with `T3Client.kt`. It already compiles against
`android.jar`.

### 3. `assetBytes` and `projectFavicon` are unreachable from an app module

`docs/ANDROID.md` says `T3Client` has no wrapper for them and that one is "a one-line
`T3Native.image` call". `T3Native` is `internal`, and `internal` is Kotlin-only: it is
unreachable from Kotlin source in another module, such as ot3-Android consuming the
AAR, although the bytecode is public. An app cannot make that call without
going around the Kotlin visibility rules. `T3Client.image(id, name, mimeType)` only covers the `assets.createUrl` image
path and requires bytes (`missing_image_bytes`), so it cannot express
`{found:false, sizeBytes:0}` either.

Proposed fix: public wrappers in `T3Client`, for example

```kotlin
class T3Asset(val status: JSONObject, val bytes: ByteArray?)
fun assetBytes(resource: JSONObject, maxBytes: Long? = null, timeoutMs: Int? = null): T3Asset
fun projectFavicon(cwd: String, path: String): T3Asset   // bytes == null when found == false
```

each passing `{"op":"assetBytes",...}` / `{"op":"projectFavicon",...}` to
`T3Native.image` and decoding `result[0]` like `image()` does.

### 4. `connectBearer` and `connectRelay` take no `options`

ABI 3 accepts `options` (`requestTimeoutMs`, `connectTimeoutMs`, `poolIdleTimeoutMs`,
`maxBytes`; listed in `capabilities.connectionOptions`), but the Kotlin wrappers have no
parameter for them; `connectRelay` also fixes `clientId` to `"t3-mobile"`. The app can
send the JSON through `execute`, but then builds protocol requests by hand.

Proposed fix: `options: JSONObject? = null` on both, and `clientId: String = "t3-mobile"`
on `connectRelay`.

### 5. `upload` reports a bad descriptor as `IllegalStateException`

`T3Client.upload(-1, ...)` throws `java.lang.IllegalStateException:
host_file_unavailable` from JNI, not a `T3Exception`. A host that catches
`T3Exception` for stable codes misses it.

At the raw JNI level this is an intentionally tested contract: the Java fixture
expects exactly that exception (`bindings/android/tests/dev/avillagran/t3/T3Native.java:32`).
The C++ adapter (`bindings/android/cpp/t3_jni.cpp`) throws `IllegalStateException` with
the codes `invalid_input`, `host_file_unavailable` and `native_allocation_failed`, and
`T3Client.image()` adds its own `requireNotNull(...) { "missing_image_bytes" }`
(`IllegalArgumentException`).

Proposed fix: keep the raw JNI contract and map in the Kotlin wrapper:
`host_file_unavailable`, `invalid_input` and `native_allocation_failed` (from
`IllegalStateException`) and `image`'s `missing_image_bytes` become
`T3Exception(code)`, so `T3Client` callers only catch `T3Exception`. Extend the
Kotlin fixture to assert each mapping and leave the Java fixture's raw
`IllegalStateException` assertion as it is.

### 6. No v0.3.0 GitHub release

The latest release is v0.2.0 (ABI 2). `scripts/fetch-or-build-lib.sh --release` cannot
fetch ABI 3 until the v0.3.0 release publishes `lib-t3-rust-android-v0.3.0.aar` and
its `.sha256` (ideally after finding 1 is fixed, so the release's fixtures compile).

Proposed fix: a tag-triggered release workflow (`on: push: tags: ['v*']`) that builds
the four ABIs, runs the host fixtures, packages the AAR and publishes as release assets
`lib-t3-rust-android-v<ver>.aar`, its `.sha256`, and the host
`libt3_jni-linux-x86_64.so` with its own `.sha256` (finding 7). CI here then needs no
lib checkout: `--release` (with `libs/lib-t3-rust.lock`) fetches and verifies
everything. In progress upstream for v0.3.2 (see the status above).

### 7. Releases do not publish the host JNI library

JVM unit tests in this repository load a desktop `libt3_jni.so` (see LIB_BUILD.md,
"Host JNI library for JVM tests"). Only a lib checkout can produce it today, so every
CI job that runs JVM tests must check out lib-t3-rust at the tag and have Cargo, CMake
and a JDK, even when the AAR itself comes from a release. The v0.2.0 release and the
v0.3.2 release workflow publish only the AAR and its checksum.

Proposed fix: build the host library in the release workflow exactly as
`tools/test-jni` does (`cargo build --release --locked`, CMake of
`bindings/android/cpp` into `target/jni-host`) and publish it as
`libt3_jni-linux-x86_64.so` plus `libt3_jni-linux-x86_64.so.sha256`, from the same
commit as the AAR. `scripts/fetch-or-build-lib.sh --release --host-jni` can then
download it instead of building it.

## 2026-10-24 — Capabilities requested for Android phase 1 (not defects)

These are missing pieces of API, not bugs. Each already exists in Rust (in the lib or
in ot3) but cannot be reached from Kotlin, so phase 1 (connect, live sidebar, read a
thread with streaming, send, approve and answer, choose a model) would otherwise have
to re-implement protocol logic, which [ARCHITECTURE.md](ARCHITECTURE.md) forbids.
Checked against v0.3.1 (`17d3cb7`): `src/`, `bindings/android/describe.json` (116
entries: 99 unary functions, 13 typed streams, 4 composed functions) and
`T3Client.kt`. `src/` is identical at v0.3.2, so every request also stands there. ot3
references are to omarchy-t3-code v0.2.22 (`191c1fc`).

Already delivered by v0.3.1 and therefore not requested: typed streams and `subscribe`
by `fn`, `cursor`/`start` in `pollSubscription`, the `ThreadStream` facade, the typed
registry coverage of unary T3 RPCs, the 64 MiB item limit for bounded-snapshot thread
streams (item 2) and stream probing through `wakeSubscriptions` (item 5).

None of the eight is on the ABI: `describe` has no `projection`, `sidebar`, `shell`,
`reconnect`, `connection_catalog`, `history`, `composer`, `approvals`, `questions`,
`queue` or `models` entry, and `call` only sends T3 RPCs. A pure, connection-free form
(for example `{"op":"domain","fn":"<module>.<fn>","args":{...}}`, listed in `describe`
next to the RPC functions, same error envelope) would serve items 1 to 7.

**Blocking for phase 1: items 1, 3 and 8.** Items 2, 4, 5, 6 and 7 are nice to have:
phase 1 can work around them (no compact snapshots, commands built from the
`describe` argument schema, a Kotlin retry loop, URL + token entry, no older history)
at some cost in behaviour or bandwidth.

### 1. Apply thread events: `projection.apply` or a reduced thread view (blocking)

- Proposed: `projection.apply` `{projection, event}` → `{projection, changed}`. Better,
  because a projection can be tens of MiB and would otherwise cross JNI on every event:
  a stateful reduced view owned by the subscription, for example
  `subscribe` `{"fn":"orchestration.subscribeThread","args":{...},"reduce":"thread"}`
  so that `pollSubscription` returns `{"items":[{"kind":"view","rev":N,"view":{...}}],
  "cursor":...}` with the lib keeping `{projection, cursor}` between polls.
- Why: the thread stream delivers a `snapshot` and then `event` items; turning them
  into the current thread (messages, turn items, runs, active run) is protocol logic.
  `ThreadStream` (v0.3.1) splits each batch into a snapshot and the events to apply,
  but leaves the applying to the host.
- In Rust today: `projection::apply(&mut Value, &Value) -> bool`
  (`src/projection.rs:8`), with the per-list caps and the field stripping that keep the
  projection bounded; `orchestration.getThreadProjection` (`call`) only fetches a
  projection. ot3 runs this loop in `src/live.rs`.

### 2. Restore compact stream snapshots (nice to have)

- Proposed: apply `bounded_snapshot::restore` to `snapshot` items of a thread stream
  opened with `acceptCompactTurnItems:true` before they are queued (or expose it as
  `bounded_snapshot.restore` in the pure form above).
- Why: with compact turn items the server omits local items from `turnItems` and ships
  them in `projection.visibleTurnItems` (`turnItemsOmitLocalVisible:true`); without the
  restore the timeline misses those items. `ThreadStream.thread(...)` defaults to
  `compactTurnItems = true` and `docs/ANDROID.md` subscribes with
  `acceptCompactTurnItems:true`, but nothing restores stream snapshots, so the default
  path hands the host an unrestored snapshot. Until then the app subscribes with
  `compactTurnItems = false`.
- In Rust today: `bounded_snapshot::restore` (`src/bounded_snapshot.rs:30`), only used
  by `boundedThread` (`src/bounded_snapshot.rs:68`, HTTP, one response).
- The 8 MiB per-item queue cap is no longer a problem: since v0.3.0 a
  `subscribeThread` stream with `acceptBoundedSnapshot:true` accepts a 64 MiB item
  (72 MiB queue, at most 2 such streams per handle; `Limits::of` in
  `src/ffi_stream.rs`, `stream::item_limit` in `src/stream.rs`). Nothing to request.

### 3. Sidebar view and shell reducer (blocking)

- Proposed: `shell.reduce` `{state, item}` → `{state, changed}` (or a
  `"reduce":"shell"` subscription, as in item 1) and `sidebar.view`
  `{shell, now, prefs:{grouping, returned, filter, query, expanded}}` →
  `{sections:[{key, title, rows[]}], counts}`; plus `sidebar.status`,
  `sidebar.metadata` and `sidebar.sortTimestamps` as individual functions.
- Why: the live sidebar (phase 1) needs the shell snapshot kept current from
  `orchestration.subscribeShell` items, then rows with status, attention reason,
  pinned/settled/snoozed state, ordering, project grouping and subagent nesting. All of
  that is decided in Rust or ot3 JS today; porting it to Kotlin would duplicate it.
- In Rust today: `sidebar::sort_timestamps`, `sidebar::sidebar_status`,
  `sidebar::metadata` (`src/sidebar.rs:6`, `:19`, `:37`) in the lib; the shell
  reducer `shell_state::reduce` (`src/shell_state.rs:83`), the row builders
  `normalize` and `snapshot` (`src/lib.rs:690`, `:826`) and the ordering and grouping
  (`ui/js/SidebarModel.js`, `ui/js/ProjectModel.js`) only in ot3. The ot3 parts would
  move into the lib first.

### 4. Command builders that return `dispatchCommand` arguments (nice to have)

- Proposed: pure functions returning the ready `args` for
  `call orchestration.dispatchCommand`: `composer.message` (ids, defaults, delivery
  mode), `approvals.command`, `questions.command`, `queue.command`,
  `models.catalog` (picker rows from `server.getConfig`), `models.runtimeModeCommand`,
  `sidebar.threadAction` / `sidebar.command`, and `projects.command` (for
  `projects.mutate`).
- Why: approve and answer, model choice and the composer are phase 1 screens. The
  host can build these JSON commands from the `describe` schema, but the validation
  rules (only a still-pending request, only an offered decision, valid runtime modes)
  live in the builders.
- In Rust today: `approvals::command` (`src/approvals.rs:61`), `questions::command`
  (`src/questions.rs:37`), `queue::command` (`src/queue.rs:31`), `models::catalog` and
  `models::runtime_mode_command` (`src/models.rs:21`, `:7`), `sidebar::command` and
  `sidebar::thread_action` (`src/sidebar.rs:63`, `:71`), `projects::command`
  (`src/projects.rs:13`); typed wrappers `Command::{queue, answer, approve,
  runtime_mode, sidebar, thread_action}` (`src/api/orchestration.rs:516-538`),
  `MessageDispatch` (`src/api/orchestration.rs:345`) and
  `ProjectMutation::from_command` (`src/api/projects.rs:119`), all Rust-only. There is
  no `composer` message builder; `call` already validates `message.dispatch` strictly
  and rewrites legacy attachment links (`src/api/orchestration.rs:1161`,
  `composer::legacy_attachment_text`).

### 5. Reconnect policy (nice to have)

- Proposed: `reconnect.nextDelay` `{failures, connectedForMs}` → `{delayMs}` and
  `reconnect.transient` `{error}` → `{transient}`.
- Why: the host owns the reconnect loop (per the lib's contract), but which errors are
  transient and the backoff curve (1 s base, 300 s ceiling, jitter, reset after 30 s
  connected) are protocol policy and should be the same as ot3's.
- In Rust today: `reconnect::transient` (`src/reconnect.rs:26`) and
  `reconnect::Backoff` with `next_delay`/`connected`/`reset` (`src/reconnect.rs:33`).
  Wakeup probing is already on the ABI (`wakeSubscriptions`, `T3Subscription.wake()`,
  `ThreadStream.wake()`), so no separate probe operation is requested.

### 6. Pairing-link parser (nice to have)

- Proposed: `connectionCatalog.pairingTarget` `{raw, code}` → `{httpBaseUrl,
  pairingCode}`.
- Why: phase 1 connects by pairing link; the link forms (direct, hosted, host + code)
  and their validation are protocol knowledge. `pairBearer` takes the already split
  `httpBaseUrl` and `pairingCode`.
- In Rust today: `connection_catalog::pairing_target` (`src/connection_catalog.rs:5`);
  the catalog functions `view`, `mutate` and `register` (`:73`, `:107`, `:213`) could
  follow, with storage staying in Kotlin.

### 7. History paging (nice to have)

- Proposed: `history.page` `{threadId, cursor}` as a `call` function that fetches the
  page and returns the converted envelope (or `history.path` and `history.envelope` in
  the pure form).
- Why: older messages beyond the snapshot window. The path can be built by hand and
  fetched with `request`, but converting the page rows needs Rust.
- In Rust today: `history::path` and `history::envelope` (`src/history.rs:3`, `:20`).
  `describe` has no history function.

### 8. Kotlin wrappers for the remaining operations (blocking)

- Proposed: `T3Client` methods for `fetchDescriptor(httpBaseUrl, environmentId?,
  options?)`, `pairBearer(httpBaseUrl, pairingCode, environmentId?, client?, connect,
  options?)`, `boundedThread(threadId)`, `requestOk(method, path, body?, protocol)`,
  `rpcWithTimeout(tag, payload, timeoutMs)`, `capabilities()` and `request` with
  `method` and `body` (POST).
- Why: phase 1 connects by pairing (`fetchDescriptor`, then `pairBearer`) and reads
  `capabilities` to gate features. Through `execute(JSONObject)` the app would assemble
  operation JSON by hand, which is the protocol knowledge the adapter exists to hold.
- In Rust today: all of them are ABI operations (`src/ffi.rs`: `request` and
  `requestOk` take `method`, `path`, `body`; `fetchDescriptor`, `pairBearer`,
  `boundedThread`, `rpcWithTimeout`, `capabilities`). `T3Client.kt` at v0.3.1 (and
  v0.3.2) wraps none of them, and `request(path, protocol)` is GET-only.
