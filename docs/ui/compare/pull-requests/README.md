# Pull request reader

Read-only increment against ot3 `7b5b1de` `T3PullRequests.qml:48-94,99-153`,
T3 Mobile source snapshot `680152e27` (`PullRequestsScreen.tsx`,
`PullRequestRouteScreen.tsx`, `PullRequestFiles.tsx`) and UI_SPEC §4.8.
Pinned library v0.3.5 remains unchanged/read-only.

Implemented: PR tab list, saved environment/project/state/involvement/search,
300 ms search debounce, explicit refresh/retry, cursor loading, native state badges,
row navigation with enterprise host, Overview Markdown, Checks, Conversation
comments and review threads. Home badge opens the native-selected current reference.
Overview shows native branch/stat fields and changed-file count. Filters and tab
survive recreation; navigation entries retain first-page lazy-list scroll state. Connection
generation changes cancel the previous read and clear its data/cursors. Selected
project metadata can disappear without broadening the request to all projects;
a deleted selected environment requires explicit replacement.

## Contracts and limits

- `pullRequests.request` supplies method/payload for list/detail/checks/activity.
  Typed LIST's documented `projectId` argument supplies scope absent in the recipe;
  actual JNI/WebSocket fixture verifies it reaches the wire with state, involvement,
  query and opaque cursor.
- `pullRequests.sidebarBadge` supplies state/icon presentation; no Kotlin draft/state
  precedence. Server field labels are translated without deriving status summaries.
- `markdown.blocks` + the existing MarkdownBlocks reader render Overview/comments.
  No HTML/WebView renderer or Kotlin Markdown parser.
- Pages append verbatim with positional UI keys. Ordering and repeated rows are
  deliberately preserved until shared Rust U31 supplies merge/order rules.
- No Code tab: v0.3.5 diff recipe returns an HTTP method sentinel, and the Android
  transport has no authenticated PR diff binding. File patches are explicitly
  unavailable. No review, comment, merge or other mutation controls.
- No provider browser/link sharing, row menus, full commit/review timeline, reviewer/
  label controls, relative-date formatting, or discussion cursor paging. Truncation
  is disclosed. No live NODE/VQ/GitHub read or physical-phone PR acceptance claimed.
- 21 new labels reuse the pinned ot3 catalog (207 total app labels × 20 locales after
  import). Android-only unavailable/partial/empty/patch/status sentences are en/es,
  English fallback elsewhere, like existing Android-specific strings. No invented
  catalog translations.

## Validation

Synthetic fixtures live in
`app/src/debug/assets/pullrequests`; no production server or provider mutation.
Goldens: `app/src/test/snapshots/PullRequestsScreenshotTest/`, Tokyo Night and Latte
(list, Overview, Checks, Conversation, offline). Representative list/Overview/Conversation goldens were visually inspected; all ten
passed image verification.

Leaving the list clears its inactive read state so a cached enabled Load more cannot
lose its action during re-subscription. The query debounce runs eagerly on edits,
with the saved query available immediately on first/resumed collection. Back reloads
the first page; pages loaded later are not retained, so their reading position is
not preserved until paging again. Full paginated restoration remains pending.

Final validation (2026-10-26):

- Full app JVM: 235 tests, no failures or skips. Core JVM: 295 tests, no failures,
  five existing skips (290 executed). Actual JNI typed WebSocket fixture verifies
  project/filter/cursor forwarding; reader fixtures verify native badge/Markdown.
- Five VM tests cover debounce/cancellation, reconnect/offline, pagination/retry,
  missing metadata/removed environment scope, reentry, and detail tab lifetime.
- Ten Tokyo Night/Latte goldens verified; lintDebug and debug APK/test APK assembled.
  Importer check: 207 strings × 20 locales against pinned AAR catalog.
- API36.1 emulator-5580, software renderer: final focused suite 3/3 passed in
  19.619 s. Both themes cover list→project→Overview→Checks→Conversation, recreation,
  both rotations, Back and pagination. Separate Home-badge coverage verifies the
  native-selected repository/host/number. Fake transport asserts read functions only
  and no orchestration dispatch. No Xiaomi operation by this increment.
- `device/` contains eight full-resolution PNGs, all visually inspected with actual
  loaded rows/content, and a 22 s video (720 px export). Evidence is synthetic UI +
  actual JNI, not a productive provider read. The opt-in recording path waits 800 ms
  before capture for SurfaceFlinger; ordinary CI does not sleep.

The first device attempt revealed a real Back→Load-more race: stateIn retained
old enabled controls while a resubscribed query debounce delayed the paging collector.
Read state now expires when inactive; saved query is immediately available and only
edits debounce. A focused reentry regression covers this. Early loading-frame PNGs
were replaced after requiring busy absent/row displayed and waiting for presentation.
Robolectric tests consume exported native views rather than loading JNI into multiple
classloaders; the normal-JVM contract test verifies those views against the pinned AAR.
