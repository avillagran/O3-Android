# Building the lib-t3-rust AAR

ot3-Android consumes [lib-t3-rust](https://github.com/avillagran/lib-t3-rust) as an
Android archive with the canonical Kotlin adapter (`dev.avillagran.t3.T3Client`) and
JNI libraries for arm64-v8a, armeabi-v7a, x86 and x86_64. The archive is produced
locally by `scripts/fetch-or-build-lib.sh` and placed under a stable name:

```
libs/lib-t3-rust-android.aar             the AAR, referenced as files("libs/lib-t3-rust-android.aar")
libs/lib-t3-rust-android.aar.sha256      sha256sum -c compatible checksum
libs/lib-t3-rust-android.properties      version, source commit, tag, describe, abiVersion, origin, sha256
libs/host/linux-x86_64/libt3_jni.so      desktop JNI library for JVM unit tests
libs/host/linux-x86_64/lib-t3-rust.properties
```

**Invariant: nothing under `libs/` is committed.** The AAR, its checksum, its
properties and the host library are build outputs and are listed in `.gitignore`.
Regenerate them; never add them to a commit.

## Current target: v0.3.1, ABI 3

The app targets the **lib-t3-rust v0.3.1** tag (commit `17d3cb7`), C ABI
`T3_PROTOCOL_ABI_VERSION 3`, unchanged since v0.3.0. ABI 3 is additive over ABI 2:

- typed API: ops `call` (`T3Client.call(fn, args, timeoutMs)`) and `describe`
  (`T3Client.describe()`, offline);
- streams: `wakeSubscriptions` (`T3Client.wakeSubscriptions()`,
  `T3Subscription.wake()`), and `pollSubscription` adds `boundaries`, `idle`,
  `completionMarker` and `errorMessage` (`T3Batch` fields; `T3Chunks` regroups items
  into server chunks);
- transport: `requestOk`, `fetchDescriptor`, `pairBearer`, `boundedThread`,
  `connectBearer`/`connectRelay` `options`; binary `assetBytes` and `projectFavicon`
  through the `image` native (`t3_client_image`);
- errors carry `message` and `requiredScope` (`T3Exception.reason`,
  `T3Exception.requiredScope`).

v0.3.1 completes the typed API on the same ABI: the registry covers 99 unary functions
in 20 modules, 13 typed streams and 4 composed functions (116 `describe` entries);
`subscribe` accepts a registry `fn` with `args` instead of a raw `tag`; `describe`
lists `streams` (item kinds, start kinds, cursor field); `pollSubscription` returns the
resume `cursor` and the `start` kind (`snapshot` or `resume`); and the Kotlin adapter
adds `T3Client.subscribeFn` and the `ThreadStream`/`ThreadUpdate` facade for the
thread and shell streams.

`capabilities` on v0.3.1 reports `abiVersion 3`, `libraryVersion 0.3.1`, `apiVersion
1`, `functions 116` and 18 `operations`: the 16 base operations plus `call` and
`describe` (`src/ffi.rs` at the tag). The lib's own references: `README.md`
("Omi / Kotlin", "Typed API"), `docs/ANDROID.md` and `include/t3_protocol.h`. What the
app still needs from the lib is tracked in [LIB_REQUESTS.md](LIB_REQUESTS.md).

**v0.3.2** (`26e67a7`, same ABI) was tagged and published as a GitHub release on
2026-10-25 UTC, after this target was checked. It carries the fixes for the
LIB_REQUESTS.md findings and is the next target; moving to it (and committing
`libs/lib-t3-rust.lock` for it) is a separate change.

## Default: build from source

```sh
scripts/fetch-or-build-lib.sh
```

Builds the current `HEAD` of a lib-t3-rust checkout, located in this order:
`$LIB_T3_RUST_DIR`, `$HOME/Work/lib-t3-rust`, `../lib-t3-rust` next to this repository.
A `LIB_T3_RUST_DIR` that is set but is not a lib-t3-rust checkout is an error; the
other locations are only tried when it is unset. In that checkout the script writes
only the untracked `target/` and `dist/`; no git mutations. Keep it at a release tag
(`git -C "$LIB_T3_RUST_DIR" checkout v0.3.1`) and run the script again after moving it.

Steps, all run inside the lib checkout with its own tools:

1. `tools/build-android arm64-v8a x86_64 armeabi-v7a x86` cross-compiles the Rust
   core and the C++ JNI adapter for every ABI with the NDK (API 24, 16 KiB page
   alignment). Outputs go to the lib's `target/` and `dist/android/<abi>/`, each
   with a `COMMIT` file.
2. `tools/package-android` refuses any ABI whose `COMMIT` is not `HEAD`, compiles
   `T3Client.kt` against `android.jar` with the Kotlin compiler (K2JVMCompiler) and
   assembles `dist/lib-t3-rust-android-v<version>.aar` plus its checksum.
3. The AAR is checked for `classes.jar`, the four `jni/<abi>/libt3_jni.so`,
   `AndroidManifest.xml`, `proguard.txt` and `META-INF/lib-t3-rust.properties`, its
   embedded `source=` commit is compared with the checkout's `HEAD`, and it is copied
   to `libs/lib-t3-rust-android.aar`.

`package-android` refuses a checkout with uncommitted changes, so the script stops
early in that case: every AAR is traceable to one lib commit.

`libs/lib-t3-rust-android.properties` records the provenance:

```
version=0.3.1
source=17d3cb7f62d537cf25a5b60f5443d9c50ad78249
tag=v0.3.1
describe=v0.3.1
abiVersion=3
origin=source
repository=https://github.com/avillagran/lib-t3-rust
sha256=...
installed=...
```

`tag` is the exact tag at `HEAD` (`none` between tags), `describe` is `git describe
--tags --always` (for example `v0.3.1-2-g1234abc` past the tag) and `abiVersion` is
`T3_PROTOCOL_ABI_VERSION` from `include/t3_protocol.h`. The script warns when the tag
and the Cargo version disagree.

A first build of the four ABIs takes 10 to 25 minutes; with a warm Cargo cache it
takes well under a minute. The script is idempotent: its key is the lib commit **and**
`git describe --tags --always`. If the properties already record both and the checksum
still matches, it does nothing; a tag added to an already built commit triggers a
reinstall so `tag`/`describe` stay true. If the lib's `dist/` already holds an intact
AAR packaged from that commit, it is reused without rebuilding. `--force` always
rebuilds. A lock on `libs/.fetch-or-build.lock` (`flock`) stops a second run in the
same repository while one is in progress.

## Options

| Option | Effect |
|---|---|
| (none) | Build the AAR from source, then the host JNI library on x86_64 Linux. |
| `--release [<tag>]` | Download the AAR from a GitHub release instead of building it; without a tag, the one in `libs/lib-t3-rust.lock`. |
| `--min-abi <n>` | With `--release`, refuse an AAR whose ABI is below `<n>` or unknown (default `T3_MIN_ABI`, else 3). |
| `--sha256 <hex>` | With `--release`, also require this SHA-256 for the AAR. |
| `--host-jni` | Build the host JNI library even when it is not built by default (e.g. with `--release`). |
| `--host-jni-only` | Build only the host JNI library; leave the AAR alone. |
| `--no-host-jni` | Skip the host JNI library. |
| `--lib-fixtures` | Also run the lib's host fixtures (`tools/test-jni`, `tools/test-kotlin-jni`); see below. |
| `--force` | Ignore what `libs/` (and the lib's `dist/`) already holds. |

`--host-jni-only` cannot be combined with `--release` (it builds no AAR), nor
`--no-host-jni` with `--host-jni`/`--host-jni-only`; `--sha256` needs `--release`.

Exit status: 0 on success, 1 on any failure (a missing tool, a refused AAR, a failed
build or a failed fixture), 2 on a usage error (unknown option, missing or malformed
argument, contradictory flags).

## Environment

Every variable has a default for the development machine; set it to override.

| Variable | Default | Used for |
|---|---|---|
| `LIB_T3_RUST_DIR` | `$HOME/Work/lib-t3-rust`, then `../lib-t3-rust` | lib checkout |
| `LIB_T3_RUST_REPO` | `avillagran/lib-t3-rust` | release downloads |
| `T3_MIN_ABI` | `3` | ABI floor for `--release` (`--min-abi` overrides) |
| `JAVA_HOME` | `$HOME/.local/jdk` | `java`, `jar`, JNI headers; only checked when building |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | `$HOME/.android-sdk` | SDK root |
| `ANDROID_NDK_HOME` | `$ANDROID_HOME/ndk/28.2.13676358` | cross-compilation |
| `ANDROID_PLATFORM` | `android-36` | selects `android.jar` |
| `ANDROID_JAR` | `$ANDROID_HOME/platforms/$ANDROID_PLATFORM/android.jar` | Kotlin adapter compile |
| `KOTLIN_VERSION` | `2.3.21` | compiler and stdlib looked up in the Gradle cache |
| `KOTLIN_STDLIB` | `kotlin-stdlib-$KOTLIN_VERSION.jar` from the Gradle cache | adapter compile |
| `KOTLIN_COMPILER_CLASSPATH` | assembled from the Gradle cache (below) | adapter compile |
| `JSON_JAR` | newest `org.json:json` jar in the Gradle cache or `~/.m2` | host fixtures |
| `LIB_FIXTURE_TIMEOUT` | `900` (seconds per fixture tool) | host fixtures |
| `GRADLE_USER_HOME` | `$HOME/.gradle` | cache location |
| `CARGO_BUILD_JOBS` | `nproc`, at most 8 | Rust and CMake parallelism |
| `CARGO_TARGET_DIR` | the lib's `target/` | Cargo output |

When `KOTLIN_COMPILER_CLASSPATH` is not set, the script assembles it from
`$GRADLE_USER_HOME/caches/modules-2/files-2.1` using the runtime dependencies that
`kotlin-compiler-embeddable` declares: `kotlin-compiler-embeddable`, `kotlin-stdlib`,
`kotlin-script-runtime`, `kotlin-daemon-embeddable` (all `$KOTLIN_VERSION`),
`kotlin-reflect` 1.6.10, `kotlinx-coroutines-core-jvm` 1.8.0 and
`org.jetbrains:annotations` 13.0 (`KOTLIN_REFLECT_VERSION`,
`KOTLINX_COROUTINES_VERSION` and `JETBRAINS_ANNOTATIONS_VERSION` override those),
plus `trove4j` when cached. If any is missing, it lists the missing coordinates and
stops; any Gradle build that uses that Kotlin version populates the cache. When a
cache directory holds the same jar under several hashes, the first path in sort order
is used. `ANDROID_JAR`, `KOTLIN_STDLIB`, every `KOTLIN_COMPILER_CLASSPATH` entry and
`JSON_JAR` are made absolute before the lib's tools run from its checkout. Nothing is
downloaded and no SDK, NDK or Rust target is installed by the script. With Kotlin
2.3.21 the v0.3.0 `T3Client.kt` compiled against `android.jar` without warnings.

## Release fallback

```sh
scripts/fetch-or-build-lib.sh --release v0.3.2
```

Downloads `lib-t3-rust-android-v<ver>.aar` and its `.sha256` from the GitHub release
(with `gh` when it is authenticated, otherwise `curl` restricted to HTTPS), verifies
the checksum and the AAR layout (`unzip -Z1`), and installs it under the same stable
name. The properties file records `origin=release <tag>`, the commit embedded in the
AAR, `tag`/`describe` as the release tag and `abiVersion` read from
`include/t3_protocol.h` at that tag. A release that does not exist (HTTP 404) is
reported as not published.

This path needs no JDK, Cargo, CMake or lib checkout: only `gh` or `curl`, `unzip` and
`sha256sum`. `JAVA_HOME` is not checked unless something is built.

**ABI floor.** The AAR is refused when its ABI is below `--min-abi` (default
`T3_MIN_ABI`, else 3) or cannot be read. v0.2.0, the latest release before v0.3.2, is
ABI 2 and lacks everything listed under "Current target"; it only installs with an
explicit `--min-abi 2`. The floor is also applied when an already installed release
AAR would be kept.

**Pinning.** `--sha256 <hex>` requires an exact AAR digest on top of the release's own
`.sha256`. A committed `libs/lib-t3-rust.lock` pins the release path for everyone:

```
tag=v0.3.2
sha256=<SHA-256 of lib-t3-rust-android-v0.3.2.aar>
```

When it exists, `--release` without a tag uses its `tag`, a different tag is refused,
and the downloaded AAR must match its `sha256` (and `--sha256`, if given, must agree
with it). Source builds ignore it, apart from a warning when the checkout is at
another tag; a locally packaged AAR is not byte-identical to the release one. No lock
is committed yet: v0.3.1, the current target, has no GitHub release. It is added
when the target moves to a published release (v0.3.2).

**Host library with a release.** `--release <tag> --host-jni` builds the host JNI
library from the lib checkout, so the checkout must be at the commit the AAR was
packaged from (its `source=`); otherwise the script stops before installing anything
and prints both commits. Releases do not publish a host library yet (LIB_REQUESTS.md,
finding 7).

## CI recipe

Release-only job (no lib checkout, no JDK, no Rust): needs `gh` (authenticated with
`GH_TOKEN`) or `curl`, plus `unzip`:

```yaml
- run: scripts/fetch-or-build-lib.sh --release --no-host-jni   # tag and sha256 from libs/lib-t3-rust.lock
```

Until the lock is committed, pass the tag (`--release v0.3.2`). JVM unit tests that
need the host JNI library cannot run on this path yet, since no release ships it.

Host path (AAR and host library from source, or a release AAR plus the host library):
a lib checkout at the tag, a JDK (for `jni.h`), Cargo and CMake; building the AAR as
well needs the Android SDK and NDK and the Kotlin jars in the Gradle cache, as above.

```yaml
- uses: actions/checkout@v4
- uses: actions/checkout@v4
  with:
    repository: avillagran/lib-t3-rust
    ref: v0.3.1
    path: lib-t3-rust
- run: scripts/fetch-or-build-lib.sh --host-jni-only
  env:
    LIB_T3_RUST_DIR: ${{ github.workspace }}/lib-t3-rust
```

The second checkout lands inside the workspace, so `LIB_T3_RUST_DIR` must be set; the
default `../lib-t3-rust` is not used. In a shell step that pipes the script's output
(`| tail`, `| tee`), use `set -o pipefail` so its exit status is not lost.

## Host JNI library for JVM tests

`T3Client` loads `libt3_jni` through `System.loadLibrary`. Android libraries do not
load on a desktop JVM, so on x86_64 Linux the script also builds a desktop
`libs/host/linux-x86_64/libt3_jni.so` the same way as the lib's `tools/test-jni`:
`cargo build --release --locked` for the host, then CMake of `bindings/android/cpp`
against the host JDK's `jni.h` (found through `JAVA_HOME`) into the lib's
`target/jni-host`, from where it is copied. It is skipped when its properties already
record the same commit and describe and its checksum matches.

JVM unit tests then run against the real Rust core without an emulator, given:

- `-Djava.library.path=libs/host/linux-x86_64`;
- an org.json implementation on the test classpath, because `android.jar` only has
  stubs (for example `org.json:json`, as Robolectric-free unit tests need anyway);
- the Kotlin adapter classes, from the AAR's `classes.jar`.

A manual smoke against the installed artifacts, in a scratch directory:

```sh
work=$(mktemp -d)
cat > "$work/Smoke.kt" <<'EOF'
import dev.avillagran.t3.T3Client
import org.json.JSONObject

fun main() = T3Client().use { client ->
    val caps = client.execute(JSONObject().put("op", "capabilities")) as JSONObject
    val api = client.describe()
    println("abi=${caps.getInt("abiVersion")} lib=${caps.getString("libraryVersion")} " +
        "ops=${caps.getJSONArray("operations").length()} functions=${caps.getInt("functions")} " +
        "describe=${api.getJSONArray("functions").length()} streams=${api.getJSONArray("streams").length()}")
}
EOF
unzip -o -q libs/lib-t3-rust-android.aar classes.jar -d "$work"
java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 -classpath "$work/classes.jar:$JSON_JAR:$KOTLIN_STDLIB" \
  -d "$work/out" "$work/Smoke.kt"
java -Djava.library.path=libs/host/linux-x86_64 \
  -cp "$work/out:$work/classes.jar:$JSON_JAR:$KOTLIN_STDLIB" SmokeKt
rm -rf "$work"
# v0.3.1: abi=3 lib=0.3.1 ops=18 functions=116 describe=116 streams=13
```

## The lib's host fixtures

lib-t3-rust ships host fixtures for its Android bindings under
`bindings/android/tests/dev/avillagran/t3/`: `T3Native.java` (Java, raw JNI),
`KotlinFixture.kt` (wrapper, capabilities, typed API, Unicode, stable errors,
concurrent close), `StreamFixture.kt` (chunk boundaries, completion marker, idle, wake
probe, error reasons against a fake WebSocket) and `T3Api.kt` (generated typed
wrappers). They run with the lib's own tools, which only write under its `target/`:

```sh
scripts/fetch-or-build-lib.sh --host-jni-only --lib-fixtures
```

This resolves the Kotlin classpath and an org.json jar as above, then runs, inside the
lib checkout, `tools/test-jni` (rebuilds `target/jni-host/libt3_jni.so`, runs
`T3Native`) and `tools/test-kotlin-jni` (compiles `T3Client.kt` with the fixtures and
`T3Api.kt` against org.json, runs `KotlinFixtureKt` and `StreamFixture`). Each tool
is reported separately; if either fails, the script prints `lib fixtures failed: ...`
and exits 1. By hand:

```sh
cd "$LIB_T3_RUST_DIR"
JAVA_HOME=... KOTLIN_STDLIB=... KOTLIN_COMPILER_CLASSPATH=... JSON_JAR=... tools/test-jni
JAVA_HOME=... KOTLIN_STDLIB=... KOTLIN_COMPILER_CLASSPATH=... JSON_JAR=... tools/test-kotlin-jni
```

Status at v0.3.0 (2026-10-24): `tools/test-jni` passes. `tools/test-kotlin-jni` does
not compile (`StreamFixture.kt:150`); with that one line corrected in a scratch copy,
`KotlinFixture` and `StreamFixture` pass with org.json 20180813 and 20250517. Details
and the proposed fix are in [LIB_REQUESTS.md](LIB_REQUESTS.md). The AAR is not
affected: it only contains `T3Client.kt`.

Status at v0.3.1 (2026-10-24): unchanged. `tools/test-jni` passes (`JNI_PASS`),
`tools/test-kotlin-jni` still fails to compile at `StreamFixture.kt:150`, so
`--lib-fixtures` exits 1 against a v0.3.1 checkout. That is expected until the target
moves to v0.3.2, where both tools pass (`KOTLIN_JNI_PASS`, `KOTLIN_STREAMS_PASS`).

## Checking an AAR

```sh
cd libs && sha256sum -c lib-t3-rust-android.aar.sha256
unzip -l lib-t3-rust-android.aar
unzip -p lib-t3-rust-android.aar META-INF/lib-t3-rust.properties
javap -cp classes.jar dev.avillagran.t3.T3Client   # after unzipping classes.jar
```

Each `jni/<abi>/libt3_jni.so` must be an ELF for its ABI with every `LOAD` segment
aligned to 0x4000 (16 KiB pages), as `readelf -lW` shows. On v0.3.1, `T3Client`
exposes `call`, `describe`, `subscribeFn` and `wakeSubscriptions`, and `classes.jar`
holds ten classes (`T3Client`, `T3ClientKt`, `T3Exception`, `T3Batch`, `T3Chunks`,
`T3Subscription`, `T3Native`, `ThreadStream`, `ThreadStream$Companion`,
`ThreadUpdate`); `T3Api` is not packaged (see LIB_REQUESTS.md, finding 2).
