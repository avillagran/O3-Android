# Inline expander and local thread cache

Scope: `fix/outbox-collapse-composer`, PR105. Integrated `origin/main` at
`47c951d` before implementation. No PR merge, phone access or Desktop edits.

## Row

Reference: Desktop `ui/qml/T3ThreadRow.qml` (focus row, subthread toggle,
project accent and tree guides) and the supplied screenshot.

Parents with children use a two-line row in card and slim rendering: project
accent, elided title, existing PR badge, bot/count/chevron chip and time; status
dot, project and monospace host badge below. The visual chip is 20dp high with
12dp icons and 4dp corners, inside a separate 48dp minimum touch area. Press
uses 8% foreground fill, idle 4%, keyboard focus an accent border. Chevron-right
becomes chevron-down while expanded; collapsed RTL mirrors its direction.
Existing localized Show/Hide labels and expansion semantics describe the action.

The persisted, host-qualified `thread:<row key>` preference remains the source
of expansion state. The chip now owns the action inside the parent, including
classic, focus, projects and bottom shelves. Children retain 16dp tree indentation.
Four new goldens cover both themes at 411dp and 320dp. Four existing goldens
changed for the shared row/host accent presentation.

## Cache

Extended the existing `ThreadCache` instead of creating a second message store.
Room v5 remains the owner of drafts, receipts and shared draft commands; no
schema migration or Room deletion is involved in clearing cached content.

- App-private `noBackupFilesDir/thread-cache-v1`; hashed environment/thread keys
  with endpoint/credential authority validation before publishing restored data.
- Default 200 MiB total projection budget, 128 entries, 8 MiB per entry and seven
  days payload age. LRU eviction prefers currently pinned threads but always
  obeys the total budget. Settled threads remain eligible; archived/deleted
  threads and removed environments invalidate cached content and late writes.
- At most 500 messages, turn items and runs per projection. Oversized arrays
  retain their newest window. Such snapshots are display-only until refreshed:
  they cannot resume an incomplete sequence or paginate across omitted content.
  An entry still larger than the byte cap is discarded.
- The sidebar shares the same budget and holds at most 2,000 thread records.
  A restored shell is display-only; a fresh GET establishes the live cursor.
  Connection publication also triggers persistence so an idle host is cached.
- Settings > Storage has phone-local controls even while hosts are offline:
  used size, 50/200/500 MiB limits, and Clear cache. Three hand-maintained strings
  are provided across all 20 locale resource sets.
- Cleanup runs at startup, on cache access/write and every six hours while the
  app process is alive. A stopped process cannot grow this cache; the next
  startup cleans it again.
- Clear invalidates existing disk-write leases. An open view may keep its
  in-memory content; reopening establishes a new disk lease. Drafts, outbox and
  unsent composer text are untouched.
- Existing stale/offline indicators remain. A saved reading anchor missing
  from the cached page is not overwritten during refresh and is restored when
  the refreshed view includes it; explicit user scrolling overrides restoration.

## Verification

Java 21 from `mise where java@temurin-21`, SDK `$HOME/.android-sdk`, serial Gradle
invocations with `--max-workers=2`. The bridge was rebuilt at the merged main
pin `1097f0e7cd4e91e58dce94744f13606a26756601` for host/x86_64/arm64-v8a.

Final verification results are recorded in the implementation handoff and local
logs `/tmp/o3-cache-final3.log` and `/tmp/o3-cache-strings.log`.
Tests cover LRU/cap, pinned priority, bounded pages, authority separation,
environment removal, late writes, offline cache opening, real Room draft/outbox
preservation, all three row layouts and reading-anchor restoration.

`connectedDebugAndroidTest` was not run: T3 `device_list` returns "Agent device
access is turned off for this environment." No emulator was started, and no
phone was accessed. Device/OEM behavior remains unverified.
