# Decisions

Dated record of build and structure decisions. Architecture itself is in
[ARCHITECTURE.md](ARCHITECTURE.md).

## 2026-10-24: Manual dependency injection, no Hilt

The dependency graph is a plain `AppGraph` class created once in `Ot3Application`.
Code with a `Context` reaches it through `Context.appGraph`; Compose screens get their
ViewModels from `appViewModel { graph -> ... }`, a thin wrapper over
`lifecycle-viewmodel-compose`'s `viewModel { }` that hands the initializer the graph.
Every dependency is `lazy` or opened on first use.

- The app is small: one activity and a handful of repositories.
- Compose does not need injected Android components; screens receive state holders and
  lambdas, which is easy to wire by hand and easy to fake in tests.
- No annotation processing or generated components, so builds stay fast and the graph is
  readable in a single file.
- Hilt, the default in most templates, is not used: it adds KSP processing, a Gradle
  plugin and `@AndroidEntryPoint` plumbing for a benefit this app does not need yet.
- If the graph grows beyond what is comfortable by hand, Koin is the fallback: it is
  runtime-only and keeps the same constructor-based style. Hilt is reconsidered only
  together with that question.

## 2026-10-24: Room for structured data, DataStore Preferences for small state

- Room (with KSP, Kotlin code generation, and the `androidx.room` Gradle plugin exporting
  schemas to `core/schemas`) stores structured, queryable data: environments now; drafts,
  outbox and caches later. Exported schemas are committed and packaged as androidTest
  assets so `MigrationTestHelper` can test every migration.
- Schema 1 already carries what the environment catalog needs (label, origin-only base
  URL, the server's environment UUID, creation and last-connection times, sort order) to
  avoid an immediate migration. Routes beyond URL + token come later with a tested
  migration.
- DataStore Preferences stores small, non-secret UI state such as the last selected
  environment id.
- Neither of them ever holds a credential in plaintext. The only credential data either
  may hold in the future is ciphertext written by `CredentialStore` (see the migration
  path below).

## 2026-10-24: security-crypto (EncryptedSharedPreferences) as credential store

Tokens are stored through `CredentialStore`, backed by `EncryptedSharedPreferences` from
`androidx.security:security-crypto`. Its master key is generated and kept in the Android
Keystore, so key material never leaves secure hardware where available.

- The master key has an explicit alias (`ot3_credentials_master_v1`), uses AES256_GCM and
  does **not** require user authentication: the future follow service must read tokens
  while the device is locked.
- The store is opened off the main thread (`CredentialStore.open`, on `Dispatchers.IO`).
  If the file or its master key is unusable (Keystore reset, restore from another
  device), both are deleted and the store is recreated once; `wasReset` lets the UI say
  "credentials reset, re-pair". Writes use `commit()`. Values and exceptions are never
  logged.
- Removing an environment deletes its Room row and its token together.
- Chosen over raw Keystore code because it gives authenticated encryption of keys and
  values with very little code, and works from minSdk 24.
- The library is deprecated upstream as of 1.1.0. It still works and is the shortest
  correct path today, so it is used behind `CredentialStore` with the deprecation warnings
  suppressed in that file only. The migration path is a raw Keystore AES-GCM key
  (same no-user-auth policy) behind the same class, with a one-time migration of existing
  entries. The values it persists, in DataStore or a private file, are ciphertext only;
  the key never leaves the Keystore and no plaintext token is ever written to disk.

## 2026-10-24: compileSdk and targetSdk 36

The SDK installed on the development machine includes `android-37.0`, a base
(non-preview) platform. compileSdk and targetSdk nevertheless stay on 36, because moving
to 37 is not a one-line change:

- Compose 1.12 (BOM `2026.08.00` and `2026.09.xx`) requires compileSdk 37 and AGP 9.1,
  and AGP 9.x requires Gradle 9. The three move together.
- Today the build uses Android Gradle Plugin 8.13.x (Gradle 8.14), whose highest
  supported compileSdk is 36, and the Compose BOM `2026.06.01` (Compose UI 1.11), the
  newest built for compileSdk 36.
- JVM target 17; CI and the development machine build with JDK 21.

ARCHITECTURE.md asks for "the latest stable SDK". Read that as the latest SDK the stable
toolchain (AGP, Compose BOM) supports as a set; adopting 37 is recorded as pending below.

## 2026-10-24: Module split: `app` and `core`

- `app`: the single activity, navigation, Compose UI and theme, and the `AppGraph`.
- `core`: an Android library (not pure JVM, because Room, DataStore and the Keystore
  need Android) with `domain` (plain Kotlin models), `data` (Room, DataStore),
  `security` (credentials). A `transport` package arrives with the lib-t3-rust AAR.
- The lib-t3-rust AAR will be consumed by `core` only. Protocol logic stays in the Rust
  library.
- More modules (for example per feature) only when build times or ownership ask for it.

## 2026-10-24: The Kotlin transport mirrors the lib's `T3Client`

**Superseded** by [Transport layer over `T3Client`](#2026-10-24-transport-layer-over-t3client-aar-through-a-t3-lib-module):
the interface now exists, written together with the AAR rather than ahead of it, and
the UI again sees no HTTP path since `request(path)` became `shell()`.

No transport interface is written ahead of the AAR. The Kotlin transport adapter arrives
with it and mirrors the surface of the lib's `T3Client` (`connect(route)`, `call`,
`describe`, `subscribe` delivering full batches with boundaries, idle, completion marker,
closed and error, `wake`, `upload`, `image`, `Closeable`); it does not expose RPC tags or
HTTP paths to the UI layer.

## 2026-10-24: Transport layer over `T3Client`, AAR through a `:t3-lib` module

Revised 2026-10-25 after review (`shell()`, `disconnect()`, non-blocking `close()`, the
factory, the fake's scripting). Supersedes the entry above.

- **`:t3-lib`** exposes `libs/lib-t3-rust-android.aar` as the artifact of a plain
  `default` configuration; `core` takes it with `api(project(":t3-lib"))`, so `app` sees
  the adapter and packages the four `lib/<abi>/libt3_jni.so`. A library module cannot
  depend on a local `.aar` file directly, and this works with
  `RepositoriesMode.FAIL_ON_PROJECT_REPOS` (no flatDir). Configuration fails with a
  pointer to `scripts/fetch-or-build-lib.sh` when the AAR is missing.
- **`core.transport`**: `T3Transport` (`Closeable`) mirrors `T3Client`: `connect(route)`
  with `ConnectRoute.Bearer` or `ConnectRoute.Relay` (credentials redacted from
  `toString`; their `JSONObject` options compare by reference), `disconnect`,
  `capabilities`, `describe`, `call`, `shell`, `fetchDescriptor`, `boundedThread`,
  `shellStream`, `threadStream`, `wakeSubscriptions` and `close`. Results are the lib's
  `org.json` values and failures its `T3Exception`, unchanged: there is no parallel error
  type and the UI maps `code`. `fetchDescriptor`, `capabilities`, `boundedThread` and
  `disconnect` go through `execute` because `T3Client` has no wrapper for them yet
  (LIB_REQUESTS.md, phase 1 item 8). The UI never sees an RPC tag or an HTTP path:
  `shell()` is the one path built in Kotlin (`GET /api/orchestration/shell`, read through
  the lib's `request`, which adds the protocol header), and `request(path)` is `internal`
  for the core tests. `upload`, `image`, `assetBytes` and `projectFavicon` arrive with the
  screens that need them.
- **`T3TransportFactory`** creates one transport per environment (each its own handle);
  `AppGraph` takes `NativeT3TransportFactory` by default and tests pass a
  `FakeT3TransportFactory`.
- **Session changes end streams like a cancel.** A successful `connect` (a switch) or
  `disconnect` cancels every stream of the handle, and so does `close`: the stream ends
  with `closed = true, error = null`, which cannot be told apart from a host cancel. The
  reconnection supervisor must not back off on it; it is the supervisor's own action.
- **`close()` does not block.** `T3Client.close()` waits for in-flight native calls up to
  their deadline, so `NativeT3Transport.close()` hands it to an internal
  `CoroutineScope(Dispatchers.IO + SupervisorJob())` and returns; it is idempotent.
  `shutdown()` awaits it (tests, teardown). Using the transport after `close()` fails
  with `IllegalStateException("client_closed")` from `T3Client`: a caller bug, not a
  stable code, so it is not mapped to `T3Exception`. A stream polled at that moment ends
  as a cancel instead.
- **Cancelling a native `call` does not unsend it.** The coroutine is cancelled, but the
  blocking JNI call underneath runs to completion: a `dispatchCommand` whose caller was
  cancelled is still sent, and its result is dropped. Send mutations from an app-scoped
  (or `NonCancellable`) context with a stable command id, and treat
  `rpc_outcome_unknown` as "maybe applied" (outbox, reconcile from the stream), never as
  a failure to retry blindly.
- **`NativeT3Transport`** runs every native call on `Dispatchers.IO`. Streams are cold
  flows over `ThreadStream`: each collection opens one subscription, polls it on IO,
  skips polls with nothing new, emits the closed update last and completes. A poll that
  throws ends the stream the same way: `T3Exception` keeps its code as the closed update's
  `error`; `unknown_subscription` (the handle dropped the stream on connect or disconnect)
  and `client_closed` are a cancel (`error = null`). Only opening a stream throws.
  Cancelling the collector closes the stream, which wakes a poll blocked in native code at once
  (measured 11 ms instead of up to 1 s). The flow has no buffer: the lib's bounded queue
  is the buffer, and a collector that falls behind ends the stream with
  `stream_backpressure` instead of holding snapshots in Kotlin. Nothing reconnects; the
  host resumes from the last update's `cursor`.
- **Shell adapter** (`ShellUpdates.kt`: `ThreadUpdate.shellSnapshot()`,
  `isShellMetadataOnly()`, `shellMetadata()`, `shellEvents()`, `shellCursor(previous)`):
  every shell consumer reads shell updates through it, because lib 0.3.2 treats T3's
  metadata-only shell `snapshot` as a full shell (LIB_REQUESTS.md, "v0.3.2 through the
  app's transport layer", finding 1). A stopgap against the no-protocol-in-Kotlin rule,
  deleted when the lib fix lands.
- `threadStream` defaults to `compactTurnItems = false` (the lib's `ThreadStream`
  defaults to true) until the lib restores compact stream snapshots (LIB_REQUESTS.md,
  phase 1 item 2).
- **`FakeT3Transport`** (scripted `suspend` answers, `MutableSharedFlow` streams plus
  optional per-collection stream hooks, `failNextStream`, `emitError`, a call log,
  `not_connected` before `connect`, streams ended by `connect`/`disconnect`/`close`) and
  **`ThreadUpdates`** (builds a `ThreadUpdate` over a minimal `T3Batch`) live in `core`'s
  main source set so `app` tests can use them too; Android test fixtures with Kotlin are
  still experimental in AGP 8.13.
- JVM unit tests drive the real Rust core through the host `libt3_jni.so`
  (`java.library.path` set on every `Test` task, `org.json` on the test classpath)
  against small local HTTP and WebSocket fakes. They skip when the library is absent and
  fail instead with `OT3A_REQUIRE_HOST_JNI=1`, which CI sets, or
  `-Pot3a.requireHostJni=true` locally. The library and both switches are inputs of the
  `Test` tasks, so a skipped run is never reused once the library exists. CI also checks
  that the debug APK carries `lib/<abi>/libt3_jni.so` for the four ABIs.
- `NativeSmokeTest` pins the targeted lib exactly (`0.3.2`, 116 functions, 13 streams).
  This is intentional: bumping `libs/lib-t3-rust.lock` must fail the test until someone
  reads the lib's changes and updates the pins with it.

## 2026-10-25: Connection supervisor per environment, cursor in DataStore, temporary sidebar logic

- **One `ConnectionSupervisor` per saved environment** (`core.connection`), owned by a
  process-wide `ConnectionManager` and created on first use. It owns one `T3Transport`
  while running and runs `fetchDescriptor` → `connect(Bearer)` → shell GET → shell
  stream, exposing `StateFlow<ConnectionState>` (Disconnected, Connecting,
  Connected(descriptor), Unauthorized, Unreachable(error, nextRetryAt)) and the reduced
  `StateFlow<ShellState?>`. It is pure Kotlin (no Android types), so JVM tests drive it
  with `FakeT3Transport` and virtual time. Reconnecting follows
  [PROTOCOL_NOTES.md](PROTOCOL_NOTES.md) ("Resume and reconnect"): jittered `Backoff`,
  resume from the cursor on transient errors, fresh snapshot otherwise, stop on
  `unauthorized`. Streams stop on the process's `ON_STOP` (ProcessLifecycleOwner, so
  rotation does not reconnect), resume on `ON_START`, and are probed with
  `wakeSubscriptions` on `ON_RESUME` and on default-network changes.
- **Supervisors start lazily**: `ConnectionManager.supervisor(id)` creates one (not
  started) the first time a screen asks for it, and the sidebar starts it. Nothing
  connects at process start; the background stops only the ones that were running and
  the foreground restarts just those. `forget` (edit or removal) waits for the run to end
  before the caller deletes the row, and each run carries a generation so a run winding
  down after `stop` never overwrites the state of the next one.
- **Other screens borrow the supervisor's transport** through
  `ConnectionSupervisor.connection: StateFlow<Connection?>` (`Connection(transport,
  epoch)`): set while `Connected`, null otherwise; `epoch` grows with every `connect`,
  which ends every stream of the handle as a cancel, so a thread view reopens its streams
  when the epoch changes. They never close it. `ShellState.thread(id)`/`project(id)` are
  indexed, and each row keeps its `raw` JSON so fields the reducer does not read
  (`modelSelection`, `runtimeMode`, ...) are reachable without growing the reducer.
- **Shell cursor in DataStore** (`ShellCursorPreferences`, file `connection`, key
  `shell_cursor:<local id>`), written off the hot path (conflated, debounced 1.5 s, and
  once more when a run ends) and removed with the environment or when its URL changes.
  **It is write-only today**: a run always starts from a shell GET and subscribes after
  that shell's sequence, because a cursor without the shell it belongs to resumes nothing.
  A cached shell (later) is what will read it. The cursor is `shellCursor`, not
  `update.cursor`, because the lib moves its cursor on metadata-only snapshots
  (LIB_REQUESTS.md).
- **Token in Keystore-backed preferences**: `EnvironmentRepository` writes the Room row
  and the `CredentialStore` token together (token first) and deletes the row, the token
  and the cursor together. `CredentialStore` now implements a small `TokenStore`
  interface so the repository is testable on the JVM. The connect screen holds the token
  only in memory, masks it, never shows it back and never logs it.
- **Temporary Kotlin protocol logic, isolated in two files**: `core/shell/ShellReducer.kt`
  (a port of ot3's `shell_state.rs`) and `core/shell/SidebarModel.kt` (ot3's minimum
  sidebar rules), each with a header naming LIB_REQUESTS.md phase 1 item 3. `Backoff`
  mirrors the lib's `reconnect::Backoff` (item 5). These do not grow; they are deleted
  when the lib exposes `shell.reduce`/`sidebar.view` and the reconnect policy.
- **`core/transport/ShellUpdates.kt` is the one stopgap for the metadata-only shell
  snapshot** (LIB_REQUESTS.md, "v0.3.2 through the app's transport layer", finding 1).
  `ShellReducer.reduce(state, update)` and the supervisor's cursor read updates only
  through it (`shellSnapshot`, `shellMetadata`, `shellEvents`, `shellCursor`); neither
  detects metadata frames itself.
- `room-runtime` is now an `api` dependency of `core`, since `Ot3Database` is part of
  its public surface and `AppGraph` builds the repository from it.

## 2026-10-25: Live thread: temporary projection shim, rate-limited refetch, outbox and drafts

- **`ThreadSession`** (`core.thread`, pure Kotlin) owns one open thread: `boundedThread`,
  then `threadStream` from its `snapshotSequence`, publishing `StateFlow<ThreadView>`
  (messages, working, active run, pending requests, model selection, stream state, stale)
  on `repaint` only. It runs on the environment supervisor's handle
  (`ConnectionSupervisor.connection`, a `Connection(transport, epoch)`) and pauses when that
  goes null, so background stops, wakes and token problems stay with the supervisor. A new
  epoch restarts the stream with a fresh backoff, resuming from the held sequence.
  Reconnects follow the supervisor's rules with its own `Backoff`; a stream closed with no
  error is a cancel and reopens from the cursor after 250 ms (backing off after three quick
  ones in a row).
- **The shim is one file that mirrors the lib's `projection::apply` in three classes**
  (`ThreadProjectionShim.kt`): *applied* (`message.updated`, `run.created|updated` with the
  `activeRunId` rule, `run.background-work-cancelled`, `runtime-request.updated`, and any
  `thread.*` naming this thread, with `thread.pull-request-link-updated` patching its link
  as the lib does); *ignored* (every other type in the lib's table: turn items, plans,
  attempts, nodes, subagents, provider rows, checkpoints, context rows); *unknown to the
  lib* (`thread.visited`, seen live, and anything new), which only moves the cursor, as the
  lib and ot3 do. No event ever causes a refetch. This replaces the first version, which
  refetched on every type outside a short list (a refetch per turn item while an agent
  works) and guessed `message.created|deleted`. Messages are not capped (the lib caps them
  at 160); runs keep the lib's 64. "Working" and Stop use ot3's `active_run_id`.
- **Refetch only when events may have been lost**: the stream ended with
  `stream_backpressure` or `stream_closed`, a snapshot was malformed (no `projection` or
  no `snapshotSequence`), or the shim failed on an item; at most once per 10 s, waiting
  for the end of the interval, with the view kept on screen and marked stale. Bugs
  (`IllegalStateException` other than a closed handle, other runtime errors) are
  `internal_error` and wait for Retry.
- **`creationSource: "web"`** on every message, as ot3 sends. The lib also allows
  `"mobile"`, but it is unverified live, and a rejection gives no machine-readable reason
  to fall back on (LIB_REQUESTS.md, "v0.3.2 through the app's transport layer" item 2).
  Both LIB_REQUESTS items found here (no rejection field, no per-event `applied`) are
  solved best by phase 1 item 1, now the main phase-1 blocker.
- **Outbox in Room** (`outbox`: id = `commandId`, environmentId, threadId, commandJson,
  createdAt, state). `MessageSender` mints `ot3a-<uuid>` ids once, persists before sending
  and sends from the app scope under `NonCancellable`; only `rpc_outcome_unknown` keeps an
  entry, and Resend sends its stored bytes unchanged. A row still `sending` at the next
  process start becomes `unknown`. Only `unknown` rows are shown, each with Resend and
  Discard, and while one exists for a thread its Send is disabled. Interrupts are
  repeatable and are not persisted. The `message.dispatch` arguments are built in Kotlin from the lib's validator
  (LIB_REQUESTS.md phase 1 item 4 would move that into Rust); Rust validates them again.
- **Drafts in Room** (`drafts`: threadKey `<environment id>/<thread id>`, text, updatedAt),
  written 400 ms after the last change and once more when the screen goes away; empty text
  deletes the row. While a send is in flight the composer is locked; once the command is
  in the outbox the persisted draft is cleared (the row owns the text, so a crash cannot
  leave it in both). Accepted clears the draft, `rpc_outcome_unknown` moves the text to
  its outbox row, any other failure puts it back. Resend never touches the draft. This
  logic is `ThreadComposer` in `core`, so it is unit-tested.
  Room rather than DataStore because the outbox already needs Room and both are per thread.
- **Schema 2** adds both tables with a manual `MIGRATION_1_2` whose SQL is Room's own
  (`core/schemas/.../2.json`), tested with `MigrationTestHelper`.

## 2026-10-25: The emulator is the reference device, started only through `scripts/emulator.sh`

UI screenshot comparisons and instrumented tests run on one emulator: AVD `ot3a` (pixel_6,
API 34 google_apis x86_64, 2 GB), `-gpu swiftshader_indirect`, cold boot, console port
5580. `scripts/emulator.sh` is the only supported way to start it; see
[EMULATOR.md](EMULATOR.md).

- The 2026-10-24 boot failures came from the soft `RLIMIT_NOFILE` of 1073741816 that
  T3/agent shells inherit. Before spawning each helper, qemu walks every fd up to that
  limit while holding the big QEMU lock, and its hang detector kills it. The script lowers
  the soft limit to 4096 before launching. It is the only fix needed: no emulator
  downgrade, image change or host change.
- SwiftShader, not the host GPU: rendering is deterministic and the same as in CI, which
  is what screenshot comparisons need, and it works headless.
- A non-default port and adb only with `-s emulator-5580`, so a phone on wireless adb is
  never addressed. `connectedAndroidTest` runs with `ANDROID_SERIAL` set; without it AGP
  uses (and uninstalls the app from) every connected device.
- CI runs `:core:connectedDebugAndroidTest` on the same image with
  `reactivecircus/android-emulator-runner`. `:app` instrumented tests stay local until
  their three failing tests are fixed.

## 2026-10-25: Pairing by link and QR: lib `pairBearer`, temporary link parser, CameraX + ZXing, unverified deep link

- **`PairingService`** (`core.pairing`, pure Kotlin) runs a pairing as a state machine
  (`Idle → Inspecting → Ready → Pairing → Paired | Failed`): parse the link, read the
  descriptor (label and version shown to the user), and only on the user's confirmation
  `pairBearer(connect = false)` with the descriptor's `environmentId`. The issued
  long-lived `accessToken` goes to the credential store; the one-time code is never stored
  and no state, `toString` or log holds either. The Room row takes the server's
  `environmentId` and the descriptor's label. A server already saved (same `environmentId`)
  at the same address keeps its row, id and label and gets the new token. At another
  address it is never moved silently, because the descriptor's `environmentId` is
  unauthenticated and any server can claim it: the screen warns that the link moves the
  saved environment from the old address to the new one, the default action adds a new
  environment, and a second, explicit Replace moves the saved one. Then the
  connection layer takes over. A saved environment that changes is stopped first
  (`ConnectionManager.forget`, which returns once its run has ended) and only then gets its
  new row and token, because a supervisor reads the token at each reconnect but keeps the
  URL it was built with: stopping it afterwards would leave a window in which it sends the
  new token to the old address. After the write, any supervisor built in between is dropped
  and a fresh one starts. Lib codes are grouped into `PairingFailure` for the
  UI; each step uses a short-lived transport. Confirming is a separate step so that a link
  opened from elsewhere (a deep link) never pairs without the user seeing the server.
  Once confirmed, the exchange, the save and the hand-over run to the end even if the user
  leaves the screen (`NonCancellable`): the exchange spends the code, so stopping half-way
  could only lose the issued session.
- **`pairBearer` goes through `execute`** in `NativeT3Transport`, like `fetchDescriptor`,
  because `T3Client` has no wrapper (LIB_REQUESTS.md, phase 1 item 8).
- **Temporary link parser** `core/pairing/PairingLink.kt`, a port of
  `connection_catalog::pairing_target`, which the lib does not expose (phase 1 item 6,
  U17 in UI_SPEC, now blocking). It also re-implements the part of the WHATWG URL parser
  that function relies on. `PairingLinkTest` ports the lib's own test case by case and
  compares 70 inputs with what the Rust function returned at v0.3.2 (captured by calling it
  from a small binary). Known differences are listed in the file's header. It is deleted
  when the lib's `pairingTarget` lands.
- **QR scanning: CameraX + ZXing core** (`camera-camera2`, `camera-lifecycle`,
  `camera-compose` 1.6.2; `com.google.zxing:core` 3.5.4), decoding the Y plane of
  `ImageAnalysis` frames in the app (`ui/pair/QrDecoder.kt`). The scanner only takes text
  that is a pairing link (`PairingService.isPairingLink`: a non-empty code, and a `/pair`
  path or a `host` parameter); for any other QR code it says "Not a T3 pairing code" and
  keeps scanning.
  - Not ML Kit's bundled barcode scanner: its AAR (`barcode-scanning` 17.3.0) is 9.9 MB,
    mostly a native model for each ABI, so it would cost several times more than the whole
    scanner below. Its unbundled variant needs Play Services at runtime.
  - Not zxing-android-embedded: it drives the deprecated Camera1 API from its own
    View-based activity, and its last release is from 2021.
  - ZXing is pure Java and reads inverted codes (`ALSO_INVERTED`, honoured by
    `MultiFormatReader`). `t3 pair` draws its QR in the terminal's text colour, so on a dark
    terminal it is light on dark; `QrDecoderTest` covers both.
  - `camera-view` (`PreviewView`, `LifecycleCameraController`) is avoided: since 1.6 it
    depends on `camera-video`, which brings media3 and Guava: the APK grew 4.94 MB with it
    and about 2.3 MB without. The
    preview is `camera-compose`'s `CameraXViewfinder` over `ProcessCameraProvider`.
  - Cost, measured on the debug APK (no minification, as in release today): +2.3 MB
    (2.27 to 2.34 MB between builds, the rest of the spread being zip alignment of the
    uncompressed native libraries): dex +2.02 MB, CameraX's two small JNI libraries
    +0.16 MB over four ABIs, resources +8 KB.
  - `CAMERA` is the only new permission and is requested when the scanner opens, with
    `uses-feature android.hardware.camera required="false"` so devices without a camera
    can still install the app and paste the link. After a denial Android will not ask
    again for (no rationale to show), the scanner offers Open settings (the app's details
    screen) and checks the permission again when the user comes back.
- **Deep link without App Links**: an intent filter for `http` and `https`, any host,
  path `/pair`, `autoVerify="false"`. Verification is impossible because T3 servers are
  arbitrary hosts. Up to Android 11, opening such a link from another app shows a chooser
  that includes ot3. From Android 12, Android sends web links to the default browser unless
  the app is approved for the domain, so the chooser normally does not appear there. On
  those versions the in-app scanner and paste are the dependable paths. Neither behaviour
  has been checked on a device yet. A link the app receives fills the pairing screen and
  shows the host it points at, but sends nothing until the user taps Continue (no request
  to an address the user has not seen), and then still waits for the confirmation. A link
  scanned in the app reads the server at once. The intent filter
  belongs to a trampoline, `PairingLinkActivity`: `Theme.NoDisplay`, `excludeFromRecents`,
  `noHistory` and an empty `taskAffinity`, so it never shows, never joins the app's task and
  leaves no recents entry. It hands the link to `PairingInbox`, starts `MainActivity` with a
  plain intent and finishes, so the intent holding the code is never `MainActivity`'s
  launching intent and never sits in the app task's recent-tasks record. `MainActivity` is
  `singleTask` and has no VIEW filter; a running instance is brought to the front.
- **Links travel in memory only** (`PairingInbox` in `AppGraph`), never as a navigation
  argument or saved state, because they carry a one-time code. The pairing screen drops the
  link once paired, and also when the server refused its code or the code is malformed,
  since it cannot be used again.
- **`FLAG_SECURE` while pairing**: the pairing and scanner screens set it on the activity's
  window while they are composed (`ui/pair/SecureWindow.kt`, counted so that the
  transition between the two never clears it), so the link, the code it holds and the
  camera view stay out of screenshots, screen recordings and the recents thumbnail.
- **UI kept minimal and isolated** (`ui/pair/`, one button on the Connect screen). The
  redesign replaces it with the Add environment screen of [UI_SPEC.md](UI_SPEC.md) (4.2,
  tranche UI-6), whose QR card, link field and masked code use this `PairingService`. That
  screen keeps CameraX, and the decoder choice above stands. The link field is masked like a
  token and uses a password keyboard, so the keyboard neither suggests nor learns it.

## 2026-10-25: lib-t3-rust v0.3.3, the Kotlin shims removed, `DomainEngine`

- **Target v0.3.3** (`5cfb7ac`, release AAR SHA-256 `183e233e…`, see LIB_BUILD.md): ABI 3
  plus the op `domain`, 37 pure functions with no connection (`describe().domain`).
- **`DomainCalls` / `DomainEngine`**: `T3Transport` extends `DomainCalls`
  (`suspend fun domain(fn, args): Any?`, offline, on IO), and `DomainEngine` holds one
  never-connected `T3Client`, created lazily on IO, for UI state builders that have no
  environment transport. `DomainEngine.shared` is the process-wide one (concurrent calls
  are fine: `T3Client` takes a read lock per call). Tests use `DomainEngine(Unconfined)`
  so the real lib runs inline on a test dispatcher.
- **Removed**: `core/shell/SidebarModel.kt` (now `SidebarView`, a mapper of
  `sidebar.view`, project chips from its `projectIdentities`) and
  `core/thread/ThreadProjectionShim.kt` (now `ThreadSession` + `ThreadTimelines`:
  `projection.apply` once per update on the held snapshot JSON, `timeline.view` on each
  repaint, both off the main thread; the whole `timeline.view` result is kept as
  `ThreadView.timeline` for UI-2). The refetch stays only for `stream_backpressure`,
  `stream_closed`, a malformed snapshot or a projection the lib refuses, at most once
  per 10 s. The resume cursor is the lib's (`ThreadUpdate.cursor`).
- **Kept, still temporary, each with its missing lib function in LIB_REQUESTS.md
  ("2026-10-25: v0.3.3")**: `ShellReducer.kt` and `ShellUpdates.kt` (no `shell.apply`;
  the shell metadata snapshot finding is still open), `MessageSender`'s
  `message.dispatch` and `run.interrupt` arguments (no composer message builder),
  `Backoff.kt` (no `reconnect.*` domain function). `PairingLink.kt` on `feat/pairing`
  must switch to `connectionCatalog.pairingTarget`.
- **Sidebar screen until UI-1**: one environment (`environmentId` filter), a flat active
  list in the lib's order with a project chip per row (replacing the per-project
  headers), a snoozed shelf, the settled section, and the lib's English `statusLabel` in
  the status chip until the i18n catalog lands.

## 2026-10-25: lib-t3-rust v0.3.4; strings and theme colours come from the lib

- **Target v0.3.4** (`e2ec546`, release AAR SHA-256 `b90064ae…`, see LIB_BUILD.md): same
  ABI, operations and `T3Client`; `describe().domain` grows from 37 to 108 functions, which
  expose every row of UI_SPEC section 6 (LIB_REQUESTS.md, "2026-10-25: v0.3.4"). The AAR
  carries ot3's string catalog as the asset `lib-t3-rust/i18n.json` (20 locales, 1 796
  English sources), which Gradle merges into the app's assets.
- **Strings are generated at build time from the AAR asset.** The `strings_ot3.xml`
  generator reads `assets/lib-t3-rust/i18n.json` from `libs/lib-t3-rust-android.aar`
  instead of an ot3 checkout, so the strings are pinned by `libs/lib-t3-rust.lock` like
  the rest of the lib; the UI-1 branch switches its generator. English sources the lib
  returns at run time (`statusLabel`, subagent labels, summary parts) are looked up with
  `i18n.t` / `i18n.catalog` rather than a Kotlin name table.
- **Theme colours come from `theme.tokens`.** The theme importer passes each Omarchy
  `colors.toml` to `theme.tokens` and writes its `roles`, `statusColors`,
  `projectPalette` / `projectFills`, `sky`, `document` and `appearance`; the UI-1 branch
  switches its importer. Reason: `docs/ui/tokens.md` (UI-1) computed the foreground- and
  accent-over-background roles with straight alpha, and 19 of its 50 tokyo-night /
  catppuccin-latte values are one off on one channel (for example tokyo-night `dim`
  `#6c708a` against `#6b718a`). ot3 draws `Qt.rgba(fg, α)` over the background with Qt
  Quick's 8-bit premultiplied blend; the lib reproduces it (`src/theme.rs`) and checked its
  goldens for Omarchy's 22 themes against what ot3 drew on screen (1 692 channels).
  `DomainContractTest.u11ThemeTokens*` pins tokyo-night and catppuccin-latte.
- **Still temporary** (unchanged by v0.3.4): `ShellReducer.kt` / `ShellUpdates.kt`,
  `MessageSender`'s `message.dispatch` / `run.interrupt` arguments and `Backoff.kt`.
  `core/pairing/PairingLink.kt`, merged from `feat/pairing`, has to switch to
  `connectionCatalog.pairingTarget` (available since v0.3.3) in its own change.

## Pending decisions

- **Adopt compileSdk/targetSdk 37, AGP 9.x (Gradle 9) and Compose 1.12 together**, in one
  dedicated change once the AAR integration is stable.
- **Typed navigation routes** (`@Serializable` destinations with Navigation Compose)
  instead of the current string constants in `Routes`, once screens take arguments.

## 2026-10-25 — The app follows the connected environment's Omarchy theme

ot3-Android is "our Omarchy T3": by default it wears the Omarchy theme active on the host
it is connected to, as ot3 follows the live theme on the desktop. The active
`~/.local/state/omarchy/current/theme/colors.toml` is read through the T3 server
(`filesystem.getMetadata` for HOME, then `projects.readFile`) and converted with the lib's
`theme.tokens`; refreshed on connect, foreground, environment switch and by polling
`theme.name` every few minutes while visible; cached per environment. The thread's or
Home's environment decides; in the merged Home, the main environment (last used or chosen
in Settings). Manual choice among the 22 bundled themes (tokyo-night / catppuccin-latte by
default) or Material You is the fallback when the host is not Omarchy, the file cannot be
read, or following is off. All colour values come from `theme.tokens`, live and bundled;
`docs/ui/tokens.md` is regenerated from it. Spec: UI_SPEC 3.1.3. If the typed file API
cannot reach the file (path outside a project, scope), the gap goes to `LIB_REQUESTS.md`.

## 2026-10-25 — Motion like ot3, token availability charts, OmaBridge as a route

Three requirements from Andrés, specified in UI_SPEC without reopening section 1 or the
tranche order:

- **Animated like ot3** (UI_SPEC 3.4, tokens.md 5). ot3's motion was inventoried by
  surface (88 animation uses on 61 QML lines plus one shared ticker). `T3Motion.qml` is
  only the show/hide style and pace used by `T3Reveal`; every other duration is a literal,
  kept in `Ot3Durations` with exact Penner easings in `Ot3Easing` (no Material defaults, no
  springs in place of OutBack). Corrections to the first tokens table: rows arrive in
  416 ms and leave in 312 ms; a section change is a row flight with InOutQuart. Reduced
  motion keeps ot3's Animations preferences and adds Android's Remove animations (all
  off) and battery saver (Light, Saver). Acceptance: every tranche adds ≤ 10 s
  recordings from the reference emulator next to its stills in
  `docs/ui/compare/<tranche>/`; section 5 gained a Motion column.
- **Token availability charts** (UI_SPEC 4.10). Compact quota strip in Home's list
  header (UI-1) and the Usage | Limits page in Settings (UI-6), ot3's look (segment per
  account, remaining %, pace marker in `failed`, reset text). Live limits come from the
  existing stream `server.subscribeServerConfig {usageLimitSources: true}` folded by
  `quotaSources.apply`; cost and tokens from `usageSummary.*` and
  `server.getUsageSummary`. ot3's pooling and pace are JavaScript ported from T3, so they
  go to the lib as `quotaSources.present` (U26) instead of Kotlin; until then one bar per
  window.
- **OmaBridge** (UI_SPEC 4.2.1, 4.2.2, 4.9.1). Environments get ordered routes (public
  address, local network, Tailscale, OmaBridge) with a route-in-use icon in the Home
  brand row and the thread top bar (UI-1) and route editing (UI-6); the token goes to a
  route only after its descriptor names the saved environment. Kinds and fallback order
  are the lib's (U27); interim is ot3's behaviour (preferred route only, user-chosen
  kind). OmaBridge has no remote HTTP API and no Android client, so the v1 OmaBridge route
  is a gateway address (`https://host:port/` + `t3 pair`); the OmaBridge page and direct
  peer routes are blocked on the new [OMABRIDGE_REQUESTS.md](OMABRIDGE_REQUESTS.md)
  (OB1 Android client, OB2 remote actions, OB3 change feed, OB4 gateway hint). Reading
  OmaBridge's state through T3's file API was rejected: it holds the device's private key.

Lib requests U26 and U27 are in LIB_REQUESTS.md, "2026-10-25: usage limits and routes".
