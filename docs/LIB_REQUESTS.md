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
`T3Native.image` call". `T3Native` is `internal`, so only code inside the lib's own
Kotlin module can make that call; ot3-Android, a separate module consuming the AAR,
cannot. `T3Client.image(id, name, mimeType)` only covers the `assets.createUrl` image
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

Proposed fix: map JNI-side failures with a stable code to `T3Exception(code)` in the
Kotlin wrapper (or document `IllegalStateException` as part of the `upload` contract).

### 6. No v0.3.0 GitHub release

The latest release is v0.2.0 (ABI 2). `scripts/fetch-or-build-lib.sh --release` cannot
fetch ABI 3 until the v0.3.0 release publishes `lib-t3-rust-android-v0.3.0.aar` and
its `.sha256` (ideally after finding 1 is fixed, so the release's fixtures compile).
