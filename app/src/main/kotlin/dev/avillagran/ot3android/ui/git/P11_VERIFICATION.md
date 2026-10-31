# P11 verification — 2026-10-31

Implemented within assigned paths from main 58dbad0. Integration and unbound
items are listed in P11_INTEGRATION.md; this is not a reachable end-to-end parity
claim before P20 wiring.

## Automated results

Java 21 from `mise where java@temurin-21`, ANDROID_HOME=$HOME/.android-sdk,
`--no-daemon --max-workers=2`:

- `:core:testDebugUnitTest`: 410 total, 234 passed, 176 skipped, 0 failures/errors.
- `:app:testDebugUnitTest`: 414 total, 398 passed, 16 skipped, 0 failures/errors.
- New P11 tests: 18 core + 6 app = 24 passed. File journal persistence/recreation,
  same-ID consumption, per-checkout exclusion, host scope, explicit reconciliation,
  late observations, atomic recovery claims, stale native revision/reference,
  dirty-file acknowledgement, auth denial, cancellation, uncertain receipt,
  reconnect read-only behavior, partially delivered scripts and draft recovery.
  UI tests cover dirty/default-branch confirmations, unavailable stream adapter,
  offline/unknown guards across restoration, checkpoint confirmation, refresh-only
  uncertainty, context/tree/hunk controls. Reader tests verify native summary and
  rename forwarding, moved checkout rejection and cursor/kind preservation.
- `:app:verifyRoborazziDebug`: 94 existing goldens unchanged, 0 added/changed.
  These validate existing surfaces, not screenshots of the new P20 slots.
- `:core:lintDebug`: 0 errors, 2 existing warnings.
- `:app:lintDebug`: 0 errors, 79 warnings, 1 hint; no findings in P11 files.
- `tools/import-ot3-strings --check`: PASS, pinned catalog 261 × 20 locales.
  Hand-kept strings_git.xml: 56 matching keys × 20 locales; XML parsed and key
  sets checked separately. Existing Desktop translations reused with three
  explicit new translations (read failure, previous hunk, next hunk).
- `:app:assembleDebug -Pot3a.requireBridgeSdk=true`: PASS.
- `git diff --check`: PASS. No baseline snapshot updates.

## Limits

Host libt3_jni is absent from this checkout; native/opt-in suites are skipped,
not successful JNI execution. New orchestration tests use recorded fake domain
and transport boundaries; Rust policy is not reimplemented in production Kotlin.
Pinned AAR methods were verified by its classes and binary registry strings and
compilation, with exact source contracts checked at d4bf6054.

No emulator, phone, live host, real destructive rollback/Git/PR operation,
physical text input, RTL, accessibility or process-kill acceptance was exercised.
Journal reopen/unknown/cancellation and draft at-most-once behavior have JVM
coverage, not an Android filesystem power-loss guarantee. Native host isolation,
remote-client status races, handle generation and composer recovery require P20
and live acceptance. PR CI/review is separate from these local results.
