# Building the lib-t3-rust AAR

ot3-Android consumes [lib-t3-rust](https://github.com/avillagran/lib-t3-rust) as an
Android archive with the canonical Kotlin adapter (`dev.avillagran.t3.T3Client`) and
JNI libraries for arm64-v8a, armeabi-v7a, x86 and x86_64. The archive is produced
locally by `scripts/fetch-or-build-lib.sh` and placed under a stable name:

```
libs/lib-t3-rust-android.aar             the AAR, referenced as files("libs/lib-t3-rust-android.aar")
libs/lib-t3-rust-android.aar.sha256      sha256sum -c compatible checksum
libs/lib-t3-rust-android.properties      version, lib commit, origin, sha256
libs/host/linux-x86_64/libt3_jni.so      desktop JNI library for JVM unit tests
libs/host/linux-x86_64/lib-t3-rust.properties
```

**Invariant: nothing under `libs/` is committed.** The AAR, its checksum, its
properties and the host library are build outputs and are listed in `.gitignore`.
Regenerate them; never add them to a commit.

## Default: build from source

```sh
scripts/fetch-or-build-lib.sh
```

Builds the current `HEAD` of a lib-t3-rust checkout, located in this order:
`$LIB_T3_RUST_DIR`, `$HOME/Work/lib-t3-rust`, `../lib-t3-rust` next to this repository.
The script only reads that checkout; to move to a newer lib, update it there
(`git -C "$LIB_T3_RUST_DIR" pull --ff-only`) and run the script again.

Steps, all run inside the lib checkout with its own tools:

1. `tools/build-android arm64-v8a x86_64 armeabi-v7a x86` cross-compiles the Rust
   core and the C++ JNI adapter for every ABI with the NDK (API 24, 16 KiB page
   alignment). Outputs go to the lib's `target/` and `dist/android/`.
2. `tools/package-android` compiles `T3Client.kt` against `android.jar` and assembles
   `dist/lib-t3-rust-android-v<version>.aar` plus its checksum.
3. The AAR is checked for `classes.jar`, the four `jni/<abi>/libt3_jni.so`,
   `AndroidManifest.xml`, `proguard.txt` and `META-INF/lib-t3-rust.properties`, its
   embedded `source=` commit is compared with the checkout's `HEAD`, and it is copied
   to `libs/lib-t3-rust-android.aar`.

`package-android` refuses a checkout with uncommitted changes, so the script stops
early in that case: every AAR is traceable to one lib commit.

A first build of the four ABIs takes 10 to 25 minutes; with a warm Cargo cache it
takes one or two. The script is idempotent: if
`libs/lib-t3-rust-android.properties` already records the checkout's commit and the
checksum still matches, it does nothing. If the lib's `dist/` already holds an intact
AAR packaged from that commit, it is reused without rebuilding. `--force` always
rebuilds.

## Options

| Option | Effect |
|---|---|
| (none) | Build the AAR from source, then the host JNI library on x86_64 Linux. |
| `--release <tag>` | Download the AAR from a GitHub release instead of building it. |
| `--host-jni` | Build the host JNI library even when it is not built by default (e.g. with `--release`). |
| `--host-jni-only` | Build only the host JNI library; leave the AAR alone. |
| `--no-host-jni` | Skip the host JNI library. |
| `--force` | Ignore what `libs/` (and the lib's `dist/`) already holds. |

## Environment

Every variable has a default for the development machine; set it to override.

| Variable | Default | Used for |
|---|---|---|
| `LIB_T3_RUST_DIR` | `$HOME/Work/lib-t3-rust`, then `../lib-t3-rust` | lib checkout |
| `LIB_T3_RUST_REPO` | `avillagran/lib-t3-rust` | release downloads |
| `JAVA_HOME` | `$HOME/.local/jdk` | `java`, `jar`, JNI headers |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | `$HOME/.android-sdk` | SDK root |
| `ANDROID_NDK_HOME` | `$ANDROID_HOME/ndk/28.2.13676358` | cross-compilation |
| `ANDROID_PLATFORM` | `android-36` | selects `android.jar` |
| `ANDROID_JAR` | `$ANDROID_HOME/platforms/$ANDROID_PLATFORM/android.jar` | Kotlin adapter compile |
| `KOTLIN_VERSION` | `2.3.21` | compiler and stdlib looked up in the Gradle cache |
| `KOTLIN_STDLIB` | `kotlin-stdlib-$KOTLIN_VERSION.jar` from the Gradle cache | adapter compile |
| `KOTLIN_COMPILER_CLASSPATH` | assembled from the Gradle cache (below) | adapter compile |
| `GRADLE_USER_HOME` | `$HOME/.gradle` | cache location |
| `CARGO_BUILD_JOBS` | `8` | Rust and CMake parallelism |
| `CARGO_TARGET_DIR` | the lib's `target/` | Cargo output |

When `KOTLIN_COMPILER_CLASSPATH` is not set, the script assembles it from
`$GRADLE_USER_HOME/caches/modules-2/files-2.1` using the runtime dependencies that
`kotlin-compiler-embeddable` declares: `kotlin-compiler-embeddable`, `kotlin-stdlib`,
`kotlin-script-runtime`, `kotlin-daemon-embeddable` (all `$KOTLIN_VERSION`),
`kotlin-reflect` 1.6.10, `kotlinx-coroutines-core-jvm` 1.8.0 and
`org.jetbrains:annotations` 13.0 (`KOTLIN_REFLECT_VERSION`,
`KOTLINX_COROUTINES_VERSION` and `JETBRAINS_ANNOTATIONS_VERSION` override those),
plus `trove4j` when cached. If any is missing, it lists the missing coordinates and
stops; any Gradle build that uses that Kotlin version populates the cache. Nothing is
downloaded and no SDK, NDK or Rust target is installed by the script.

## Release fallback

```sh
scripts/fetch-or-build-lib.sh --release v0.2.0
```

Downloads `lib-t3-rust-android-v<ver>.aar` and its `.sha256` from the GitHub release
(with `gh` when it is authenticated, otherwise `curl`), verifies the checksum and the
AAR layout, and installs it under the same stable name. The properties file records
`origin=release <tag>` and the commit embedded in the AAR.

Only a fallback for machines without the Android toolchain: **v0.2.0 is older than the
lib's source `HEAD` (0.3.x)** and lacks the 0.3 additions (typed API `call`/`describe`
and ABI v3, transport options, pairing, bounded snapshots, signed assets). Use a
release only when its version matches what the app needs.

## Host JNI library for JVM tests

`T3Client` loads `libt3_jni` through `System.loadLibrary`. Android libraries do not
load on a desktop JVM, so on x86_64 Linux the script also builds a desktop
`libs/host/linux-x86_64/libt3_jni.so` the same way as the lib's `tools/test-jni`:
`cargo build --release --locked` for the host, then CMake of `bindings/android/cpp`
against the host JDK's `jni.h` (found through `JAVA_HOME`). It is skipped when it was
already built from the same commit.

JVM unit tests then run against the real Rust core without an emulator, given:

- `-Djava.library.path=libs/host/linux-x86_64`;
- an org.json implementation on the test classpath, because `android.jar` only has
  stubs (for example `org.json:json`, as Robolectric-free unit tests need anyway);
- the Kotlin adapter classes, from the AAR's `classes.jar`.

A manual check, compiling the adapter with the same Kotlin compiler classpath:

```kotlin
// Capabilities.kt
fun main() = dev.avillagran.t3.T3Client().use { client ->
    println(client.execute(org.json.JSONObject().put("op", "capabilities")))
}
```

```sh
java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 -classpath "$JSON_JAR:$KOTLIN_STDLIB" -d out \
  "$LIB_T3_RUST_DIR/bindings/android/kotlin/dev/avillagran/t3/T3Client.kt" Capabilities.kt
java -Djava.library.path=libs/host/linux-x86_64 -cp "out:$JSON_JAR:$KOTLIN_STDLIB" CapabilitiesKt
```

It prints the library's `capabilities`: `libraryVersion`, `abiVersion` and the list of
`operations` the native core supports.

## Checking an AAR

```sh
cd libs && sha256sum -c lib-t3-rust-android.aar.sha256
unzip -l lib-t3-rust-android.aar
unzip -p lib-t3-rust-android.aar META-INF/lib-t3-rust.properties
```

Each `jni/<abi>/libt3_jni.so` must be an ELF for its ABI with every `LOAD` segment
aligned to 0x4000 (16 KiB pages), as `readelf -lW` shows.
