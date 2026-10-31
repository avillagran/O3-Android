# Dependency adoption 0.3.6

Original adoption branch: `feat/adopt-lib-036`, based on origin/main; no merge or device deployment.
The consumer continuation below uses `feat/parity-consumers` based on that branch.
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
grant. Administration mutations need explicit confirmation. No owner-only grant, consent, domain,
identity or all-hosts approval control is offered remotely. New invitation content
is ephemeral and not saved/logged. Existing outbox identities remain local.

Repository tests cover exact replay after a lost receipt and process recreation,
retention expiry with an absent document, incomplete pagination and queue scope
rejection. Native contract regressions cover empty permission advertisement,
stale history owner, overlapping PR pages, split ANSI/CR output, composing-state
transform rejection and unsafe reference rejection. These are not two-device
acceptance tests. The original adoption used English fallback; the consumer continuation translates the two new resource families into all 20 locales.

Partial integration and missing UI work are itemized in desktop-parity-status.md;
this change does not claim complete Desktop parity. No phone, all-hosts checkout,
Desktop checkout or deployed services were changed. The shared lib checkout was
read-only; host JNI was built from an isolated cache clone at the release source.

## Original adoption verification

Validation at PR100 (Java 21, Android SDK ~/.android-sdk, max-workers=2):
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



## Consumer continuation — feat/parity-consumers

Based on `feat/adopt-lib-036`; the other checkout was read only to copy ignored
`libs/` and `core/src/main/jniLibs/`. No phone access or merge. See the current
consumer table in desktop-parity-status.md; historical package audit rows do not
supersede it.

Delivered automatic shared thread-draft workers/startup replay, exact CAS send
clear, explicit conflict resolution, source-host attachment metadata verification,
62 labels × 20 locales, administration forms/ephemeral invitation QR/share, native
quota pooling, restricted registry SVG icons, native reference/text-document
routing, durable pin batches/project metadata writes, provider forms/readback,
native completion/recall, diff pagination and live-output status indicators.

This remains a partial consumer integration: no real phone/Desktop round trip was
performed (phone access was excluded); new-chat shared identity, live cross-host upload acceptance, anchor/binary-document reader
support and the acceptance cases in the status table remain open. A two-journal
fixture is not a live two-device result. No unsupported control claims success.

Final local JVM/golden/lint/required-bridge assembly passed with Java 21 and
--max-workers=2: core 484 PASS/5 SKIP per debug/release variant (489 total each),
app 513 PASS/1 SKIP (514 total), 0 failures. All 144 goldens pass; four usage
images were updated for the corrected explanatory notice and visually inspected.
Lint has 0 errors, core 2 warnings, app 107 warnings/1 hint. Import check remains
261 × 20; 62 hand-kept parity keys × 20 = 1,240 complete localized values across
three resource families, plus the corrected usage notice in all locales. Notices
check: 123 packaged entries and 15 keys × 20 PASS.

New regressions cover independent-device journal round trips, offline conflicts,
conditional send clear, duplicate acknowledgements, editor/save races, JSON key
order, preserved newer local branches, cursor-only edits, exact batch suffix replay,
private original-stash preservation across transfer conflicts, and unsafe SVG rejection. The image test now awaits bitmap visibility after decoding;
the quota preference test awaits DataStore publication before recreation. Existing
assertions remain; no unknown write is silently replayed.

A previous complete emulator run passed 106/9 skipped/0 failures in 226.470 seconds
instrumentation, 241.598 seconds for Gradle. A later quota fixture assertion observed
the switch before its asynchronous write was published; final complete verification
is repeated after the state-based wait above. Interrupted environment restarts are
not counted as successful validation.

Final consumer acceptance on the final sources: core 484 PASS/5 SKIP for each
variant; app 513 PASS/1 SKIP; 144 goldens PASS; lint 0 errors (core 2 warnings,
app 107 warnings/1 hint); import/notices checks PASS. Full unfiltered API34 emulator:
106 PASS, 9 SKIP, 0 FAIL, 242.174 seconds instrumentation. The combined Gradle
invocation (JVM, goldens, lint, required-bridge APK and full instrumentation) took
378.432 seconds, under 40 minutes. No physical phone was accessed.

Consumer APK: `/home/node/.cache/o3-android-artifacts/parity-consumers/O3-Android-parity-consumers.apk`.
SHA-256: `fdc591b300eddd6c0d1996a508495cebcd617bdf4685bd19c833fc8f457b72a6`.
Both production arm64-v8a/x86_64 bridge JNI libraries are present. Logs and parsed
counts: `/home/node/.cache/o3-android-artifacts/parity-consumers/acceptance.log`
and `checks.json`. Remaining acceptance is explicit in desktop-parity-status.md;
fixture passes do not establish authenticated live-host or phone/Desktop parity.
