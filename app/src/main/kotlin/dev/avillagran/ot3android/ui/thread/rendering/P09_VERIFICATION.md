# P09 validation — 2026-10-31

Temurin 21.0.13, `ANDROID_HOME=$HOME/.android-sdk`, all Gradle runs used
`--max-workers=2`. Final combined run passed in 1m34s:

- `:core:testDebugUnitTest`: 392 cases, 216 passed / 176 skipped.
- `:app:testDebugUnitTest`: 425 cases, 409 passed / 16 skipped.
- Zero failures/errors. 17 new cases (12 unit, 4 Compose, 1 dual-theme screenshot).
- `:app:verifyRoborazziDebug`: 96 goldens, including two new snapshots.
- `lint`: zero errors; app 78 warnings / 1 hint, core 2 warnings.
- `tools/import-ot3-strings --check`: passed, native catalog 261 keys ×20 locales.
  XML parsing also confirmed seven new hand-kept rendering keys in all 20 locales.
- `:app:assembleDebug -Pot3a.requireBridgeSdk=true`: passed.

New checks cover Desktop formula vocabulary, superscript/subscript spans, malformed
and deep source preservation, unknown commands, exact highlighted code preservation,
escaped strings/comments, source and frame byte bounds, HTML/SVG rejection, offline,
expired and unsupported state, stale completion after host change, cancellation,
copy/toggle/streaming controls, both native table clipboard formats and raster budget
release on expiration. Existing image/media/budget/zoom/copy regressions ran in the
full suite unchanged. Raster fixtures are not a Mermaid engine acceptance test.

The existing skipped cases require missing host JNI or opt-in/live configuration.
No phone or emulator was used. Live host grants, codecs, RTL, screen reader and device
look acceptance remain unverified. No network renderer or remote script was used.
The actual Mermaid engine, interactive QML/HTML runtime and P20 wiring remain pending;
see `P20_INTEGRATION.md`. Goldens validate the new exported surfaces, not reachability
from ThreadScreen. Shared reader/navigation/AppGraph/Gradle files were not edited.

Local build logs: `/home/node/.cache/o3-android-artifacts/p09/`.
