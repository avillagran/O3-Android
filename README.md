# ot3-Android

**T3 Code on Android, natively.** Kotlin + Jetpack Compose on top of
[`lib-t3-rust`](https://github.com/avillagran/lib-t3-rust), the same Rust core that
drives [Omarchy T3 Code](https://github.com/avillagran/omarchy-t3-code) on the desktop.

Follow local and remote coding sessions, write prompts, attach files, approve actions
and answer questions from your phone or tablet, against your own T3 Code servers:
pairing link, URL and token, OmaBridge, Tailscale, and later T3 Connect.

Status: **in development, not distributed yet.** See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
The lib-t3-rust v0.3.0 AAR is built locally with `scripts/fetch-or-build-lib.sh`; see
[docs/LIB_BUILD.md](docs/LIB_BUILD.md) and, for what the app needs from the lib,
[docs/LIB_REQUESTS.md](docs/LIB_REQUESTS.md).

Independent community project; not an official T3 Code app. MIT License.

## Building

Requirements: JDK 17 or newer (21 recommended) and an Android SDK with platform 36.
Point Gradle at the SDK with `sdk.dir=...` in `local.properties` (not versioned) or
`ANDROID_HOME`.

```sh
./gradlew :core:testDebugUnitTest :app:assembleDebug   # unit tests and debug APK
./gradlew :core:lintDebug :app:lintDebug               # lint
./gradlew connectedDebugAndroidTest                    # Room, credential and Compose UI tests on a device
```

Build and structure decisions are recorded in [docs/DECISIONS.md](docs/DECISIONS.md).
