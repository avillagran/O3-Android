# Dependency adoption 0.3.8

Branch `chore/adopt-lib-038`, based on origin/main a56791c; no merge or device deployment.
Author/committer: Andrés Villagrán <andres@villagranquiroz.cl>.

Pin: lib-t3-rust v0.3.8, ABI 3, source 1498ad6656fed511d1c7ea61795dfd46c0e01ce5.
Release AAR SHA-256: 6cf56cf366d16dd59ef313864fb7cfb4e04fb067487e1d1e0671b30fe81ae213.
The GitHub asset digest, the release `.sha256` and the local `sha256sum` agree, and the
AAR's embedded `source=` is the pinned commit. OmaBridge stays at 1097f0e7.

## What changed

The lib now negotiates gzip and brotli on JSON, signed asset, document and hub
screenshot fetches. Every limit counts decoded bytes, and reading stops at the first
byte past it. Auth and relay documents, token exchanges and the MJPEG stream still
send `Accept-Encoding: identity`.

The API is unchanged: 125 functions, 14 streams and 127 domain functions, so only the
version moves in `NativeSmokeTest`. The i18n asset has the same SHA-256 (`6760b8bd…`),
so `strings_ot3.xml` changes only in its generated header.

No Kotlin mapping changed. `core/` and `app/` never matched `unsupported_encoding` or
set `Accept-Encoding`. A compressed body that does not decode arrives as `unreachable`,
already in `ConnectionSupervisor.TRANSIENT` and the connection text, or as
`asset_unavailable`, which reader reads already show as a retryable failure. Any
`Content-Encoding` other than gzip or br stays `unsupported_encoding`.

The About notice for lib-t3-rust moves from 0.3.6, stale since 0.3.7, to 0.3.8; the
license text is identical to the AAR's. As `tools/about-notices/README.md` states, the
native transitive inventory is not exported or certified, and that still applies to
the new crates: `adler2`, `alloc-no-stdlib`, `alloc-stdlib`, `async-compression`, `brotli`, `brotli-decompressor`, `compression-codecs`, `compression-core`, `crc32fast`, `flate2`, `miniz_oxide`, `simd-adler32`, `tokio-util`.

## Sizes

`libt3_jni.so`, uncompressed; the same four files and sizes are in the debug APK under
`lib/<abi>/`, which reports 4 for the CI check.

| ABI | v0.3.7 | v0.3.8 | Growth |
|---|---:|---:|---:|
| arm64-v8a | 14,416,464 | 15,822,240 | +1,405,776 |
| armeabi-v7a | 10,925,904 | 12,107,936 | +1,182,032 |
| x86 | 16,207,396 | 17,752,132 | +1,544,736 |
| x86_64 | 15,253,744 | 16,732,768 | +1,479,024 |

The AAR grows from 23,489,932 to 26,116,303 bytes (+2,626,371). The two checked ABIs,
arm64-v8a and x86_64, keep 16 KiB `LOAD` alignment. Debug APK: 115,081,446 bytes.

## Verification

Java 21, Android SDK `~/.android-sdk`, `--max-workers=2`, `OT3A_REQUIRE_HOST_JNI=1`,
`-Pot3a.requireBridgeSdk=true`, build cache off. Host JNI came from an isolated clone
at the tag, so the shared lib checkout stayed read-only. The final run of the gate:

- `:core:testDebugUnitTest`: 536 PASS, 5 SKIP, 0 FAIL (541 total).
- `:app:testDebugUnitTest`: 584 PASS, 1 SKIP, 0 FAIL (585 total).
- `:app:verifyRoborazziDebug`: PASS; no golden changed.
- Lint: core 0 errors, 2 warnings; app 0 errors, 113 warnings, 1 hint.
- `:app:assembleDebug` with the required bridge SDK: PASS.
- `tools/import-ot3-strings --check`: 261 × 20 PASS. `tools/about-notices/check.py`:
  127 notices PASS. `tools/tests`: 10 PASS.

That gate was not green on the first attempts, and these failures are recorded:

- The first run failed `NativeBridgeLinkageTest` because the ignored host bridge library
  was missing from the new worktree. It was copied from a checkout at the same OmaBridge
  pin; no code changed.
- `VisualRenderingComposeTest.rasterFrameUsesBoundedReaderAndReleasesAfterScopeExpires`
  failed in 2 of the 4 full runs, on the wait for `ReaderBitmapBudget` to
  drain. The test renders from an in-memory fake and has no HTTP path. This is the
  stranded-reservation flake fixed by open PR #113, which is not in this base. With that
  PR's change applied temporarily, 4 of 4 full app suites passed; it is not part of this
  branch. Until #113 lands, this test can still fail on this branch. Those 2 failed runs
  skipped goldens because the test task failed first.

Not verified: no emulator or instrumented run (Gradle `connected*` tasks were excluded
because a personal phone is attached) and no fetch from a live host, so compression was
not observed end to end in the app. The lib's own `tests/http_compression.rs` covers
the negotiation.
