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

**Status:** every finding below still applies to v0.3.1 (`17d3cb7`). The fixes were
merged upstream (PR #8) and released as v0.3.2 (`26e67a7`), which is now the target.
Each finding is re-checked against the v0.3.2 release in
[2026-10-25: v0.3.2](#2026-10-25-v032): findings 1 to 4 and 6 are closed, 5 is
partially closed (`image()`'s `missing_image_bytes`) and 7 is still open.

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

## 2026-10-25: v0.3.2

Checked against the v0.3.2 GitHub release (`26e67a7`, published 2026-10-25 00:35 UTC),
consumed through the committed `libs/lib-t3-rust.lock`, on the same machine and
toolchain as above. Source references are to the lib at the tag. The lib checkout
stayed clean.

- `gh release download v0.3.2 -R avillagran/lib-t3-rust`, then `sha256sum`:
  `3a07a7e4e6d8e60d0a568c961fafb60d867dce6094e7e9cb003a8e686c1a50fc`, equal to the
  release's `.sha256` and to GitHub's asset digest; 16503549 bytes.
- `scripts/fetch-or-build-lib.sh --release --host-jni`: checksum verified against the
  lock, `version=0.3.2 source=26e67a7… tag=v0.3.2 abiVersion=3 origin=release v0.3.2`;
  host `libt3_jni.so` built from `26e67a7` (the commit check against the AAR's
  `source=` passed).
- `scripts/fetch-or-build-lib.sh --lib-fixtures; echo exit=$?`: `JNI_PASS`,
  `KOTLIN_JNI_PASS`, `KOTLIN_STREAMS_PASS` (org.json 20180813), `exit=0`.
- Smoke through the release `classes.jar` and the host library: `abi=3 lib=0.3.2
  ops=18 functions=116 describe=116 streams=13`; `upload(-1, …)` →
  `T3Exception(host_file_unavailable)`; `connectBearer` with an unknown option →
  `T3Exception(invalid_connection_options)`; `projectFavicon` offline →
  `T3Exception(not_connected)`.
- `git diff v0.3.1 v0.3.2 -- src/ include/` is empty: the Rust core and the C ABI did
  not change, only the Android bindings, tools and workflows.

| # | Finding | Status | Evidence |
|---|---|---|---|
| 1 | `tools/test-kotlin-jni` does not compile | **Closed** | `StreamFixture.kt:150` is `complete.addAll(chunks.accept(last))`; `tools/test-kotlin-jni` prints `KOTLIN_STREAMS_PASS`. |
| 2 | `T3Api` is not in the AAR | **Closed** | `T3Api.kt` is generated into `bindings/android/kotlin/dev/avillagran/t3/` (`tests/api_describe.rs:8`) and `tools/compile-android-kotlin:13` compiles `kotlin/…/*.kt`; `jar tf classes.jar` lists `T3Api` and its 20 module classes (`T3Api$Orchestration`, …; 116 `fun` declarations in `T3Api.kt`). |
| 3 | `assetBytes` and `projectFavicon` unreachable | **Closed** | `javap`: `public final T3Asset assetBytes(JSONObject, long, Integer)` and `projectFavicon(String, String)`, both with `$default`; `T3Asset(status, bytes)` with `found` and `mimeType` (`T3Client.kt:107`, `:112`, `:132`). `maxBytes` is a `Long` defaulting to `MAX_ASSET_BYTES` (64 MiB) rather than `Long?`; equivalent. |
| 4 | `connectBearer`/`connectRelay` take no `options` | **Closed** | `javap`: `connectBearer(String, String, JSONObject)` and `connectRelay(String, String, String, String, JSONObject)` with `$default`; `clientId = "t3-mobile"`, `options = null` (`T3Client.kt:40`, `:45-47`). |
| 5 | JNI failures are not `T3Exception` | **Partially closed** | `jni {}` (`T3Client.kt:23-26`) maps `IllegalStateException` with `invalid_input`, `native_error`, `host_file_unavailable` or `native_allocation_failed` (`:127`, every code `t3_jni.cpp` throws) to `T3Exception(code)`; the Kotlin fixture asserts `host_file_unavailable` (`KotlinFixture.kt:54`). Still open: `image()` throws `IllegalArgumentException("missing_image_bytes")` (`T3Client.kt:100`, `requireNotNull(asset.bytes)`). |
| 6 | No ABI 3 GitHub release | **Closed** | Release v0.3.2 has `lib-t3-rust-android-v0.3.2.aar` and `.sha256` (`android-release.yml:49-53`, on `v*` tags); `test.yml` job `kotlin` runs `tools/compile-android-kotlin`, `tools/test-jni` and `tools/test-kotlin-jni` on every push and pull request (green on PR #8 and on the tag). |
| 7 | Releases do not publish the host JNI library | **Open** | `gh release view v0.3.2 --json assets`: only the AAR and its `.sha256`; `android-release.yml:49-53` uploads only those. CI here still checks out the lib at the tag to build it. |

Fix for 5: in `image()`, `return asset.bytes ?: throw T3Exception("missing_image_bytes")`,
and in `binary()` (`T3Client.kt:96`) replace `requireNotNull(result[0])` with
`?: throw T3Exception("native_error")`, so no `T3Client` call throws anything else for
a stable code; assert `missing_image_bytes` in `KotlinFixture.kt`. (Using a closed
client still fails with `IllegalStateException("client_closed")` from `useHandle`;
that is a caller bug rather than a stable code, so it is not part of this request.)

Fix for 7: unchanged from finding 7 above. In `android-release.yml`, build the host
library as `tools/test-jni` does and add `libt3_jni-linux-x86_64.so` and its `.sha256`
to the `softprops/action-gh-release` files, from the same commit as the AAR.

## 2026-10-24 — Capabilities requested for Android phase 1 (not defects)

These are missing pieces of API, not bugs. Each already exists in Rust (in the lib or
in ot3) but cannot be reached from Kotlin, so phase 1 (connect, live sidebar, read a
thread with streaming, send, approve and answer, choose a model) would otherwise have
to re-implement protocol logic, which [ARCHITECTURE.md](ARCHITECTURE.md) forbids.
Checked against v0.3.1 (`17d3cb7`): `src/`, `bindings/android/describe.json` (116
entries: 99 unary functions, 13 typed streams, 4 composed functions) and
`T3Client.kt`. Re-checked at v0.3.2 (`26e67a7`, the current target):
`git diff v0.3.1 v0.3.2 -- src/ include/` is empty and the v0.3.2 `T3Client.kt` and
`T3Api.kt` add none of the eight, so every request stands unchanged. ot3 references are
to omarchy-t3-code v0.2.22 (`191c1fc`).

Already delivered by v0.3.1 and therefore not requested: typed streams and `subscribe`
by `fn`, `cursor`/`start` in `pollSubscription`, the `ThreadStream` facade, the typed
registry coverage of unary T3 RPCs, the 64 MiB item limit for bounded-snapshot thread
streams (item 2) and stream probing through `wakeSubscriptions` (item 5).

None of the eight is on the ABI: `describe` has no `projection`, `sidebar`, `shell`,
`reconnect`, `connection_catalog`, `history`, `composer`, `approvals`, `questions`,
`queue` or `models` entry, and `call` only sends T3 RPCs. A pure, connection-free form
(for example `{"op":"domain","fn":"<module>.<fn>","args":{...}}`, listed in `describe`
next to the RPC functions, same error envelope) would serve items 1 to 7.

**Blocking for phase 1: items 1, 3, 6 and 8** (6 since 2026-10-25, when pairing by link
shipped). Items 2, 4, 5 and 7 are nice to have: phase 1 can work around them (no compact
snapshots, commands built from the `describe` argument schema, a Kotlin retry loop, no
older history) at some cost in behaviour or bandwidth.

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
  `compactTurnItems = true` (still at v0.3.2: `T3Client.kt:316`) and `docs/ANDROID.md`
  subscribes with `acceptCompactTurnItems:true` (`:33`, `:163`, `:252` at v0.3.2), but
  nothing restores stream snapshots, so the default path hands the host an unrestored
  snapshot. Until then the app subscribes with `compactTurnItems = false`.
- In Rust today: `bounded_snapshot::restore` (`src/bounded_snapshot.rs:30`), only used
  by `boundedThread` (`src/bounded_snapshot.rs:69`, HTTP, one response).
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

### 6. Pairing-link parser (blocking since 2026-10-25)

Also U17 in the UI specification's requests below, which gates UI-6. UI_SPEC section 4.2
planned URL + token only until it lands; pairing shipped earlier on a temporary port, so
it now blocks removing that port rather than the screen.

- Proposed: an offline operation on the JSON ABI, with no handle state and no network:
  `{"op":"pairingTarget","raw":"<link or host[:port]>","code":"<typed code or empty>"}` →
  `{"httpBaseUrl":"https://host:port","pairingCode":"…"}`, failing with the function's codes
  (`invalid_endpoint`, `invalid_pairing_code`). It would be listed in `capabilities`'
  operations. Plus a Kotlin wrapper next to the `pairBearer` one (item 8), for example
  `T3Client.pairingTarget(raw: String, code: String = ""): PairingTarget` with
  `PairingTarget(httpBaseUrl, pairingCode)` and the code redacted from `toString`. (The pure
  `{"op":"domain",…}` form above would also do.)
- Why: pairing by link and QR shipped in ot3-Android on 2026-10-25, and every pairing
  starts by splitting the link. The link forms and their validation are protocol knowledge
  (the hosted `?host=` form, which parameter wins, scheme mapping, user info and port 0
  rejections, size and control-character limits), and `pairBearer` takes the already split
  `httpBaseUrl` and `pairingCode`.
- Evidence: without it, ot3-Android carries `core/pairing/PairingLink.kt`, a port of
  `pairing_target` that also has to re-implement the part of the WHATWG URL parser the
  function relies on (host normalization, IPv4 shorthand, IPv6 compression, default
  ports, form decoding of the fragment). It matches the Rust function on the 70 inputs of
  `PairingLinkTest`, captured by calling `pairing_target` at v0.3.2, plus the lib's own test
  ported case by case. Known differences remain on inputs no T3 link produces (IDNA 2003
  instead of UTS #46). It goes stale whenever the lib's rules change.
- Update 2026-10-25: lib-t3-rust v0.3.3 (`5cfb7ac`) exposes it through the
  connection-free `domain` operation as `connectionCatalog.pairingTarget` `{link,
  pairingCode?}` → `{httpBaseUrl, pairingCode}`, with a `T3Api` wrapper. The app still pins
  v0.3.2, so `PairingLink.kt` stays until it moves to v0.3.3; `PairingLinkTest`'s table
  can then check the wrapper instead.
- In Rust today: `connection_catalog::pairing_target` (`src/connection_catalog.rs:5-58`,
  test at `:265-290`); the catalog functions `view`, `mutate` and `register` (`:73`, `:107`,
  `:213`) could follow, with storage staying in Kotlin.

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
  `rpcWithTimeout(tag, payload, timeoutMs)`, `capabilities()`, `disconnect()`, `request`
  with `method` and `body` (POST), and `shell()` (`GET /api/orchestration/shell`, today
  the one HTTP path ot3-Android builds in Kotlin).
- Why: phase 1 connects by pairing (`fetchDescriptor`, then `pairBearer`) and reads
  `capabilities` to gate features. Through `execute(JSONObject)` the app would assemble
  operation JSON by hand, which is the protocol knowledge the adapter exists to hold.
- In Rust today: all of them are ABI operations (`src/ffi.rs`: `request` and
  `requestOk` take `method`, `path`, `body`; `fetchDescriptor`, `pairBearer`,
  `boundedThread`, `rpcWithTimeout`, `capabilities`). `T3Client.kt` at v0.3.1 (and
  v0.3.2) wraps none of them, and `request(path, protocol)` is GET-only.
- Update 2026-10-25: pairing now calls `pairBearer` through `execute` in
  `NativeT3Transport.pairBearer`, building `{"op":"pairBearer","httpBaseUrl",
  "pairingCode","environmentId","client":{label, deviceType, os},"connect"}` and reading
  `{accessToken, tokenType, environmentId, descriptor, scope, expiresIn, connected}` by hand.
  `NativePairingTest` pins that shape against a local server. A `T3Client.pairBearer`
  returning a typed result with the token redacted from `toString` would replace both.

## 2026-10-25: v0.3.2 through the app's transport layer

Checked with `NativeT3Transport` over the v0.3.2 release AAR and the host
`libt3_jni.so` (`26e67a7`): the JVM tests in `core/src/test/.../transport` and one
manual, read-only live run against a local T3 server (`connect(Bearer)`,
`request("/api/orchestration/shell")` (now `shell()`), `shellStream()` and `threadStream(id)`; shapes
only, no mutation). Confirmed as expected: `capabilities` (`abiVersion 3`,
`libraryVersion 0.3.2`), `describe` (116 functions, 13 streams), validation before
connecting (`invalid_cwd`, `unknown_function`, `invalid_thread_id`), `unauthorized` on a
401, `fetchDescriptor` without a credential, `t3_environment_mismatch`,
`invalid_connection_options` for both routes, and closing a subscription from another
thread waking a blocked `poll` at once.

### 1. A shell metadata snapshot replaces the shell and moves the cursor

- What: on `orchestration.subscribeShell`, T3 follows the authoritative snapshot with a
  second `kind: "snapshot"` item that carries `resolvedRepositoryIdentityRoots` and only
  refreshes project identities. The lib treats it as a full state: `start_of` returns
  `Snapshot` for every shell `snapshot` (`src/stream.rs:52-55`) and `advance` sets the
  cursor to its `snapshot.snapshotSequence` (`:21`, `:33-36`), and `ThreadStream.poll`
  returns it as `ThreadUpdate.snapshot`, dropping the events before it in the batch
  (`bindings/android/kotlin/dev/avillagran/t3/T3Client.kt:290`). A host that
  follows the documented loop ("replace whatever the host held") replaces its sidebar
  with a shell that has no threads.
- Evidence: the live shell stream, first two polls: `items=[snapshot]` with
  `snapshot{archivedThreads[0], projects[16], schemaVersion, snapshotSequence,
  threads[213]}` and `boundaries=[]`, then `items=[snapshot]` with
  `{kind, resolvedRepositoryIdentityRoots[16], snapshot{..., projects[16],
  threads[0]}}` and `boundaries=[1]` (the same server chunk), then `idle`. ot3's reducer
  treats such an item as metadata only and keeps the cursor (`src/shell_state.rs:87-107`
  in omarchy-t3-code v0.2.22, test `metadata_refresh_merges_identities_without_moving_the_cursor`
  at `:223`: "a resume prefix must not skip replayed events"), following T3's
  client-runtime `state/shellReducer.ts`.
- Two more consequences of the same rule:
  - When the metadata item shares a poll batch with the authoritative snapshot (a host
    that polls a little later than the server writes, as the live run's single chunk
    shows), `ThreadUpdate.snapshot` is the metadata item, because the last snapshot of a
    batch wins, and the authoritative snapshot survives only in `batch.items`.
  - When it is the first item after a resume (`afterSequence` set), `start` says
    `"snapshot"` instead of `"resume"`, so a host that follows `start` drops the state it
    resumed from.
- Proposed fix: keep the metadata item out of `snapshot`. Either give `ThreadUpdate` a
  `metadata` field (the item, in order, with the events before it kept), or, better and
  the same work, phase 1 item 3 (`shell.reduce`), which applies it as ot3 does. In both
  cases `stream::start_of` must not return `Start::Snapshot` for a shell `snapshot` with
  `resolvedRepositoryIdentityRoots`, and `cursor_of` returns `None` for it (so `advance`
  leaves the cursor alone). Add the frame to `StreamFixture`, alone and sharing a batch
  with the authoritative snapshot.
- Workaround in ot3-Android until then: one shell adapter in `core.transport`
  (`ThreadUpdate.shellSnapshot()`, `isShellMetadataOnly()` and the helpers next to them)
  that every shell consumer goes through. It treats a metadata item as metadata only
  (never replaces the sidebar, never trusts `start` for it), does not adopt the cursor it
  moved, and reads the authoritative snapshot from `batch.items` when the metadata item
  shadows it in `ThreadUpdate.snapshot`. This is protocol knowledge in Kotlin, against
  the rule in ARCHITECTURE.md, accepted only as a stopgap: it is deleted when a lib
  release with the fix above lands.

**Update 2026-10-25** (same server, v0.3.2, `T3Client` directly and the app's
transport, read-only): the metadata frame also arrives **in the same batch** as the
authoritative snapshot (`items=[snapshot{threads[216]}, snapshot{resolvedRepositoryIdentityRoots[16],
threads[0]}]`, `boundaries=[2]`). `ThreadStream.poll` keeps only the last `snapshot`
(`T3Client.kt:290`), so `ThreadUpdate.snapshot` is the empty metadata frame and the
authoritative snapshot is not in the update at all; a host that follows the documented
loop paints an empty sidebar. Metadata frames also recur later in a running stream (one
for a single project, `resolvedRepositoryIdentityRoots[1]`; one subscription received
13 `snapshot` items in 40 minutes, 11 of them after the first batch). ot3-Android works around it with the same
adapter (`core/transport/ShellUpdates.kt`, which reads `update.batch.items` in order and
derives the cursor without the metadata frame); `ShellReducer` only consumes it. The
proposed fix above (or item 3, `shell.reduce`) removes both.

### 2. `rpc_rejected` carries no machine-readable field or reason code

Found 2026-10-25 while building the thread screen's send (v0.3.2, `T3Exception` and
`src/api/orchestration.rs`; nothing was sent to a live server).

- What: when the server refuses a command, the app gets `T3Exception("rpc_rejected",
  reason)` with `reason` as free text (a schema error or a sentence). Nothing says which
  argument was refused or why in a stable form, so the app cannot react to it (for
  example drop `creationSource: "mobile"`, which the lib's validator allows, if a server
  refuses it) without parsing English or schema text. The previous send code did exactly
  that (it looked for `creationSource` or `"mobile"` in `reason` and resent with `"web"`);
  it was removed in favour of always sending `"web"`, as ot3 does.
- Evidence: `CREATION_SOURCES = ["web", "mobile", "mcp", "provider", "server"]`
  (`src/api/orchestration.rs:11`), while ot3 always sends `"web"` (`src/write.rs:149` in
  omarchy-t3-code) and so does the lib's own compaction command (`src/compaction.rs:154`);
  no live server has been seen accepting `"mobile"`. `T3Exception` has `code`, `reason` and `requiredScope` only.
- Proposed fix: add `rejectedField` and `rejectedReason` (a stable code such as
  `invalid_enum`, `too_long`, `not_found`) to `T3Exception` when the server's rejection
  names them, or, for this case, list the values the connected server accepts for
  `creationSource` in the descriptor or in `describe`, so the app never needs a rejection
  to learn them.

### 3. `ThreadUpdate` cannot say whether the lib's reducer applied an event

Found 2026-10-25 with the thread screen (v0.3.2), read-only against the same server.

- What: a host that follows a thread stream has to decide, per event, whether its view
  changed, was left alone (a row it does not show), or does not know the type. The lib
  knows (`projection::apply` returns `bool`), but `ThreadUpdate` only carries the raw
  items. The first version of the thread screen marked the view stale and refetched
  `boundedThread` for every type outside a small Kotlin list; on a live thread that is a
  refetch per `turn-item.updated`, i.e. a refetch storm while an agent works.
  ot3-Android now mirrors the lib's apply table in Kotlin
  (`core/.../thread/ThreadProjectionShim.kt`: applied, ignored, unknown to the lib), which
  is protocol knowledge outside the lib and goes stale with every new event type.
- Evidence: the live thread used for the check held 477 turn items, 340 nodes and 106
  messages, and the first event after subscribing was `thread.visited`, a type that is
  not in `src/projection.rs`'s table (`apply` returns false: only the cursor moves).
- Proposed fix: preferred, phase 1 item 1 (a reduced thread view owned by the
  subscription), which removes the shim altogether. Short of that, a per-item
  `applied: Boolean` in `ThreadUpdate` (the result of the lib's `projection::apply` on a
  projection the lib holds for the subscription), so the host never classifies event
  types itself.

**Phase 1 item 1 is now the main phase-1 blocker** (2026-10-25). Items 3 and 8 still
block, but item 1 is the one with live cost: the bounded snapshot of a busy thread is large
(477 turn items, 340 nodes, `payloadBudgetExceeded = true`), so refetching it is not an
answer to an event it cannot apply, and the Kotlin mirror of `projection::apply` it
forces is the largest piece of protocol logic left in the app.

## 2026-10-25: requests from the UI specification

Source: `docs/UI_SPEC.md` section 6 (merged in #7), which maps every screen of ot3-Android
v1 to the lib function it reads. This section moves the rows marked **new** there, plus
U2, whose scope the spec extends; the other U rows restate existing items above (U1 =
phase-1 item 1, U5 / U6 / U7 / U8 = item 4 and the pure-form list, U17 = item 6, U18 =
item 7). Citation prefixes (`lib:`, `ot3:`) and pins are those of UI_SPEC section 0
(lib v0.3.2 `26e67a7`, ot3 `7b5b1de`).

All of them rely on the connection-free operation proposed at the start of the phase-1
section (`{"op":"domain","fn":"<module>.<fn>","args":{...}}`, or the equivalent typed
`call` without a socket), returning JSON the app renders without further derivation.

**Order agreed with the lib (2026-10-25), already in progress there:** U2, U21, U0, U3,
U4, U10, U5, U7, U8. Gating per UI tranche: nothing blocks UI-1 (U2 and U21 complete
it); U0, U3, U4 and U10 gate UI-2; U5, U7 and U8 gate UI-3; U6, U15 and U24 gate UI-4;
U9, U19, U20 and U22 gate UI-5; U14, U17 and U23 gate UI-6; U12 gates UI-7.

### Extended existing request

| # | Request | In Rust today | Screen | Until then |
|---|---|---|---|---|
| U2 | `sidebar.view` + shell reducer (existing item 3), **extended to several environments' shells in one view** and to ot3's ten-state detail and counts | `sidebar::sidebar_status`, `metadata`, `sort_timestamps` (`lib:src/sidebar.rs:6,19,37`); detail, grouping and order only in ot3 (`ot3:ui/js/SidebarModel.js:5-125`, `ot3:ui/js/ProjectModel.js:4-12`) | Home (4.1), count pills | `SidebarModel.kt`, extended to several environments |

### New requests

| # | Request | In Rust today | Screen | Until then |
|---|---|---|---|---|
| U0 | **new** Timeline view: ot3's `conversation_timeline` rows (user, assistant, activity groups, changes, context, plan, secret, subagents, error, each with a `rev`) and `live_activity`, from a projection or the reduced view of item 1 | **ot3 only**: `ot3:src/lib.rs:532-617` | Thread (4.3), working pill (4.4.1) | user and assistant messages from `ThreadProjectionShim` (`core/.../thread/ThreadProjectionShim.kt:54`) |
| U3 | **new** `activity.describe` | `lib:src/activity.rs:38` | Activity blocks (4.3.3) | blocks hidden |
| U4 | **new** `changes.summaries` | `lib:src/changes.rs:4` | Changed-files card (4.3.4), diff ranges (4.6.2) | card hidden |
| U9 | **new** `diffReview.present` | `lib:src/diff_review.rs:100` | Diff (4.6) | file list without hunks |
| U10 | **new** Markdown as renderer-neutral blocks (paragraphs, headings, lists, quotes, tables, code with language and runnable flag, images, links classified GitHub / file / thread / web with ot3's allow-list); the lib says an Android surface "gets its own variant once its renderer is designed" (`lib:README.md:602-612`) | ot3's renderer emits Qt rich text (`ot3:src/lib.rs:119-171`); `code_blocks::parse` already returns fenced blocks as data (`lib:src/code_blocks.rs:12`) | Messages (4.3.2) | display-only CommonMark in Kotlin; only http(s) links tappable |
| U11 | **new** `theme.tokens`: Omarchy `colors.toml` in, ot3 roles out (3.1) | **ot3 only**: `ot3:ui/qml/T3Palette.qml`, `ot3:ui/qml/T3Panel.qml:380-387`, `ot3:ui/js/DocumentTheme.js` | every screen | precomputed by the generator with goldens |
| U12 | **new** `timeline.minimap` | **ot3 only**: `ot3:ui/js/TimelineModel.js:4,16` | Tablet minimap (4.3.8) | no minimap |
| U13 | **new** `subagents.buckets` and labels | **ot3 only**: `ot3:ui/js/Subagents.js:8-54` | Subagent cards (4.3.6) | hidden |
| U14 | **new** `usageSummary.request` / `aggregate`, `quotaSources.limits` / `providers` / `sources` | `lib:src/usage_summary.rs:23,40`, `lib:src/quota_sources.rs:23,75,86` | Usage (4.10) | page hidden |
| U15 | **new** `planWorkflow.view` / `followUp` with a renderer that returns Markdown | `lib:src/plan_workflow.rs:191,325` | Plan card and banner (4.3.7) | plan as a message |
| U16 | **new** ship ot3's string catalog with the lib (or the AAR) | `i18n::translate` has no catalog (`lib:src/i18n.rs:4`); catalog in `ot3:translations/i18n.json` | every screen | generated `strings_ot3.xml` |
| U19 | **new** `pullRequests.sidebarBadge`, `pullRequests.payload` | `lib:src/pull_requests.rs:82,268` | Row PR badge (3.7), PR comment, review and actions (4.8) | no badge; arguments built from the `describe` schema |
| U20 | **new** `branchPicker.dedupe` / `selectionTarget` / `mismatch` | `lib:src/branch_picker.rs:9,41,90` | Branch picker, branch mismatch notice (4.7.3, 4.4.1) | refs listed as returned |
| U21 | **new** project identity: monogram and colour slot | **ot3 only**: `ot3:src/lib.rs:729-755`, `ot3:ui/qml/T3Palette.qml:36-46` | Project chip everywhere (3.7) | first letter of the title, accent tint |
| U22 | **new** `gitControls.scope` / `actions` / `present` / `buildAction` | `lib:src/git_controls.rs:37,185,203,245` | Git & changes sheet (4.6.3) | status and Review only |
| U23 | **new** `nativeSettings.snapshot` / `patch`, `scopedSettings.effective` / `projectPatch` | `lib:src/native_settings.rs:107,152`, `lib:src/scoped_settings.rs:38,56` | Settings pages (4.9) | read-only |
| U24 | **new** `checkpointRevert.canPreview` / `reference` / `command` / `observation` / `recoveryMessage` | `lib:src/checkpoint_revert.rs:7,39,159,208,248` | Edit from here (4.5.4) | not offered |
| U25 | **new** `toolInspector.scope` / `request` / `parse` | `lib:src/tool_inspector.rs:61,89,236` | Inspector (4.3.3) | not offered |

Proposed fix, common to every row: expose the Rust function (moving it from ot3's crate
into the lib first where the row says "ot3 only") through the connection-free operation,
add it to `describe` with typed args, generate the `T3Api` wrapper, and cover it with a
fixture test whose JSON the app can reuse as screenshot-test input.

## 2026-10-25: v0.3.2 pairing through the app's transport layer

Checked with `NativeT3Transport.pairBearer` over the v0.3.2 release AAR and the host
`libt3_jni.so` (`26e67a7`): `NativePairingTest` against a local server serving the
descriptor and `POST /oauth/token`, and one live pairing against a local T3
0.1.3-nightly.20261025.3669 from a `t3 pair` link (`LivePairingTest`). The session it
created was revoked afterwards. Confirmed as documented: the descriptor is read and its
`environmentId` checked (UUID, expected id) before the code is sent; input errors
(`invalid_pairing_code`, `invalid_pairing_client`, `invalid_endpoint`) fail before any
request; the form-encoded token exchange carries the client's label, device type and OS;
`connect: true` connects the handle. Item 6 above (U17) moved to blocking with this
work.

### 1. `pairing_rejected` also covers server errors

- What: `pair_bearer` maps every failed exchange to `pairing_rejected`: `unauthorized` (401,
  403) and every `http_<n>`, including 5xx (`src/pairing.rs`, the `map_err` after
  `decode`). An app then tells the user the code is bad ("expired or already used, run
  `t3 pair` again") when the server failed or a proxy in front of it answered 502.
  Re-running `t3 pair` does not help with that, and each run mints another credential.
- Evidence: `NativePairingTest.aRefusedCodeIsPairingRejected` gets `pairing_rejected` for
  400, 401, 403 and 500 alike. The OAuth error body (`{"error":"invalid_grant"}` in the
  fixture) is dropped, so the app cannot tell an expired code from an unknown one either.
- Proposed fix: keep `pairing_rejected` for 400, 401 and 403 (an OAuth refusal), and
  return `http_<n>` for anything else, as every other operation does. Put the OAuth
  `error` value (when it is a short ASCII token) in the exception's `reason`.

### 2. Pairing again leaves the previous session alive, and Kotlin cannot revoke it

- What: re-pairing an environment that is already saved (a lost or reset token,
  `unauthorized` after a server change) gets a new session. The app replaces the stored
  token, but the old session stays active on the server, and every re-pair adds one
  more. ot3 retires its own stale sessions with `Connection::revoke_stale_sessions`
  (`src/pairing.rs:189-210`: `GET /api/auth/clients`, a host policy, `POST
  /api/auth/clients/revoke` `{sessionId}`). That function is Rust-only: no ABI operation,
  no `describe` entry, and `T3Client.request` is GET-only.
- Evidence: the live run's session showed up in `t3 auth session list` with the client
  label the app sent. It could only be removed from the server's CLI (`t3 auth session
  revoke`), not from the app.
- Proposed fix: two typed operations on a connected handle, `auth.sessions` →
  `{current: sessionId, sessions: [{sessionId, client{label, deviceType}, issuedAt,
  lastConnectedAt, connected}]}` and `auth.revokeSession {sessionId}` (refusing the current
  session). Then the app revokes the session whose token it just replaced. Alternatively,
  a `pairBearer` option `replaceSession: <old token>` that revokes it once the new session
  works.
