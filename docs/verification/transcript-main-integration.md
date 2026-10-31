# PR88 main integration — 2026-10-31

Merged origin/main `09454b04ab6073c682a93187712ee66868b5a7f7` into
feat/transcript-parity, starting at `047979aa36f20885a37d7bd711eb70047af84e84`.
No PR merge, phone access or changes to the all-hosts checkout.

Conflict resolution retains the attachment composer, rendering preferences and Git review
from main, together with the compact bounded composer, focus release, stable reading
viewport/IME handling, find and position slider from PR88. Cache tests retain keyboard
settling and atomic coordinate reads with the original tolerance/deadlines.

ThreadScreen passes quote, child navigation and scoped secret actions to the reader.
Quotes append to existing drafts without sending and cannot overwrite queued edits.
Child navigation retains the selected environment. Secret answers are direct, non-replayed,
permission checked and rejected if their connection changes during authorization. Private
input clears on controller changes. Four new JVM cases and one device case cover these
integration boundaries.

Plan implementation/new-thread actions remain unavailable: the pinned native contract lacks
the complete interaction-mode + dispatch recipe with durable retry identities. No partial
plan implementation is presented as successful. See reader/P08_INTEGRATION.md and
LIB_REQUESTS.md. History paging, terminal-response eligibility and U35 routing remain open.

## Validation

Java: Temurin 21 from `mise where java@temurin-21`; ANDROID_HOME=$HOME/.android-sdk.
Gradle: --no-daemon --max-workers=2. Device runs explicitly select emulator-5554, API34;
each full connected suite is bounded by `timeout 2400`.

- Full app connectedDebugAndroidTest final: 99 passed, 9 skipped, no failures/errors
  (108 XML cases), Gradle wall time 3m46s; previous full pass 4m41s.
- Core unit tests: 287 passed, 176 skipped, no failures/errors.
- App unit tests: 485 passed, 16 skipped, no failures/errors.
- verifyRoborazziDebug: 132 goldens passed. Only the two conflicted markdown reader
  goldens were regenerated (markdownReader filter); both themes visually inspected for
  navigation, text, code highlighting and layout. Other existing goldens retained.
- Lint: no errors/fatal issues; app 97 warnings, core 2 warnings.
- tools/import-ot3-strings --check: 261 native strings × 20 locales passed.
- git diff --check passed.

Logs: ~/.cache/o3-android-artifacts/pr88-merge/ on NODE. Native/live-host and physical
security/IME acceptance are outside these local emulator and JVM checks.
