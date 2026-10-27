# Strict bridge SDK CI candidate

`bridge-sdk.yml` is a complete workflow candidate, validated with actionlint1.7.7.
It is stored here for review and **does not execute as a GitHub workflow**. The
current GitHub credential exposes repo/read:org/gist but not workflow; the private
credential request timed out. Installing this file under `.github/workflows/`
requires an authorized credential with workflow scope. Do not count this candidate
as CI coverage. The existing Android workflow still treats the SDK as optional.

The candidate builds the exact public source from the SDK lock, three production
JNI targets and two lab variants, invokes host JNI under strict Gradle flags, then
runs the two-bridge/two-real-T3 Android laboratory on an explicit emulator5554.
It prebuilds fixture executables before emulator boot and retires only generated
cross-compilation caches to preserve disk space. Production JNI is restored and
release-input validation is run even after a failure. Reports exclude private
fixture directories, credentials and raw T3 logs.

T3 is pinned to v0.1.3-nightly.20261027.3754; the official Linux x64 archive SHA256
is checked before extraction/execution. Lab-private CA handling remains confined
to the lab flavor. This proves SDK/JNI/QUIC/isolated-authority behavior, not ordinary
app shell streams, productive host discovery, Home rendering or physical acceptance.
