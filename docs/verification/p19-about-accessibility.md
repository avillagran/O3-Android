# P19: Android About, notices and accessibility

Implementation scope: Android-side remainder of P19 in the 2026-10-31 Desktop parity
audit. Desktop reference was read only. No device, phone or all-hosts checkout access.

## Delivered behavior

- Settings lists About and Licenses, including search. Both have real typed destinations.
- About identifies O3 Android, reads the installed package version, links project source,
  explains translation draft status and opens licenses. Toolbar and Android Back return
  from nested licenses to About; a direct Licenses destination returns to Settings.
- Licenses loads 123 packaged entries offline, filters by every whitespace-separated
  search term across name/version/license, reports shown/total, expands one notice at a
  time, displays selectable legal text, links source, and handles load failures with retry.
- 15 hand-kept keys in each of 20 `strings_about.xml` files; generated strings untouched.
  Notices, coordinates, versions and identifiers retain upstream text.

## Accessibility pass

| Integrated surface | Evidence / outcome |
| --- | --- |
| About and Licenses | 320dp Arabic screenshots, normal and 200% font scale, both light/dark themes. Wrapping text, scrollable content, labeled search/back/link actions; 48dp back and action targets. Notice rows expose button role and localized expanded/collapsed state. Count updates have a polite live region. |
| Mixed Arabic / brand | Existing SkyBrandRow keeps the wordmark LTR while the page remains RTL. Arabic product names are isolated; version/source substitutions use BidiFormatter. License identifiers use LTR text direction. Existing O3 header snapshots cover both directions at 100/160/200%. |
| Settings root | About and Licenses discoverable and clickable; translated search remains available. Regression navigates both entries and typed destinations. |
| Home shelves | Shelf names remain merged with their counts; headings, button roles and expand/collapse accessibility actions added. Existing labeled search/filter/action controls retained. |
| Integrated thread rows | Interactive PR badge minimum width/height 48dp, text can grow; project actions and unpin now have 48dp targets; subagent control uses minimum height/width rather than fixed caption height. Project action speaks full project name even in sober dot mode. Existing thread title/project/status description retained. |
| PR list | Shared PR badge fix applies to real list; updated dark/light goldens reviewed. |
| Appearance, notifications, environments and usage | Source review of existing labeled Material controls, shared Ot3IconButton (48dp), Ot3Switch row semantics, percentage progress semantics and scroll containers. No new settings policy or thread wiring introduced. |

Automated semantics assertions verify the mixed wordmark, About action minimum bounds,
PR badge label/click/bounds, local notice loading, multi-term search and navigation.
Screenshots are layout evidence, not proof of spoken output on a physical device.
Interactive TalkBack, Switch Access and physical-device acceptance remain manual work;
no such pass is claimed. ThreadScreen and AppGraph are untouched (PR96 ownership).

## License collection boundary

See `tools/about-notices/README.md` for exact release graph provenance and reproducible
collection. Native SDK project licenses come from pinned upstream artifacts/source;
their internal transitive native dependency inventory is not exposed by Maven and is
not certified by this work. No Desktop-only dependency catalog is passed off as APK
content.

## Final validation (2026-10-31, NODE)

Java: `mise where java@temurin-21` → Temurin 21.0.13+9.
Android SDK: `$HOME/.android-sdk`. Gradle maximum workers: 2.

- `tools/import-ot3-strings --check`: 261 generated keys × 20 locales, unchanged.
- Generator unit tests: 10 passed.
- `python3 tools/about-notices/check.py`: 15 keys × 20 locales, positional argument
  parity, no placeholders, 123 nonempty notices, unique notice paths and HTTPS sources.
- Release dependency notice regeneration is idempotent; no unresolved Maven licenses.
- Core debug and release JVM tests: each 287 passed, 176 skipped, 0 failed.
- App debug JVM tests: 492 passed, 16 skipped, 0 failed (7 additional tests).
- `:app:verifyRoborazziDebug`: 140 goldens passed (8 added, 10 updated for target sizes).
  New Arabic About/notice images and changed list/catalog images visually inspected.
- `lint`: 0 errors; app 97 warnings, core 2 warnings. No new warning introduced.
- `:app:assembleDebug -Pot3a.requireBridgeSdk=true`: passed.
- `git diff --check`: passed; no ThreadScreen, AppGraph or generated string changes.

The 176 core skips and some app skips reflect missing host JNI/opt-in fixtures;
this is not acceptance of live native-host flows. App release unit tests were not
part of this debug-APK verification. No instrumentation or device session was run.
