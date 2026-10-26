# Strings

Companion to [UI_SPEC.md](../UI_SPEC.md) section 3.6.

## Rule

Every user-visible string is ot3's English source string, translated with ot3's catalog.
A string ot3 does not have is allowed only for a state that exists on mobile and not on
the desktop (UI_SPEC rule 4); each one is listed below, written in ot3's voice.

## Source catalog

- ot3's catalog (`ot3:translations/i18n.json`): 20 locales (en, es, pt, fr, de, it, nl, pl,
  ru, uk, ja, ko, zh, ar, tr, sv, da, no, fi, cs) × 1 796 keys, English source strings as
  keys, `{name}` placeholders (`ot3:translations/COVERAGE.md:3-25`).
- The app reads it from lib-t3-rust, not from an ot3 checkout: since v0.3.4 the AAR ships
  the lib's copy (`data/i18n.json`) as the asset `assets/lib-t3-rust/i18n.json`, so the
  catalog is the one in `libs/lib-t3-rust-android.aar`, pinned by `libs/lib-t3-rust.lock`
  (LIB_BUILD.md). v0.3.4's asset is byte-identical to `ot3:translations/i18n.json`
  (sha256 `d72e7658…`).
- Non-English values are machine-authored drafts (`ot3:translations/COVERAGE.md:27`);
  Android inherits that status and says so in About.
- The lib also exposes the catalog at run time: `i18n.t` (one source string in one
  locale), `i18n.catalog` (a locale's whole table), `i18n.resolve` and `i18n.languages`
  (U16, closed in v0.3.4). The app's own strings use Android resources generated from the
  asset instead (DECISIONS.md, "UI-1f").

## Generator

`tools/import-ot3-strings` (Python, standard library, run as `python3 -I`):

1. Reads `app/src/main/res/ot3-strings.tsv`: `android_name<TAB>ot3 English key`, one row
   per string the app uses.
2. Opens `libs/lib-t3-rust-android.aar` (put there by `scripts/fetch-or-build-lib.sh`) as
   a zip and reads `assets/lib-t3-rust/i18n.json` and the lib version from
   `META-INF/lib-t3-rust.properties`; it fails if the AAR is missing, has no catalog, or
   is not the version `libs/lib-t3-rust.lock` pins.
3. Looks each key up in the catalog for every locale; a missing key, a missing locale or a
   translation whose `{placeholders}` differ from the English ones fails the run.
4. Rewrites `{name}` placeholders as positional `%1$s`, `%2$s`… in the order they appear
   in the English string, and escapes `'`, `"`, `@`, `?` for Android resources (a literal
   `%` in a formatted string is `%%`).
5. Writes `values/strings_ot3.xml` and `values-<locale>/strings_ot3.xml`, with ot3's `no`
   written as `values-nb`, and `values-ar` marked for RTL review. Each file's header names
   the lib version, the asset and the catalog's sha256, so a rerun on the same AAR is
   byte-identical.
6. Leaves `values/strings.xml` (the app's own strings, today 75) and
   `values/strings_mobile.xml` (the list below) untouched, and fails if a name in the TSV
   also exists in either.

The outputs are committed; the Gradle build never runs the script and needs no Python.
`--check` writes nothing and exits non-zero if any output would change; CI runs it (and
`tools/tests/test_import_ot3_strings.py`) after fetching the AAR, so a lib bump that
changes strings fails until they are regenerated and committed.

Plurals: ot3 has none (counts are written as "Working ({count})",
`ot3:ui/qml/T3Panel.qml:1242`); Android keeps those strings as they are instead of
inventing `<plurals>`.

## Mobile-only strings

Each row names the mobile state and the T3 Mobile source that has it; wording follows
ot3's sentence case and tone.

| Android name | English | Why ot3 lacks it | T3 Mobile source |
|---|---|---|---|
| `mobile_offline_cached` | Offline · showing saved messages | ot3 is never offline with a cache | `t3m:src/features/threads/threadContentPresentation.ts:18-46` |
| `mobile_messages_not_cached` | Messages not saved on this device | same | same |
| `mobile_opening_thread` | Opening thread… | ot3 opens instantly from a live bridge | `t3m:src/features/threads/ThreadRouteScreen.tsx:139-148` |
| `mobile_reconnecting_env` | Reconnecting to {environment}… | ot3 shows a sidebar health dot only | `t3m:src/features/home/workspace-connection-status.ts:19-35` |
| `mobile_decline_redirect_hint` | Send a message to decline and redirect the agent | ot3 steers without a hint | `t3m:src/features/threads/ThreadDetailScreen.tsx:1601-1603` |
| `mobile_camera_needed` | Camera access needed to scan the code | no QR scanning on the desktop | `t3m:src/features/connection/ConnectionsNewRouteScreen.tsx:118-125` |
| `mobile_scan_pairing` | Scan the code from t3 pair or desktop Connections settings | same | `t3m:src/features/connection/ConnectionsNewRouteScreen.tsx:353-359` |
| `mobile_not_pairing_code` | Not a T3 pairing code | no QR scanning on the desktop; shown while the camera sees a QR code that is not a pairing link (no code, or neither a `/pair` link nor a hosted link with `host`), and scanning goes on | none: ot3-Android's scanner checks the text before taking it (`ui/pair/QrScanScreen.kt`) |
| `mobile_select_thread` | Select a thread | the desktop always shows one | `t3m:src/features/layout/WorkspaceEmptyDetail.tsx:46` |
| `mobile_show_more_settled` | Show more ({count} settled hidden) | ot3 scrolls a separate shelf | `t3m:src/features/threads/thread-list-v2-items.tsx:283-303` |
| `mobile_notifications_permission` | Allow notifications to hear when a thread needs you | desktop notifications need no permission | `t3m:src/features/settings/SettingsNotificationsRouteScreen.tsx:405-455` |
| `mobile_tab_chats` | Chats | ot3 has a sidebar, not tabs (PRs and Settings use ot3's "Pull requests" and "Settings") | `t3m:modules/t3-native-controls/android/src/main/java/expo/modules/t3nativecontrols/T3WorkspaceNavigationModule.kt:358` |
| `mobile_syncing_threads` | Syncing threads… | ot3 shows a sidebar health dot only | `t3m:src/features/home/workspace-connection-status.ts:22,32` |
| `mobile_loading_threads` | Loading threads… | same | `t3m:src/features/home/workspace-connection-status.ts:22,32` |
| `mobile_reconnecting_envs` | Reconnecting {count} environments… | same | `t3m:src/features/home/workspace-connection-status.ts:28` |
| `mobile_you_are_offline` | You are offline | the desktop has no device network state | `t3m:src/features/home/workspace-connection-status.ts:20` |
| `mobile_thread_list_options` | Thread list options | ot3's filter is a popup without a title | `t3m:src/features/home/home-list-filter-menu.ts:88` |
| `mobile_filter_environment` | Environment | ot3 filters by status and project only | `t3m:src/features/home/home-list-filter-menu.ts:44` |
| `mobile_no_environments` | No environments connected | ot3 always has its local backend | `t3m:src/features/home/HomeScreen.tsx:160` |
| `mobile_connecting_environment` | Connecting to environment | same | `t3m:src/features/home/HomeScreen.tsx:191` |
| `mobile_env_connecting` | Connecting… | ot3's connection rows show Connected, Offline or Disabled | `t3m:src/features/connection/connectionTone.ts:20` |
| `mobile_env_connection_failed` | Connection failed | same | `t3m:src/features/connection/connectionTone.ts:32` |
| `mobile_env_retrying_attempt` | Retrying · attempt {n} | same | `t3m:src/features/connection/ConnectionEnvironmentRow.tsx:134` |
| `mobile_component_catalog` | UI components | the debug catalog is T3 Mobile's | `t3m:src/Stack.tsx:703` |
| `mobile_snooze_until` | Snooze until | ot3's snooze presets are a popup menu without a title | `t3m:src/features/threads/threadListV2.ts:95-118` |
