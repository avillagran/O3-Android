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

## 2026-10-25: Sky header in AGSL with a Canvas fallback, ot3's saver pace, Light and Auto

- **AGSL on API 33+, Canvas below.** `STARFIELD_AGSL` is `starfield.frag` uniform for
  uniform (renames only where AGSL's single namespace needs them: `meteor()` →
  `meteorAt()`, its local `fade` → `fadeIn`; `qt_Opacity` → `opacity`), plus a
  `pixelDensity` uniform so every length stays in dp as ot3's px are logical pixels.
  Drawn with `drawWithCache` + `ShaderBrush` over one `RuntimeShader`, uniforms set in
  `onDrawBehind`. minSdk is 24, so API 24–32 draws `SkyCanvasPlan`: the same grid hash
  per star (core disc, sampled halo, tinted at the star's centre) and per comet (tapered
  quad with `tail²` stops, head glow), no nebula. Both read the math from `SkyMath`, one
  pure Kotlin file the shader mirrors, so the JVM tests cover the hashes, layers,
  twinkle, comet schedule and colours; a source test ties the AGSL constants and uniform
  names to it.
- **AGSL checked without a device** with skia-python's `RuntimeEffect` (Skia's SkSL
  front end, which is AGSL's): it compiles, and a rendered frame matches
  `SkyMath.shade()` within 0.01 / 255 on average (`scripts/check-starfield-agsl.py`,
  `docs/ui/compare/ui-1c/`). The instrumented `StarfieldShaderCompileTest` builds the
  `RuntimeShader` on API 33+ when the emulator is back.
- **Frame pace = ot3's "saver" smoothness** (`T3Ticker.qml:22-27`): 33 ms while
  something works, under explicit Full, or with a comet on screen; 125 ms for an idle
  Auto or Light sky (5 dp/s of drift looks the same at 8 fps); Light never below 40 ms
  without a comet. ot3's default "adaptive" smoothness would run comets at the display
  rate, which on a 120 Hz phone means most of the time while something works; 33 ms
  is the UI_SPEC 3.5 budget. The `withFrameNanos` loop runs only while the header has a
  window bound and the lifecycle is RESUMED, sleeps between ticks instead of waking on
  every vsync, and redraws through a counter read in the draw phase (no recomposition).
- **Light** is ot3's meaning (`T3Starfield.qml:47-48`): comets stay in header skies only.
  The header is Android's only sky, so Light differs from Full only in pace. **Off** is
  one static frame at the frozen clock. **Auto** is Full plus `onAutoDegrade`: ot3's
  counter (+1 for a tick over twice its interval, −0.5 otherwise, only while working
  with no comet on screen) over 90 switches the header to Light and calls back once so
  the preference can keep it. Animator scale 0 → Off and battery saver → Light are read
  live by `SkyHeader` (`SkyMotion.limitedBy`).
- **Energy** is an input (0 idle … 1 working) eased in `SkyClock` over 1 600 ms
  InOutSine on tick times, not with a Compose animation (which would wake every vsync);
  the first value applies at once like a QML `Behavior`'s initial value.
- **Own small types** (`SkyPalette`, `SkyMotion`) instead of the theme classes, so the
  sky has no dependency on the theme PR; Home maps its theme and motion preference onto
  them. Detail: [ui/sky.md](ui/sky.md).

## 2026-10-25: ot3 on a phone; Omarchy themes, `Ot3Theme` and JVM screenshot tests (UI-1a)

**The principle of [UI_SPEC.md](UI_SPEC.md) section 1**, recorded here as section 5 asks:

- ot3-Android is ot3 on a phone. T3 Mobile decides the screen hierarchy, navigation,
  gestures, sheets and tablet columns; ot3 decides colours, density, typography, icons,
  component shapes, wording and behaviour; `lib-t3-rust` supplies every derived value.
- When the references disagree: navigation and gestures follow T3 Mobile (system back,
  sheets instead of popups and context menus); appearance and wording follow ot3; touch
  beats density (48 dp targets by widening the hit area; only the slim row grows, 36 → 48
  dp); platform conventions beat both (edge-to-edge, IME insets, `sp`, RTL); data beats
  both (no Kotlin heuristics when the lib lacks a value); desktop-only features stay out.
- Themes: the 22 Omarchy themes ship in the APK, tokyo-night (dark) and catppuccin-latte
  (light) by default, Material You as an extra entry. This replaces dynamic colour by default.
- Visual acceptance is by JVM screenshots (Roborazzi, Robolectric native graphics), since
  the emulator on the development machine is unusable; instrumented UI tests stay for
  interactions.

**Importer** (`tools/import-omarchy-themes`, docs/ui/omarchy-themes.md):

- **Python 3 standard library** (`tomllib` for `mode` and TOML validity, ot3's regex for
  colours). It runs offline wherever Omarchy is installed and needs no Gradle module, so
  `settings.gradle.kts` stays untouched while the UI-1 parts are built in parallel. Its
  failure modes have Python tests (`tools/tests/`, run in CI); its output is checked by the
  app's JVM tests through the app's own loader (goldens, 22 themes, 17 dark / 5 light), and
  a Kotlin port of the formulas must reproduce every role of every theme.
- Source for the committed JSON: Omarchy 4.0.2 from an `omarchy-mac` checkout (`09f16de`,
  `version` 4.0.2), passed as the path argument, since this machine has no
  `/usr/share/omarchy`. Rerunning on the same themes keeps `generatedAt`, so the output is
  byte-identical.
- **Rounding**: each channel is the exact composite `bg + (fg − bg) × alpha`, rounded half
  to even. Two goldens in tokens.md are exact ties and only this rule reproduces them:
  tokyo-night `divider` green 27 + 150 × 0.09 = 40.5 → 40 (`#272836`; half up gives
  `#272936`) and catppuccin-latte `rowSelected` blue 245 − 140 × 0.075 = 234.5 → 234
  (`#e3e5ea`; half up gives `#e3e5eb`). The goldens are unchanged.
- Two roles beyond tokens.md section 1, `accentContainer` and `failedContainer` (@ 0.14),
  because the Material mapping needs them and nothing blends at runtime.
- **Project palette**: ot3 uses `pick(key, accent)`, so a missing key adds the accent
  (once). omarchy-themes.md said such keys were dropped; corrected there. Only `white`
  (no `orange`) is affected: its palette gains `#6e6e6e`.
- **`onAccent` deviates from tokens.md section 2 as written** ("falls back to `foreground`
  if contrast is under 4.5:1"). Three themes are under 4.5:1 with the background, and
  the foreground would be worse in each: catppuccin-latte 4.34 → 1.63, miasma 3.86 →
  2.28, rose-pine 3.14 → 2.12. The importer keeps the background unless the foreground
  contrasts better, which gives the background for all 22 themes, as ot3 does
  (`highlightedText`). Open for the owner: keep this, or fall back to white or black
  (catppuccin-latte 4.91 with white, miasma 5.09 and rose-pine 6.13 with black).

**Compose**:

- `Ot3Colors` (immutable, `LocalOt3Colors`), `Ot3Typography` (`LocalOt3Typography`),
  `Ot3MotionPolicy` (`LocalOt3Motion`), `Ot3Spacing`, `Ot3Radii`, `Ot3Sizes`,
  `Ot3Shapes`; `Ot3Theme { }` wraps `MaterialTheme` with the tokens.md section 2 scheme,
  transparent `surfaceTint` and `LocalTonalElevationEnabled = false`. Material roles and
  type slots that tokens.md does not map take the nearest ot3 role or size, so no baseline
  Material colour shows. `Ot3Theme { }` without arguments uses the default theme of the
  system appearance (previews, tests); the app uses `Ot3AppTheme`.
- The JSON is parsed once per process, off the main thread, started by `AppGraph`;
  `MainActivity` draws once the catalog and the appearance settings are read, over a
  window background equal to the default theme's.
- Preferences live in `core`'s `AppearancePreferences` (DataStore `appearance`): Follow
  system / Dark / Light, a dark and a light theme id, the animation preference. The
  defaults are one constant, `AppearanceSettings.DEFAULT_THEMES`. A stored id that no
  longer exists or has the wrong appearance reads as the default.
- **Material You**: accent = `primary`, background = `surface`, foreground = `onSurface`,
  and `muted` = `outline` (the spec names no source for it); other roles by the importer's
  formulas; status colours, project palette and sky constants from the default theme of
  the same appearance. Below Android 12 the default theme is used.
- **Motion**: Auto and Full → Full; Light; Off. Animator duration scale 0 forces Off; battery
  saver caps Full to Light. Light only changes the sky (ot3's preference is the
  starfield's); Off snaps every spec taken from `LocalOt3Motion`.
- Typography: line height 1.5 for body slots and prose, 1.3 elsewhere; Fira Code 6.2
  Regular and Medium bundled (docs/LICENSES.md), ligatures left on.
- System bars: transparent, icons from the resolved appearance (`SystemBarStyle.auto`
  with ot3's light/dark decision rather than the system's).

**Screenshot tests**: Roborazzi 1.77.0 and Robolectric 4.16.1, SDK 36, native graphics,
411 × 891 dp at xhdpi (822 × 1782 px), motion Off. `ScreenshotTest.snapshot(name) { }`
renders in tokyo-night and catppuccin-latte and compares with
`app/src/test/snapshots/<Class>/<name>.<theme>.png`, without tolerance; CI runs
`:app:verifyRoborazziDebug`. The first goldens are the token sheet (every role, the type
scale, Fira Code), whose sampled pixels equal the tokens.

## 2026-10-25: UI-1b: ot3 strings and Lucide icons

- **`tools/import-ot3-strings` is a Python script (standard library, run as `python3 -I`).**
  It runs by hand when `app/src/main/res/ot3-strings.tsv` changes, and its output
  (`values*/strings_ot3.xml`) is committed, so the build never needs Python or the ot3
  checkout. It only reads JSON and writes XML; Python's `json` keeps the catalog's key order
  and Unicode untouched, and `-I` keeps it from importing anything from either checkout.
  The ot3 checkout comes from `--ot3` or `OT3_CHECKOUT`; each file records the catalog's
  sha256, so a rerun on the same catalog is byte-identical (`--check` verifies that).
- **Names:** imported strings are `ot3_*`, mobile-only ones `mobile_*`; the importer fails if
  a name also exists in `strings.xml` or `strings_mobile.xml`, if a key is missing in any of
  the 20 locales, or if a translation's `{placeholders}` differ from the English ones.
  Placeholders become `%1$s`… in English order; a literal `%` in a formatted string is `%%`.
- **Locales:** ot3's `no` is written as `values-nb`; `zh` as `values-zh` (all Chinese
  locales); `values-ar` carries an RTL-review comment. `strings.xml` (the app's own strings)
  and `strings_mobile.xml` are English-only and say so with `tools:ignore="MissingTranslation"`
  on their root; the alternative, disabling the check in Gradle, would also hide gaps in the
  generated files.
- **Wording choices:** where ot3 and T3 Mobile both have a string for the same thing, ot3's
  wins (rule 4): "Add connection" for adding an environment, "Name" for the environment
  label, "Reconnecting…" for the reconnecting state, "Pull requests" and "Settings" for two of
  the three tabs, "Command Palette". Thirteen strings only T3 Mobile has were added to the
  mobile-only list in `docs/ui/strings.md`, each with its source.
- **Icons are converted at build time by a Kotlin JVM module, `:lucide-vectors`
  (`tools/lucide-vectors`)**, which `:app:generateOt3Icons` runs and AGP registers as a
  generated resource directory. Kotlin rather than the importer's Python because it runs on
  every build: it needs nothing beyond the JDK Gradle already uses, it is cached as a
  normal task with declared inputs, and its unit tests run with Gradle. It parses SVG path
  data and writes it back with explicit commands and separators (Android's `PathParser`
  does not accept every compressed form), turns `circle`, `ellipse`, `rect`, `line`,
  `polyline` and `polygon` into paths, draws every stroke at 1.75 (scaled to the viewport),
  paints in white for tinting, centres non-square view boxes in a square viewport, and
  fails on transforms or unknown elements.
- **Icon sources:** ot3's icon folder first, then ot3's inline icons in `IconAssets.js`
  (ot3 draws several of them differently from current Lucide, e.g. `pin`,
  `git-pull-request`, `settings`; ot3's drawing wins), then `lucide-static@0.564.0` for
  icons ot3 lacks (`eye`). `send.svg` is left out because `ic_send` already exists and ot3's
  composer sends with `arrow-up`. Provider marks (Claude, OpenAI, Cursor) wait for the
  screens that show providers. `Ot3Icons` maps ot3 names to the drawables; a unit test keeps
  it equal to the folder.
- **Licences:** `docs/LICENSES.md` collects the Lucide ISC (and Feather MIT) text, the T3
  mark notice, ot3's MIT notice and the machine-translation status of the catalog, for
  Settings → About → Licenses.

## 2026-10-25: UI-1d: typed routes, three tabs, sheet navigator and merged Home state

- **Typed `@Serializable` routes** (Kotlin serialization plugin) for every destination of
  UI_SPEC 2.1, in a nested graph per tab: `ChatsGraph` (Home, Thread, ThreadChanges,
  NewThread, NewThreadDraft, AddProject, CommandPalette and Home's sheets),
  `PullRequestsGraph` (PullRequests, PullRequest, PullRequestFile) and `SettingsGraph`
  (Settings, Environments, AddEnvironment, Usage, Archive, Licenses). Destinations without a
  screen show one "not yet" placeholder. This settles the pending decision below.
- **One `Ot3Navigator`** holds the rules so every screen follows them: switching tabs saves
  and restores each tab's stack; reselecting the current tab pops to its root (Android
  convention; T3 Mobile does not say); opening a thread from anywhere switches to Chats and
  resets its stack to `[Home, Thread]`; Environments and AddEnvironment live under Settings;
  saving an environment returns to Home. The old `environments → sidebar → thread` graph and
  `connect` are gone: `AddEnvironment(mode?, pairingUrl?, environmentId?)` is today's URL +
  token form (`environmentId` edits a saved one) and Home is always the start destination,
  with "No environments connected" + Add environment on a fresh install.
- **Thread deep link `ot3a://thread/{environmentId}/{threadId}?prompt=`** is parsed by
  `ThreadDeepLink` and opened with `openThread`, not declared as a Navigation deep link: an
  in-app Navigation deep link into a nested graph replaces the back stack with the leaf and
  drops Home from under the thread.
- **Sheet navigator:** a custom `Navigator` (`SheetNavigator`, modelled on Navigation's
  `DialogNavigator`) whose destinations are `FloatingWindow`s declared with `sheet<Route>()`.
  Each sheet is a real back-stack entry, so it has its own saved state, view-model store and
  lifecycle, survives process death with the back stack, and back pops it; `SheetHost` draws
  each entry as an M3 `ModalBottomSheet` (predictive back and swipe-down dismiss pop it) and
  adds an activity-level back handler so the order holds whichever window has focus. Used
  for Thread actions, Project actions, Thread list options and Snooze until; they live in
  the Chats graph and share Home's view model, scoped to the `ChatsGraph` entry.
- **Back order (2.3):** the top sheet, then the keyboard (the IME consumes back itself),
  then Home's search (its handler runs only while Home is the current entry), then detail →
  list. Predictive back is on (`android:enableOnBackInvokedCallback`).
- **Merged Home state in `core`:** `HomeStateBuilder` is a pure function of every saved
  environment's connection and shell plus search, filters, shelves and paging; the app's
  `HomeViewModel` only wires flows (supervisors started there, a 1 s clock while a row shows
  an elapsed time or a countdown, 30 s otherwise). Rows are keyed `<environment>/<thread>`;
  the environment label appears only with more than one saved environment (T3 Mobile).
  Search matches title, project and environment and includes settled rows, whose shelf
  stays collapsed if the user collapsed it (ot3's user rule); from two characters a
  "Messages" section adds `orchestration.searchThreads` from every connected environment,
  250 ms after the last keystroke, merged as ot3 does. Status filter counts follow the
  project and environment scope, never the search; count pills ignore filters and settled
  rows. Grouping and the collapsed state of the Working, Snoozed and Settled shelves live
  in DataStore (`HomePreferences`); settled rows page 10 then 25 at a time (T3 Mobile's
  `THREAD_LIST_V2_SETTLED_INITIAL_COUNT` / `_PAGE_COUNT`). The header text is T3 Mobile's
  workspace status, shown 800 ms after it first appears; "You are offline" is modelled but
  never produced until the app reads the device's network state.
- **Menus as data:** `ThreadActions` lists ot3's whole thread menu, project menu, swipe
  actions and snooze presets with ot3's visibility and capability rules
  (`threadPinning`, `threadSettlement`, `threadSnooze`, `threadTitleRegeneration`,
  `threadAutoSettleOptOut`, write access) behind an `ActionContext`. Today's context
  (`ActionContext.Interim`) knows no capability, no write access and no reachable command
  builder, so the Thread actions sheet offers only Copy ▸ Path / Branch / Thread ID and
  Filter by {project} / All projects, the Project actions sheet only Copy Path, swipes are
  modelled but no gesture is attached, and Snooze until lists its presets disabled. Later
  PRs flip the flags (request item 4 for the builders, UI-5 and UI-6 for the screens).
- **What stays interim, and what removes it:** row order, shelves and the snooze rule come
  from the temporary `SidebarModel.kt`, extended to several environments and still the one
  temporary sidebar file (U2 replaces it; observed returns from Working and subagent
  nesting are not tracked until then); statuses map `NeedsYou` to Approval or Input,
  `Working` and `Failed` onto ot3's ten-state enum, with no Done, Plan ready, Woke, Limited,
  Waiting or Goal (U2); the project chip is the project title's first letter on an accent
  tint (U21); projects are not grouped by repository (U2). Home, sheets and tabs are plain
  Material composables until the kit and the visual Home restyle them. ot3 writes the
  elapsed time as "3m 5s" (`T3Panel.qml:541-545`); this follows the spec's "3m 05s".

## Pending decisions

- **Adopt compileSdk/targetSdk 37, AGP 9.x (Gradle 9) and Compose 1.12 together**, in one
  dedicated change once the AAR integration is stable.
