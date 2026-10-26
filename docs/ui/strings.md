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
| `mobile_route_public` | Public address | ot3 lists a host's "Public addresses" (`ot3:ui/qml/T3ConnectionAccess.qml:149`) but never labels the route a client uses; the phone shows one route at a time (UI_SPEC 4.2.1) | none: T3 Mobile has one address per environment |
| `mobile_route_last` | An environment needs at least one route. | ot3 removes a route only through T3's catalog | none |
| `mobile_add_as_route` | Add as a route | ot3's gateway check ends with "Ready: in the T3 app, add this address…" (`ot3:ui/js/OmaBridgeText.js:82`); the phone is that app | none |
| `mobile_animations_system_off` | Off while Android's Remove animations is on | ot3 has no OS animation setting (UI_SPEC 3.4.4) | `t3m:src/lib/designTokens.ts:8` (each animation follows the system setting) |
| `mobile_limits_empty` | No provider on the selected environments reports subscription limits. | ot3 hides an empty strip | `t3m:src/features/usage/UsageLimitsPooled.tsx:323-327` |
| `mobile_usage_connect` | Connect an environment to see usage. | ot3's page always has its local backend | `t3m:src/features/usage/UsageRouteScreen.tsx:305-314` |

## ot3 strings missing from ot3's catalog

ot3 shows these, but builds them outside `T3I18n.tr`, so `ot3:translations/i18n.json`
(and the lib's `i18n.json`) do not have them; ot3 itself shows them in English. Android
lists them in `values/strings_mobile.xml` with ot3's exact English until the lib returns
them from U26 / U27 as catalog keys (LIB_REQUESTS.md, "2026-10-25: usage limits and
routes").

| Android name | English | ot3 source |
|---|---|---|
| `ot3x_route_lan` | Local network | `ot3:src/connection_network.rs:51` |
| `ot3x_route_tailscale` | Tailscale | `ot3:src/connection_network.rs:51` |
| `ot3x_quota_access_denied` | Access denied | `ot3:ui/js/UsageLimits.js:35` |
| `ot3x_quota_outdated` | Disconnected; readings may be outdated | `ot3:ui/js/UsageLimits.js:35` |
| `ot3x_quota_refresh_failed` | Could not refresh limits | `ot3:ui/js/UsageLimits.js:35` |
| `ot3x_resets_in` | resets in {time} | `ot3:ui/js/NativeUsageLimits.js:566` |
| `ot3x_resets_now` | resets now | `ot3:ui/js/NativeUsageLimits.js:564` |
| `ot3x_resets_momentarily` | Resets momentarily | `ot3:ui/js/NativeUsageLimits.js:565` |

"Connected through OmaBridge · {gateway}" is not a new string: it is the catalog's
"Connected through OmaBridge" joined with " · " and the name, as ot3 does
(`ot3:ui/qml/T3ConnectionOmaBridge.qml:39`). Every other text of the motion, usage and
OmaBridge sections (Animations page, usage page and strip, OmaBridge page) is in the
catalog.
