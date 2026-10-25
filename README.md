# ot3-Android

**T3 Code on Android, natively.** Kotlin + Jetpack Compose on top of
[`lib-t3-rust`](https://github.com/avillagran/lib-t3-rust), the same Rust core that
drives [Omarchy T3 Code](https://github.com/avillagran/omarchy-t3-code) on the desktop.

Follow local and remote coding sessions, write prompts, attach files, approve actions
and answer questions from your phone or tablet, against your own T3 Code servers:
pairing link, URL and token, OmaBridge, Tailscale, and later T3 Connect.

Status: **in development, not distributed yet.** See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
The app targets the lib-t3-rust v0.3.4 AAR from its GitHub release, pinned (tag and
SHA-256) by `libs/lib-t3-rust.lock`. `scripts/fetch-or-build-lib.sh --release --host-jni`
downloads and verifies it and builds the host JNI library for JVM tests from a
lib-t3-rust checkout at the same tag (the release ships none); source builds remain for
unreleased lib work. See [docs/LIB_BUILD.md](docs/LIB_BUILD.md) and, for what the app
needs from the lib, [docs/LIB_REQUESTS.md](docs/LIB_REQUESTS.md); what it expects from a
T3 server is in [docs/PROTOCOL_NOTES.md](docs/PROTOCOL_NOTES.md). Screens, navigation and
the visual system are specified in [docs/UI_SPEC.md](docs/UI_SPEC.md).

Independent community project; not an official T3 Code app. MIT License.

## Building

Requirements: JDK 17 or newer (21 recommended) and an Android SDK with platform 36.
Point Gradle at the SDK with `sdk.dir=...` in `local.properties` (not versioned) or
`ANDROID_HOME`.

The lib-t3-rust AAR (and, on x86_64 Linux, the host JNI library for JVM tests) must be in
`libs/` before Gradle configures:

```sh
scripts/fetch-or-build-lib.sh --release --host-jni      # AAR from the pinned release
./gradlew :core:testDebugUnitTest :app:assembleDebug   # unit tests and debug APK
./gradlew :core:lintDebug :app:lintDebug               # lint
./gradlew connectedDebugAndroidTest                    # Room, credential and Compose UI tests on a device
```

An optional, read-only check against a real server runs with the unit tests when
`OT3A_LIVE_T3_URL` and `OT3A_LIVE_T3_TOKEN_FILE` (a file holding a bearer token) are set;
it writes only keys and counts to `core/build/live-connection-report.txt`:

```sh
OT3A_LIVE_T3_URL=http://127.0.0.1:8643 OT3A_LIVE_T3_TOKEN_FILE=~/.t3-token \
  ./gradlew :core:testDebugUnitTest --tests '*LiveConnectionTest*' --rerun
```

Build and structure decisions are recorded in [docs/DECISIONS.md](docs/DECISIONS.md).
