# Strings

Companion to [UI_SPEC.md](../UI_SPEC.md) section 3.6.

## Rule

Every user-visible string is ot3's English source string, translated with ot3's catalog.
A string ot3 does not have is allowed only for a state that exists on mobile and not on
the desktop (UI_SPEC rule 4); each one is listed below, written in ot3's voice.

## Source catalog

- `ot3:translations/i18n.json`: 20 locales (en, es, pt, fr, de, it, nl, pl, ru, uk, ja,
  ko, zh, ar, tr, sv, da, no, fi, cs) × 1 796 keys, English source strings as keys,
  `{name}` placeholders (`ot3:translations/COVERAGE.md:3-25`).
- Non-English values are machine-authored drafts (`ot3:translations/COVERAGE.md:27`);
  Android inherits that status and says so in About.
- The lib has the lookup (`lib:src/i18n.rs:4`, `translate(catalog, locale, source)`, with
  locale normalisation at `:3`) but no catalog; request U16 in UI_SPEC section 6.

## Generator

`tools/import-ot3-strings`:

1. Reads `app/src/main/res/ot3-strings.tsv`: `android_name<TAB>ot3 English key`, one row
   per string the app uses.
2. Looks each key up in `ot3:translations/i18n.json` for every locale; a missing key fails
   the build step.
3. Rewrites `{name}` placeholders as positional `%1$s`, `%2$s`… in the order they appear
   in the English string, and escapes `'`, `"`, `@`, `?` for Android resources.
4. Writes `values/strings_ot3.xml` and `values-<locale>/strings_ot3.xml`, with ot3's `no`
   written as `values-nb`, and `values-ar` marked for RTL review.
5. Leaves `values/strings.xml` (the app's own strings, today 79) and
   `values/strings_mobile.xml` (the list below) untouched.

Plurals: ot3 has none (counts are written as "Working ({count})",
`ot3:ui/qml/T3Panel.qml:1242`); Android keeps those strings as they are instead of
inventing `<plurals>`.

## Appearance

Settings → Appearance (UI_SPEC 3.1.3), in `values/strings_appearance.xml` until the
catalog generator is on `main`. Rows with an ot3 key move to `ot3-strings.tsv` then; the
others are mobile-only.

| Android name | English | ot3 key, or why ot3 lacks it | Source |
|---|---|---|---|
| `appearance_title` | Appearance | ot3: `Appearance` | ot3 catalog |
| `appearance_theme` | Theme | ot3: `Theme` | ot3 catalog |
| `appearance_mode_light` | Light | ot3: `Light` | ot3 catalog |
| `appearance_follow_environment` | Follow the environment's Omarchy theme | ot3 runs on the Omarchy host and always follows it | UI_SPEC 3.1.3 |
| `appearance_live_theme` | {theme} · {environment} | same | UI_SPEC 3.1.3 ("Tokyo Night · omarchy") |
| `appearance_live_theme_cached` | {theme} · {environment} (saved) | same; shown until the environment is read | UI_SPEC 3.1.3 (cache) |
| `appearance_not_omarchy` | {environment} is not running Omarchy | same | UI_SPEC 3.1.3 |
| `appearance_no_scope` | {environment} does not allow reading files ({scope}) | ot3 reads its own files | UI_SPEC 3.1.3 (missing scope) |
| `appearance_read_error` | Could not read the theme of {environment} ({code}) | same | UI_SPEC 3.1.3 (error) |
| `appearance_waiting` | Connecting to {environment}… | ot3 has its local backend | UI_SPEC 3.1.3 |
| `appearance_no_environment` | No environments connected | same | `t3m:src/features/home/HomeScreen.tsx:160` |
| `appearance_main_environment` | Main environment | ot3 has one environment on screen | UI_SPEC 3.1.3 |
| `appearance_main_environment_last_used` | Last used | same | UI_SPEC 3.1.3 |
| `appearance_mode_follow_system` | Follow system | ot3 follows Omarchy, not a system mode | UI_SPEC 3.1.3 |
| `appearance_mode_dark` | Dark | ot3's catalog has "Dark appearance" but no "Dark" | UI_SPEC 3.1.3 |
| `appearance_dark_theme` | Dark theme | ot3 has one live theme | UI_SPEC 3.1.3 |
| `appearance_light_theme` | Light theme | same | UI_SPEC 3.1.3 |
| `appearance_material_you` | Material You | Android only | `t3m:src/lib/mobileTheme.ts:19` |
| `appearance_manual_applies` | Used when the environment's theme is off or not available. | ot3 has no manual fallback | UI_SPEC 3.1.3 |

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
