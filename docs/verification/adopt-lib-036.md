# Dependency adoption 0.3.6

Branch: `feat/adopt-lib-036`, based on origin/main; no merge or device deployment.
Author/committer: Andrés Villagrán <andres@villagranquiroz.cl>.

Pins:
- lib-t3-rust v0.3.6, source 86819eb442ae4a8635cb21206f2195343c75b094,
  release AAR SHA-256 5e44c1ef846f27b7f131ca3bbacb771f21ce70028f061014904064e4caf9e21d.
- OmaBridge cd0de3a83d0c12ed324c8b580e5f67c8f3e0aff9, production host/x86_64/arm64,
  Android NDK 28.2.13676358, unchanged mobile ABI 1.
- ABI 3: 124 typed functions, 13 streams, 124 domain functions, 33 domain modules,
  24 JSON operations. Native smoke assertions updated after reading the source.
- Catalog now has 2,027 sources; import remains the Android subset, 261 × 20.

Safety: selected draft authority is independent of the catalog bridge. The
receiving authority checks drafts grants; management checks a separate admin
grant. Writes need explicit confirmation. No owner-only grant, consent, domain,
identity or all-hosts approval control is offered remotely. New invitation content
is ephemeral and not saved/logged. Existing outbox identities remain local.

Repository tests cover exact replay after a lost receipt and process recreation,
retention expiry with an absent document, incomplete pagination and queue scope
rejection. Native contract regressions cover empty permission advertisement,
stale history owner, overlapping PR pages, split ANSI/CR output, composing-state
transform rejection and unsafe reference rejection. These are not two-device
acceptance tests. New UI strings currently use the English resource fallback.

Partial integration and missing UI work are itemized in desktop-parity-status.md;
this change does not claim complete Desktop parity. No phone, all-hosts checkout,
Desktop checkout or deployed services were changed. The shared lib checkout was
read-only; host JNI was built from an isolated cache clone at the release source.

Validation so far (Java 21, Android SDK ~/.android-sdk, max-workers=2):
- Core debug JVM: 472 PASS, 5 SKIP, 0 FAIL (477 total).
- App debug JVM: 511 PASS, 1 SKIP, 0 FAIL (512 total).
- Combined verifyRoborazziDebug, lint and required-bridge assembleDebug: PASS.
- Lint: core 0 errors/2 warnings; app 0 errors/102 warnings. The new administration
  and shared-draft strings deliberately use English fallback with scoped
  MissingTranslation annotations; translation work is not claimed complete.
- Strings import check: 261 × 20 PASS. Notices: 123 entries and 15 × 20 keys PASS;
  native license texts match the two pinned sources. SDK versions in notices updated.
- Earlier loopback image connector timeouts were not reproduced in the final core
  suite; the test now retains server-side failures as suppressed diagnostics.
  Assertions and deadlines are unchanged; no root cause is claimed fixed.
- Final standalone verifyRoborazziDebug: PASS, 144 committed goldens (6 PR goldens
  updated and inspected). Required-bridge APK and lint refreshed after the remote
  invitation capability guard. App bitmap visibility had one intermediate failure;
  the unchanged focused case and subsequent complete JVM/golden run passed. No
  cause is claimed fixed and no assertion was relaxed.
- First full emulator pass found four fixture failures: unregistered native PR list
  and permission view functions. Both now forward to the real JNI; full repeat
  passed 106/9 skipped/0 failures in 228.813 seconds. Final guard acceptance: 106 PASS, 9 SKIP, 0 FAIL; 229.202 seconds instrumentation, 236 seconds entire Gradle invocation (under 40 minutes).

APK: `/home/node/.cache/o3-android-artifacts/adopt-036/O3-Android-adopt-lib-036.apk`.
SHA-256: `2661aa6e8d62676aa8e55ca6b7133ad8141b6b5b2c318c2337294dc5460c6647`.
Archive contains the updated SDK notices and bridge JNI for arm64-v8a/x86_64.


