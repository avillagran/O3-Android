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

**Importer** (`tools/import-omarchy-themes`, docs/ui/omarchy-themes.md; replaced in UI-1f
by the lib's `theme.tokens`, see that entry):

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

## 2026-10-25: UI-1e: the component kit takes presentation values

- **The kit's models are the lib's fields, one to one.** `ThreadRowModel`, `RowStatus`,
  `RowTime`, `ProjectChipModel`, `PrBadgeModel` and `CountPillsModel` hold what
  `sidebar.view` and `projects.identity` (lib 0.3.3) return: `statusRole` → `StatusRole`,
  `statusLabel` → label, `time{kind,label}` → `RowTime`, `color`/`colorSlot`/`fill` →
  `ProjectChipModel.fromLib`. The kit derives nothing about a thread; what stays in Kotlin is
  ot3's QML presentation: the detail → icon map (`T3ThreadRow.qml:271-277`), "Working" plus
  the elapsed time, the done age under the title, the snooze countdown in `working`.
  `StatusRole.MUTED` (the lib's `muted`, ot3's fg @ 0.6) is its own role rather than `dim`
  (fg @ 0.57), so the sampled colour is ot3's.
- **No ripple; ot3's pressed fills.** Rows fill `rowPressed`, icon buttons fg @ 0.07, buttons
  a lighter accent or fg @ 0.08, shelf headers `rowPressed`, animated over 130 ms. A Material
  ripple on top of ot3's flat fills would be a second, non-ot3 feedback. Keyboard focus draws
  ot3's 1 dp accent ring.
- **Touch targets:** icon buttons, switches, buttons and selects reserve 48 dp
  (`minimumInteractiveComponentSize` or a 48 dp box) while drawing ot3's sizes; PR badges and
  the subagent toggle inside rows rely on Compose's own touch-bounds expansion, so the row
  layout keeps ot3's spacing.
- **Card rows are 82 dp including 2 dp above and below the card**, as in ot3, so stacked
  cards sit 4 dp apart without list spacing.
- **Tooltip colours:** Omarchy's `Color.tooltip` is not in `omarchy-themes.json`; the tooltip
  takes ot3's popup look (`background`, 1 dp `cardBorder`, radius 7) until the importer
  carries the tooltip palette.
- **Sheet stops:** Material 3 1.4 has no custom detents, so `Ot3SheetStops.PARTIAL_AND_TALL`
  uses Material's partial stop (half the window) and then 0.92; the per-sheet fractions of
  UI_SPEC 2.2 (0.55, 0.65…) wait for custom detents. The sheet's 1 dp top hairline is drawn
  by the full-width handle slot because `ModalBottomSheet` ignores borders on its surface.
- **Dialogs** are `BasicAlertDialog` with ot3's card (`Ot3DialogSurface`), 24 dp from the
  window edges, and the window dim set to 0.5 to match the sheet scrim.
- **Live activity text with motion Off is drawn whole**, not at ot3's resting half opacity,
  so a live label does not read as disabled when animations are off.
- **The component catalog lives in the `debug` source set** with its own launcher entry
  ("UI components"), so it ships in no release build and needs no route in the app's
  navigation; its goldens run from `src/testDebug`. Screen captures (sheet, dialogs,
  tooltip) drive the test clock by hand; under Robolectric a text field inside a dialog
  window never lets Compose go idle, so the Rename dialog's card is captured inline and the
  window capture uses the Archive dialog.

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
  when the lib's `pairingTarget` lands. (Deleted in UI-1f: `PairingTargets` calls
  `connectionCatalog.pairingTarget`, and the table runs against it as `PairingTargetsTest`.)
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

## 2026-10-25: UI-1f: Home on `sidebar.view`, colours from `theme.tokens`, pairing links from the lib

- **Home is `sidebar.view`.** `HomeStateBuilder.view` passes every saved environment
  (shell, name, connection state while not connected) and Home's view state (search text,
  status filter, project or group key, environment, grouping, the previous `returns`) to
  the lib through `DomainEngine`, off Main; `build` maps the result. Rows, their order,
  `detail`, `state`, `statusLabel`, `statusRole`, `time`, shelves, sub-thread nesting,
  `counts`, `filterCounts`, `projectGroups` and `projectIdentities` are the lib's. Gone:
  `SidebarModel` (deleted on main), `StatusMapping`, Kotlin's `RowTime`, the interim
  first-letter chip, Kotlin search and filter matching, and the project list sorting.
  What stays in Kotlin is view state: which shelves are open, T3 Mobile's settled paging
  (counted in top-level rows), the server's message search (merged across environments),
  the connection header, and ot3's card / slim choice per row. The view model rebuilds
  the view on every tick (1 s while a row shows an elapsed time or a countdown, else
  30 s), so times are never recomputed in Kotlin. Rows draw with the kit's card and slim
  rows (`HomeRow.toThreadRowModel`); a long press on the chip opens the project's actions.
- **Pairing links: `connectionCatalog.pairingTarget`.** `PairingLink.kt` is deleted;
  `PairingTargets` calls the domain function and returns the redacted `PairingTarget`.
  The 70-input table runs against it (`PairingTargetsTest`), with no difference.
  Pairing's screens became typed Settings routes (`PairEnvironment`, `ScanPairingCode`).
- **Colours: `theme.tokens`.** The importer is a JVM task over the host library
  (`:core:importOmarchyThemes`, wrapped by `tools/import-omarchy-themes`), so it computes
  no colour; the JSON (schema 2) is the lib's output per theme. The Python importer and
  its tests are gone, and so is `Ot3Colors.derive`: Material You writes the wallpaper
  scheme as a `colors.toml` and goes through `ThemeTokens.fromColorsToml`, which the live
  Omarchy theme (UI_SPEC 3.1.3) will use too. Material You's colours arrive a few
  milliseconds after start; the theme draws nothing until the first colours exist and
  keeps the shown ones while new ones resolve. The values move to Qt's blend: 19 of the
  50 tokyo-night / catppuccin-latte goldens in tokens.md change by one step on one
  channel, every screenshot golden was re-recorded, `onAccent` is unchanged for all 22
  themes. Not from the lib: the Material containers (accent and failed @ 0.14, blended by
  `Ot3Blend`), requested in LIB_REQUESTS.md.
- **JNI in JVM tests.** App unit tests get the host library on `java.library.path` as
  core's do. Tests that call the lib are plain JUnit, not Robolectric: each Robolectric
  sandbox has its own class loader and a JNI library loads in one class loader only.

## 2026-10-25: UI-1f: ot3 strings generated at build time from the lib's AAR

- **`tools/import-ot3-strings` reads the catalog from the AAR.** It opens
  `libs/lib-t3-rust-android.aar` and reads `assets/lib-t3-rust/i18n.json` instead of
  `translations/i18n.json` in an ot3 checkout; `--ot3` and `OT3_CHECKOUT` are gone. The lib
  version comes from the AAR's `META-INF/lib-t3-rust.properties` and must match the tag in
  `libs/lib-t3-rust.lock`; every `strings_ot3.xml` header names that version, the asset and
  the catalog's sha256. On v0.3.4 the asset is byte-identical to the ot3 file used so far, so
  regenerating changed only the headers.
- **Build-time generation, not runtime `i18n.t`.** Looking strings up through the lib at
  run time would cost a JNI call per string per recomposition, and domain calls belong off
  the main thread, which a `Text` cannot wait for. Android resources give locale selection
  and per-app language, RTL handling, lint (missing translations, format arguments),
  Compose previews and Robolectric screenshots without the native library, and
  `stringResource` caching.
- **Drift is caught in CI.** The catalog is pinned by the AAR, which is pinned by the lock;
  CI runs `python3 -I tools/import-ot3-strings --check` after fetching the AAR, so a lib bump
  that changes any imported string fails until the outputs are regenerated and committed.
  The outputs stay committed and the Gradle build never runs Python.
- **Runtime `i18n.t` / `i18n.catalog` stay available** for English sources the lib returns
  at run time, if a later screen needs them. Today none does: Home maps the lib's `detail`
  to its `ot3_status_*` resources.

## Pending decisions

- **Adopt compileSdk/targetSdk 37, AGP 9.x (Gradle 9) and Compose 1.12 together**, in one
  dedicated change once the AAR integration is stable.

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

## 2026-10-25: Live Omarchy theme, implementation (UI-1g)

Implements the entry above (UI_SPEC 3.1.3) as data in `core`, for the UI-1 theme to wear.

- **Public API** (`core/theme`): `LiveThemeSource.selection: StateFlow<ThemeSelection>`,
  where `ThemeSelection` is `Live(environmentId, environmentLabel, themeName,
  displayName, colors, tokens: JSONObject, fromCache)` or `Fallback(reason)`, `reason`
  one of `Loading`, `FollowingOff`, `NoEnvironment`, `Waiting(environment)` and
  `Unavailable(environment, NotOmarchy | NoScope(requiredScope) | Error(code))`.
  `tokens` is the lib's `theme.tokens` result (same schema as the bundled themes once UI-1's
  importer uses `theme.tokens`), so `Ot3Colors` is built from either the same way.
  `showOnScreen(environmentId): AutoCloseable` (sidebar and thread screens claim their
  environment; the latest open claim wins, so a navigation transition never leaves none),
  `setVisible` (process ON_START / ON_STOP), `refresh()`.
- **HOME comes from `filesystem.browse`, not `filesystem.getMetadata`.** Checked live on
  T3 0.1.3 (PROTOCOL_NOTES.md, "Live Omarchy theme"): `getMetadata(["~"])` answers
  `{kind}` only, no path. `browse("~/.local/state/omarchy/current/theme")` answers the
  expanded `parentPath`, so HOME is that minus `/.local/state/omarchy/current`.
  `getMetadata` still runs first, as the cheap "is this Omarchy" check (a null entry; `browse`
  fails with `rpc_rejected` when the directory is missing). Both run once per environment
  and process; then `projects.readFile(cwd = HOME, ".local/state/omarchy/current/theme.name"
  and ".../theme/colors.toml", allowMissing = true)`. **`readFile` accepts HOME, a directory
  that is no project, as `cwd`** (live); `cwd = "~"` is `rpc_rejected`. Gap and the call
  wanted instead: LIB_REQUESTS.md, "2026-10-25: live Omarchy theme".
- **Poll:** one `readFile` of `theme.name` every 3 min while the app is visible and the
  followed environment connected; a different name triggers the full read. Full reads also
  on every new supervisor `Connection` (connect and reconnect), on return to the
  foreground, on an environment switch and on `refresh()`. Nothing runs in the background.
  Following an environment starts its supervisor (`ConnectionManager.supervisor(id).start()`),
  so the merged Home's main environment connects for its theme.
- **Which environment:** the latest on-screen claim; else `ThemeSettings.mainEnvironmentId`;
  else `SelectionPreferences.lastEnvironmentId` (now written when a sidebar or thread opens);
  else the most recently connected one.
- **Failures:** an `Error(code)` keeps the theme shown (from a read or the cache): a dropped
  connection must not flip the colours; polls retry it. `NotOmarchy` and `NoScope` drop the
  environment's cache and fall back to the manual choice; polls do not probe such a host
  again until the next connect or foreground. `NoScope` is decided by the code
  `write_not_authorized`, with `filesystem:read` as the scope: the lib's `requiredScope`
  names the wrong scope (LIB_REQUESTS.md).
- **Cache:** `FileThemeCache`, one JSON file per environment under `files/live-theme/`
  (name = SHA-256 of the local id; theme name, display name, `colors.toml`, tokens; never a
  path or credential). Shown at once with `fromCache = true` until the environment is read.
  `Fallback(Loading)` covers the first milliseconds, so a UI can keep its splash instead of
  painting the manual theme first.
- **Preferences:** `core/data/prefs/AppearancePreferences.kt` and its test are copied
  byte for byte from `feat/ui-1` (b666c97), so the merge adds the same file on both sides.
  `ThemePreferences` adds `followEnvironment` (default on) and `mainEnvironmentId` in their
  own DataStore (`live_theme`) and exposes them with the manual choice
  (`AppearancePreferences`: mode, dark and light theme ids, `material-you`) as one
  `ThemeSettings` flow. `AppGraph` builds `ThemePreferences(context,
  AppearancePreferences(context))` without a graph property of its own, so merging with UI-1's
  `AppGraph.appearancePreferences` declares nothing twice; both instances use the one
  `appearance` DataStore the context delegate holds per process.
- **Settings → Appearance** (`ui/settings/AppearanceSettingsScreen.kt`, reached from the
  environments list until UI-1's Settings tab): the follow switch with "Theme · environment"
  or the reason, the main environment when more than one is saved, then Theme / Dark theme /
  Light theme. Until the bundled catalog (UI-1) the theme lists hold the default, the stored
  choice and Material You. Strings in `values/strings_appearance.xml` (docs/ui/strings.md,
  "Appearance"). `main`'s `Ot3Theme(dynamicColor = true)` is not changed: painting the
  selection is the UI-1 theme's job.

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

## 2026-10-26 — UI-1 live-theme integration

- The typed navigation graph exposes Appearance from Settings and Environments. The
  Settings page lists the actual bundled catalog, rather than only the former defaults.
- MainActivity consumes LiveThemeSource.selection. Native live tokens override the manual
  appearance; fallback uses the saved manual settings. The first Compose theme waits
  until the initial cache/environment selection is resolved. System bar appearance follows
  the actual colours. Changing theme keeps the chat composition and its draft alive.
- A visible thread claims its environment and records it as last used. The merged Home
  uses the configured main/last-used target. The source does not even request a supervisor
  connection flow while backgrounded, so switching a preference cannot restart a shell
  after the lifecycle has stopped it. Reads and polls remain foreground-only.
- Existing UI-1 navigation, Home/kit/motion and native colour/string generation are kept.
  This integrates UI-1g's reader/cache/settings with the production theme path, rather
  than treating its core tests as evidence that the screen already followed the host.

- Managed route/credential changes replace the shared supervisor. Thread and live-theme
  consumers follow that replacement without replacing the screen or its draft. Starts
  are guarded by current supervisor identity and foreground state, so a forgotten
  transport cannot be revived and a background consumer waits for foreground resume.
  Regression tests cover shared consumers, replacement and deferred background starts.
## 2026-10-26 — UI-N: notifications and Live Updates without a relay

Andrés asked for the official T3 app's Android 16 experience (status bar chip, lock
screen and shade card while an agent works; normal alerts for approval, question, done and
failed) with no FCM, relay or push server. Specified in UI_SPEC 4.14; tranche UI-N runs
after UI-2a's timeline reader and the bridge catalog integration; earlier tranches keep
their names and states.

- **Semantics in Rust.** Counts are `sidebar.view` `counts`; phases, card, alerts, dedupe,
  first-observation silence, freshness, deadlines and chip kind are requested as
  `awareness.view` (LIB_REQUESTS U28). Kotlin only maps the result onto Android
  notifications. The runtime does not start before U28.
- **One shell stream per environment.** The follow service holds leases on the existing
  `ConnectionSupervisor`s; `ON_STOP` releases only UI leases. No second connection,
  reconnection loop or credential path.
- **`specialUse` foreground service**, opt-in, started only from a visible app, stopped
  after 15 idle minutes (T3's finished-card lifetime), on Disconnect, toggle off,
  `onTimeout` or revocation. `dataSync` rejected for its targetSdk 35+ 6 h / 24 h cap and
  its transfer-only purpose.
- **Promotion is requested, never assumed.** Promotion request only while active and
  ongoing, uncolourised; Settings shows `canPostPromotedNotifications()`; only
  `FLAG_PROMOTED_ONGOING` on a posted card counts as evidence. Live Update settings use
  `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` (present in the SDK; the guide's
  `ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS` is not).
- **Approve never approves from a notification.** Every action opens the thread deep link;
  confirmation and the `approvals.command` / outbox path are UI-4's visible card.
- **Lock screen** defaults to Full, matching Andrés’s requested thread/project/host card;
  Status only ("OT3 · Working") and Hidden remain optional, subject to system privacy settings.
- **Status icon** is the bundled T3 mark path, recentred and white (`ic_stat_ot3`); no
  generated or raster image. Adaptation of T3 Mobile's MIT module is attributed in
  `docs/LICENSES.md` and `app/.../notifications/NOTICE.md`.
- **Upstream policy (Andrés, 2026-10-26, latest instruction):** findings that belong to
  T3 Code are prepared as a local patch or written finding for review and are **not**
  sent, nor is any upstream PR opened or reopened, without Andrés's explicit approval.
  Our work never waits for an upstream merge.

## 2026-10-26 — local follow runtime

- lib v0.3.5 awareness.view owns phases, counts, alerts, suppression baseline and deadlines.
  Kotlin keeps its baseline only for the service lifetime and cold-seeds after process death.
- Follow leases borrow ConnectionManager supervisors. Foreground screens share them;
  backgrounding stops only unleased supervisors. Last background release stops its
  supervisor, and route replacement retains lease ownership without reviving the orphan.
- The user opts in from visible Settings after the notification explanation/permission.
  A specialUse foreground service follows shell snapshots, uses native nextDeadline,
  wakes existing subscriptions on resume/network changes, and requests no wake lock or
  battery exemption. It stops on opt-out, empty catalog or 15 idle minutes.
- A quiet service notice states that local following is running and offers Stop. Native
  activity cards are separate so completed/failed cards can be ordinary expiring
  notifications rather than being forced ongoing by the service flag. This creates a
  second quiet notice while an activity card is shown; device UX acceptance remains open.
- Service lifecycle/Doze, revoked-authority handling, system promotion/lockscreen and
  API36.1 emulator evidence still require verification. No promotion is claimed from flags
  requested on a builder.

## 2026-10-26 — verified live follow presentation correction

Supersedes the separate live-card/service-notice policy: while live, the native activity
card is also the foreground-service notification; idle tracking uses a quiet service
notice so finished cards can expire normally. API36.1 real NODE evidence confirms actual
promotion, background continuity and removal after revoked reauthentication. The OT3
monochrome mark supplies chip branding and short critical text supplies the state:
a longer brand prefix hid that text in the observed system UI. Full lock-screen content
remains the default; lock-screen capture and remaining lifecycle cases are not accepted
yet. See docs/ui/compare/ui-n/runtime/live-node-verification.json.

## 2026-10-26 — notification controls

Settings now persists approval/input/plan/completion/failure preferences and sends them to awareness.view. Rust owns alert suppression and baseline consumption. Full lock-screen detail remains default; State/Hidden update already posted notifications and summaries, preserving absolute expiry and routes. System promotion availability refreshes on resume. See docs/ui/compare/ui-n/controls/README.md for tests and boundaries.

## 2026-10-26 — Omarchy palette selection and refresh

Selecting a bundled Omarchy palette in Appearance now immediately chooses its dark/light mode and disables desktop follow; turning follow back on explicitly restores the environment theme. Previews consume the existing native theme.tokens colors. A refresh button is available, and foreground polling reads colors.toml as well as theme.name so same-name custom edits are detected. Background polling remains disabled; desktop files are read-only. Spanish labels are included. Evidence and live-verification boundaries: docs/ui/compare/omarchy-themes/README.md.

## 2026-10-26 — Usage page reads the shared native projection

Replace the Settings Usage placeholder with the supported cost/token increment.
Use the existing environment Connection transport and Rust usageSummary request /
aggregate; no extra shell stream, date-window arithmetic or aggregation in Kotlin.
Local date formatting uses APIs available at minSdk 24. Period/environment changes
cancel reads, and connection replacement invalidates the result. Preserve selection
with the navigation entry SavedStateHandle. Limits and pricing refresh are separate
remaining work, not inferred from the synthetic summary used in screenshot tests.

### 2026-10-26 — Visible quota stream and probe pacing

The Limits tab owns a typed server-config stream only while its selected environment
is visible. Rust quotaSources.apply folds and sanitizes observations; Android only
controls lifetime, retry and presentation. The application shares a monotonic
five-minute probe gate per environment, counting failed attempts. Until U26 exposes
the common presentation, display native usedPercent and absolute reset times without
recreating pooling or pace rules. Screenshot inputs are frozen native outputs; actual
JNI stream/reducer behavior is tested separately to avoid Robolectric classloader
collisions when the complete suite runs.

### 2026-10-26 — Shared visible quota ownership and opt-in strip

Home, thread and Limits acquire the application SharedQuotaSource by environment.
The final release cancels and joins the stream/reducer entry; reconnect replaces it,
without retaining a background subscription. An independent application refresh gate
continues to count every attempted probe for five minutes. Keep reader/composer and
shell lifetimes unchanged: Home owns a stable lazy-list header slot, and the thread
owns a stable slot under its real toolbar. The shared Appearance DataStore preference
is off by default and is also available in both overflows. Group taps use a typed
Limits route with an optional environment, preserving the calling screen's Back path.
Until U26, use native used percentages and platform absolute reset dates with static
bars; do not implement desktop pooling, remaining/pace or notices in Kotlin. Details,
validation and remaining differences: `ui/compare/quota-strip/README.md`. The Usage /
Limits tab fade is deferred to a separate increment.

### 2026-10-26 — Local diff reading controls

File navigation and wrap are presentation state, not protocol operations. Preserve
the selected native path (including oldPath fallback after rename) and wrap mode
across dialog recreation; neither control fetches again. Unwrapped width comes
from Fira Code font metrics, as ot3's T3DiffReview does, and the patch scrolls as
one surface. Rust remains responsible for scope, patch parsing and line limits.


### 2026-10-26 — Diff modal lifetime across viewport changes

Keep the open diff at reader scope, keyed by the selected thread, rather than inside
a virtualized Changes row. Device rotation can dispose that row while the modal is
open. A real-workspace regression now checks both orientations and a single stream;
standalone dialog restoration was insufficient to catch this failure.

### 2026-10-26 — Read-only pull request navigation

Replace the PR list/detail placeholders with typed native reads. Keep host in the
route as an optional backward-compatible field; a list row and Home badge supply
the native reference, including enterprise host. Never synthesize a provider URL.
Rust builds recipes, validates typed inputs, derives badges and parses Markdown;
Android owns scope selection, view lifetime, scroll and retry presentation.

An explicit project is never broadened to All projects when shell metadata is
missing. A removed selected environment is unavailable until another is selected.
Environment/project changes are atomic; reconnect cancels the previous generation
and clears its data/cursors. Server pages are appended verbatim until U31 supplies
common merge/order semantics. Comments and review-thread bodies use the existing
Markdown reader. PR patches and all mutations remain absent, visibly/documentedly.


### 2026-10-26 — Native attachment image access and bounded rendering

Read native attachment descriptors through T3Client.image rather than constructing
asset URLs in Android. Kotlin owns only lifecycle, connection-generation guards and
platform decoding with bounded dimensions. Keep the image dialog at reader scope
so virtualization cannot dismiss it during rotation. Native calls have no per-read
cancel handle; UI cancellation discards late results while Rust retains its deadline.
Markdown media-file references are not attachment IDs and await U30 JNI exposure.

### 2026-10-26 — Pull request return state and ownership

Keep one private page cache and actual LazyListState in the list navigation-entry
ViewModel. Back/recreation may reuse pages/cursor/visible pixel offset only after the
current exact scope and Connection transport identity/epoch validate. While hidden,
observable payloads expire and no connection/project collection or lease remains.
Do not compose an empty list during validation: it would clamp the retained anchor.

A different/offline connection, scope/filter/query change or explicit Refresh clears
the cache and resets position. The first effective environment is pinned, avoiding
a deleted default silently switching accounts. Process death refetches; large payloads
and list position are not saved into process bundles. Keep page data verbatim until
U31 supplies common merge/order. A session-tagged buffered More action covers the
publication/receiver-registration gap without replaying canceled/offscreen actions.

### 2026-10-26 — Explicit Android image clipboard offers

Use the same native binary attachment read as previews, under the shared two-job
semaphore, when the user presses Copy image. Offer the original bytes and validated
MIME through a read-only Content URI, never the sampled preview bitmap. Android
receivers resolve a ContentProvider URI; desktop's wl-copy PNG normalization is not
needed for this platform contract. No Kotlin asset URL, HTTP client or protocol parser.

Expose only `cacheDir/clipboard-images/` through a non-exported, grant-enabled
FileProvider, using UUID filenames and a fixed MIME/extension mapping. Keep at most
eight 10 MiB files; remove files aged at least 24 hours on startup/next copy,
revoking URI grants before deletion. This is opportunistic cleanup, with no expiry
timer; Android may also reclaim cache. Existing explicit clipboard offers remain
usable across disconnect until cleanup/reclamation; no durable gallery is implied.
Unpublished files are deleted on failure/cancellation. Recheck the live connection
immediately before publishing and cancel UI work on lifecycle/generation changes.
Use the existing kit CopyButton's busy/check/error feedback and 1.8-second reset;
show its 48 dp touch action on thumbnails and viewer rather than requiring hover.

### 2026-10-26 — Touch queue input, authoritative order

Use a 48 dp grip with immediate source opacity0.5 and 2 dp accent insertion marker.
Pointer position chooses native beforeRunId/null input; server rows are never reordered
optimistically. Stable run-order/pending/eligibility/connection/edit/orientation inputs
cancel stale drags without reacting to JSON object recreation. Keep long queues usable
through grip Up/Down menu/TalkBack actions rather than introducing edge-autoscroll.

Continue through MessageSender's durable queue path and reuse error/unknown retry
feedback inside the sheet. queue.derive supplies capabilities; queue.command validates
shape, not projection membership. Map projected queue-edit rows to documented native
inputs before invoking that strict builder; preserve original attachments/context and
unsent draft. JNI and a real queue-edit rotation test exposed the prior metadata bug.


### 2026-10-26 — attachment image zoom is UI geometry

Add touch pinch/pan and double-tap to the existing fullscreen attachment reader,
following T3 Mobile's double-tap-enabled ImageViewing rather than claiming desktop
QML has zoom. Keep a bounded1–4× immediate transform, Fit aspect ratio and clamped
normalized translation; no new gesture animation or protocol. Save only transform
floats plus validated UI ownership. A bounded current-Connection token in the VM
keeps rotation coherent and prevents another environment/connection/process inheriting
a saved transform. Closing resets. Existing binary read, decode limits/semaphore and
original-byte copy remain authoritative. Markdown/video/live acceptance stays open.
### 2026-10-26 — one composer surface with UI selection ownership

Expand the existing thread composer rather than opening a second route/VM or modal.
Keep draft text in ThreadComposer/Room; save only cursor/selection/focus per canonical
environment/thread identity. A transient UI edit token pairs local text with its echo
so delayed combined-flow emissions cannot overwrite rapid typing. Null-token external
updates remain authoritative. Queue edits retain their separate normal selection and
existing attachment/context ownership. Explicit user edits, including clearing to
empty before Room load, win over initial persisted draft loading.

Back/Esc closes expanded mode and IME on phones. Soft/virtual Enter stays newline;
physical compact Enter sends, expanded Ctrl+Enter sends. Repeat/composition do not
send. Unsupported alternate delivery/new-thread shortcuts remain inactive. No new
protocol, persistence payload, remote stream or animation is introduced.

### 2026-10-26 — Expanded composer with a large landscape IME

The explicit landscape+visible-IME test exposed a 4 px text viewport: header and
options consumed the available 394 px window above IME. Below 220 dp remaining
window height, options move to the header overflow, preserving text and touch
targets. Finite toolbar width also restores the weighted model selector that an
unbounded horizontal-scroll wrapper hid. Tests now set/restore the emulator's
show-IME-with-hardware-keyboard preference themselves, assert visible IME in both
orientations and verify the model control in overflow. Rotation waits for the new
decor window bounds before querying Compose/Espresso; no arbitrary delay or wider
assertion tolerance was added.

### 2026-10-27 — Quota menu reads loaded preferences and owns accepted writes outside the popup

Delayed-DataStore Compose regressions on API34 proved an enabled false default before
first load and cancellation of an accepted write after menu disposal. Show native
Loading/disabled until the preference arrives; launch persistence in AppGraph.appScope
and invert atomically inside DataStore. Preserve soft IME/draft in the graph regression.
The precise cause of PR51's earlier CI timeout remains unproved; no timeout increase
or keyboard dismissal masks that portrait path. See `ui/compare/quota-strip/api34-menu/`.

### 2026-10-26 — follow owns leases, Rust owns awareness

Keep one follow lease per saved environment across metadata updates. Switching
observers to a replacement supervisor preserves that environment's lease; retired
unauthorized callbacks cannot close its replacement. Current privacy is an input to
every card build. Compare complete parsed card+privacy before Android publication,
without skipping awareness evaluation or alerts. Recover missing/stale Android
records only on the existing deadline wake, identified by a random UI-only extras
stamp; Room ticks must not postpone that wake. Avoid duplicate cancels and privacy
refreshes after actual NMS queue shedding was observed in the synthetic system lab.

### 2026-10-27 — Saved thread projections belong to the current local credential

Resolve a domain-separated SHA256 authority over local environment identity, route and the
high-entropy credential under EnvironmentRepository.mutations, reusing its existing
token gate. Persist only the digest. Observe distinct authority changes even while
connection remains null; invalidate held state and block the old Connection identity.
Metadata-only edits, including first-discovered server UUID, retain valid offline cache.
The saved local ID/route/credential bind the cache; independent advertised-UUID isolation
is not claimed. Guard remove with lease ownership too.
No independent expiry timer or Kotlin protocol authorization policy is added; idle
natural expiry without an existing signal/read remains unaccepted. See
`ui/compare/feedback-cache/authority-safety.md` for tests and exact limits.

### 2026-10-27 — Follow resilience is visible re-entry with a cold baseline, not a restart promise

A loopback server in a separate host JVM survives app SIGKILL. An opt-in isolated
instrumentation APK has a different UID and controls DeviceIdleController/key events;
the real application is not instrumented during lifecycle acceptance. No helper IPC,
permission or dependency enters the product. Measured State/Full process cycles retained
opt-in/privacy and restored following on visible reopen, without reposting two native
child alerts. No automatic restart was observed for bounded5-second windows; this does
not emulate LMK, force-stop or OEM policy. The service remains START_NOT_STICKY.

Forced deep Doze retained the FGS; Working remained until a controlled connection
failure was observed, then Rust supplied Waiting and recovered. Loopback does not
prove Android network enforcement or natural maintenance windows. Wake-probe chronology
records fresh frame/blackhole/pings and successor streams at4.525s/5.418s; network
recovery is not attributed exclusively to wakeSubscriptions. Baseline is session-local,
not DataStore; idle is fixed15min, not configurable. Natural/legacy expiry stays open.
See `ui/compare/ui-n/resilience/README.md` and its native system checks.

### 2026-10-27 — Legacy expiry is presentation scheduling, not awareness policy

API24/25 now schedule received absolute Rust deadlines via inexact non-wakeup RTC
alarms and a non-exported receiver. Persisted slot generations and presentation stamps
protect replacements and privacy refresh from stale callbacks/NMS snapshots. API26+
keeps its native timeout. No exact alarm permission or wake-during-idle request is added;
cleanup may be delayed. Summary lifecycle remains pending rather than risking removal
of enqueued children. [Verification and limits](ui/compare/ui-n/legacy-expiry.md).

### 2026-10-27 — Reveal choices are local UI preferences; effects snapshot uniforms

Show/hide style and pace persist in Appearance and reach both reveal axes through
the shared theme policy. They follow T3Motion/T3Reveal's five choices and timings;
shader capability failure falls back to Spring and reduced motion remains immediate.
Instant uses a direct target branch so it also interrupts an unchanged animation
target. These presentation settings add no protocol behavior or server requests.

RuntimeShader is cached per reveal, but the attached RenderEffect is created after
the current frame's uniforms. Android/Skia copies the builder into the image filter;
reusing an effect created before uniform updates left moving content empty. A real
GPU pixel regression demonstrated the failure and its correction on both axes.
The capability probe may be cached; its effect must never be attached to a frame.
The shader's artistic code remains unchanged and no effect stays attached at rest.
See [source references, regression and capture limits](ui/compare/reveal-styles/README.md).

### 2026-10-27 — Explicit tool inspection uses existing native views

The reader renders toolInspector.parse fields from lib035 without classifying
protocol events in Kotlin. Selecting one activity entry owns one cancellable
read; expanding an activity group does not eagerly fetch all entries. Tabs reuse
that result and retry performs a fresh explicit read. A post-read cancellation
check protects against a non-cooperative old request completing after selection
changes. Bounded selectable sections and copy preserve native truncation flags.

The inspector uses existing OT3 buttons, icons, colors and shared AAR strings.
Follow-live command output and desktop popup placement remain explicit gaps;
this change adds neither fabricated polling nor a completion claim for UI-2.

### 2026-10-27 — Inspector lifetime belongs to the reader

The phone inspector is a reader-owned window, not part of a recyclable activity
row. This follows the spec's sheet/popup ownership and keeps selection through
rotation or source-row disposal. The previous inline landscape close failed in
API34 CI; its exact touch/layout cause remains unproven. The replacement is
verified by a compact-viewport close test and a row-replacement lifetime test,
with device acceptance still required. Short content wraps instead of reserving
a mostly empty full-height panel. No subscription or protocol rules moved into UI.

### 2026-11-01 — A manual row borrows the catalog route; it never changes hands

A row added by hand stays independent: its URL, credential and provenance do not move when it
runs over the bridge catalog's route, so `cacheAuthority` and saved cursors keep their meaning.
The fallback lives in memory, rebuilt from each projection, including one served from a stale
cached catalog, because an unreachable bridge is the case that needs it. Only reachability
errors trigger it: a home server that answers with a refusal keeps its own route. A refusal at
the fallback is never shown as the row's failure, and that route is not offered the token again
in the same run. This is one fallback, not the multi-route model of UI_SPEC 4.2.1 (validation
P2), which would replace it.

### 2026-11-01 — Environments keeps its bar to one line

Three text actions wrapped the title until the bar filled a portrait screen, which looked like
an empty list to an off-LAN phone. Refresh is an icon, Administration and Appearance are in a
menu, the title ellipsizes, and the bridge status has its own line under the bar, because that
text is long and is what an offline user needs to read.

