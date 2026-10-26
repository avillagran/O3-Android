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
survive recreation; loaded server pages, cursor and the visible row/pixel offset survive
opening a detail and Back within this navigation entry. Connection
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

One in-memory cache belongs to the list's navigation-entry ViewModel and retains
only its current request scope and exact connection owner (transport identity + epoch).
On return, retained rows stay private until current scope/environment/connection are
validated. No old payload is published during that check; the UI avoids composing an
empty LazyColumn which would clamp the retained LazyListState. Same-owner Back and
activity recreation reuse all loaded pages and the cursor without another list read.

Connection loss/replacement, environment/project/filter/query changes and explicit
Refresh invalidate pages/cursor and reset position. A missing or removed environment
never falls back to another environment, including the initially defaulted selection.
While hidden, the list owns no connection/project-flow subscription or transport lease;
connection changes are checked on return before showing anything retained. A small
eager flow debounces local search edits only; it opens no network stream. Editing the
query cancels the old read immediately and clears its rows while the new request waits
300 ms. Scope metadata changes alone do not re-read valid cached pages.

More actions carry a session token through a conflated channel, covering publication
before receiver registration. No-subscriber/canceled-session actions are ignored and
cannot page a different scope or connection. Page append remains verbatim: this change
does not add domain sort/dedup/precedence. Payloads and LazyListState never enter
SavedStateHandle or a process bundle. Process death refetches from page one and resets
the reading position; only small selected scope/filters/query remain restorable.

Initial reader validation (2026-10-26):

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
The return-state follow-up keeps observable state private while inactive but retains
one validated-owner cache. A buffered session action and focused reentry regression
cover this race; saved query is immediately available and only edits debounce. Early loading-frame PNGs
were replaced after requiring busy absent/row displayed and waiting for presentation.
Robolectric tests consume exported native views rather than loading JNI into multiple
classloaders; the normal-JVM contract test verifies those views against the pinned AAR.

## Return-state follow-up (2026-10-26)

Matches ot3 `T3PullRequests.qml:41-48,74-95`: opening a selected PR hides the narrow
list without replacing its entries or ListView content position. Android keeps that
presentation state at navigation-entry lifetime, while validating connection ownership.

- Full app JVM: 243/243 passed. Eight focused VM tests cover page-two reuse, no
  subscribers/transport collection while hidden, publication-before-receiver More,
  private validation delay, changed epoch, replaced transport at the same epoch,
  offline→reconnect while hidden, offscreen refresh/filter/query invalidation and no
  deleted-environment fallback. Native page order/cursors remain unchanged.
- Ten original PR Tokyo/Latte goldens verify unchanged; lintDebug, debug APK and
  instrumented APK assemble passed. No shared-library or protocol changes.
- Dedicated actual navigation test passed API36.1: two pages loaded, first visible
  item index14 (PR200) and 35px offset, open detail then Back, activity recreation,
  landscape and portrait (actual viewport dimensions checked). Every return keeps
  index14/35px and exactly two list reads. Continuing uses the retained page-three
  opaque cursor and makes the third read, without re-reading pages one/two.
- The initial synthetic setup asked for a row too near the list end to align at the
  top; Android correctly clamped that initial scroll. The final fixture anchors page
  two's first row with enough following rows; this changed test setup, not UI code.

Final API36.1 combined device suite: **4/4 passed** in 14.091 s (the original three
reader tests plus return-state test). Opt-in evidence run: 1/1 passed in 8.397 s.
`return-state/` contains three inspected loaded screenshots (before detail, detail,
after Back) and a 10 s video. Before/after show the same page-two row and offset.
The 600 ms pre-capture pause is opt-in only; normal CI does not sleep. This is a
synthetic three-page provider with actual JNI/navigation/Compose, not a live provider
or physical-phone acceptance. Emulator stopped after evidence; Xiaomi untouched.
