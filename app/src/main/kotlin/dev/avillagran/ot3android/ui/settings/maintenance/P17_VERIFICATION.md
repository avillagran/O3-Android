# P17 verification — 2026-10-31

Implementation scope: owned maintenance modules and locale files; no shared navigation, AppGraph,
Gradle, original Android checkout or phone changes. P17 is partial parity, as detailed in
P17_GAPS.md. Route registration is exported for P20.

Environment: Java Temurin 21 from `mise where java@temurin-21`,
ANDROID_HOME=$HOME/.android-sdk, Gradle --max-workers=2, required bridge SDK enabled.

Checks:

- `:core:testDebugUnitTest`: 441 total, 265 passed, 176 skipped, zero failures/errors.
- `:app:testDebugUnitTest`: 469 total, 453 passed, 16 skipped, zero failures/errors.
- 20 new maintenance safety/policy tests pass, including revoked/read-only permissions,
  registry absence, stale report rejection, post-close completion rejection, uncertain/cancelled
  mutation persistence, scope isolation, archive changed-row rejection and acknowledgement
  sequence boundaries. Storage writes use the existing native scoped-settings boundary.
- `:app:verifyRoborazziDebug`: 128 existing golden results, no golden edits.
- `lint`: zero errors. Existing warnings remain; exact final counts are in the external report.
- `tools/import-ot3-strings --check`: 261 generated strings × 20 locales pass.
- New hand-kept maintenance copy: 76 keys × 20 locales, equal keys and valid XML.
- `:app:assembleDebug -Pot3a.requireBridgeSdk=true`: passes.
- `git diff --check`: passes.

The registry/schema evidence was cross-checked against the pinned source commit
`d4bf6054b4480efdcd8a436cb66cb7f2e23664b1`, including the typed ThreadUnarchive/ThreadDelete
variants and native storage policy validation. This does not substitute for runtime JNI/host tests.

One intermediate verification failed writing XML results after two Gradle invocations overlapped.
Final checks were serialized. No test assertion was weakened or golden changed to address it.

Unverified: real-host JNI/transport/permission behavior, route integration, emulator/phone layouts,
RTL/accessibility, Desktop worker settings, vault synchronization/conflict presentation and
host-sync runtime. The unavailable worker/vault/sync routes are omitted. Existing skipped tests
are reported as skipped, never counted as passed. No phone cleanup or host cleanup was run.
