# ot3 design tokens for Compose

Companion to [UI_SPEC.md](../UI_SPEC.md) section 3. Every value is ot3's, with its
source; the Android column is the value to implement. Source pins and the citation
prefixes (`ot3:`, `omarchy:`, `t3m:`, `lib:`) are defined in UI_SPEC section 0.

Conversion rule (UI_SPEC 3.2 and 3.3): **text sizes × 7/6, rounded to the nearest sp**
(ot3's body 12 px becomes Android's 14 sp body and every ratio is kept); **lengths,
radii and spacing 1 px = 1 dp** (ot3 px are logical pixels at base 12,
`omarchy:shell/Commons/Style.qml:211-223`); **every touch target at least 48 dp**.

## 1. Colour roles

ot3 draws almost everything as the foreground (`Color.popups.text`) or the accent at
a given opacity over the background. The lib's `theme.tokens` returns each role as the
opaque colour ot3 draws (Qt Quick's 8-bit premultiplied blend), the importer (see
[omarchy-themes.md](omarchy-themes.md)) stores its result as it is, so Compose never
blends ot3's roles at runtime and a role can feed a Material `ColorScheme`.

| Role | ot3 definition | Source | tokyo-night | catppuccin-latte |
|---|---|---|---|---|
| `background` | `colors.toml` `background` (`popups.background`) | `omarchy:default/themed/shell.toml.tpl:127-131` | `#1a1b26` | `#eff1f5` |
| `foreground` | `colors.toml` `foreground` (`popups.text`) | same | `#a9b1d6` | `#4c4f69` |
| `accent` | `colors.toml` `accent` | `omarchy:shell/Commons/Color.qml:148-154` | `#7aa2f7` | `#1e66f5` |
| `muted` | `colors.toml` `muted` | `omarchy:shell/Commons/Color.qml:148-154` | `#414868` | `#acb0be` |
| `dim` (secondary text) | fg @ 0.57 | `ot3:ui/qml/T3Panel.qml:381` | `#6b718a` | `#9295a6` |
| `statusMuted` (a row status with nothing to say) | fg @ 0.6 | `ot3:ui/qml/T3Palette.qml:33` | `#6f758f` | `#8e8fa1` |
| `divider` | fg @ 0.09 | `ot3:ui/qml/T3Panel.qml:382` | `#272936` | `#e0e2e8` |
| `surface` (bubble, code block) | fg @ 0.045 | `ot3:ui/qml/T3Panel.qml:387` | `#21222e` | `#e8ebef` |
| `card` | fg @ 0.035 | `ot3:ui/qml/T3Approvals.qml:18` | `#1f202c` | `#eaebf0` |
| `cardBorder` | fg @ 0.12 | `ot3:ui/qml/T3Approvals.qml:18` | `#2b2d3b` | `#dbdde4` |
| `changesCard` | fg @ 0.025 | `ot3:ui/qml/T3Changes.qml:14` | `#1d1e2a` | `#ebedf2` |
| `rowSelected` | fg @ 0.075 | `ot3:ui/qml/T3ThreadRow.qml:179` | `#252633` | `#e3e5eb` |
| `rowPressed` (ot3 hover) | fg @ 0.045 | `ot3:ui/qml/T3ThreadRow.qml:179` | `#21222e` | `#e8ebef` |
| `rowDraft` | accent @ 0.06 | `ot3:ui/qml/T3ThreadRow.qml:179` | `#1f2333` | `#e3e9f6` |
| `field` | fg @ 0.04 | `ot3:ui/qml/T3TextField.qml:20-22` | `#20212e` | `#e9ebef` |
| `fieldBorder` | fg @ 0.14 (accent on focus) | `ot3:ui/qml/T3TextField.qml:20-22` | `#2e303f` | `#d8dae1` |
| `switchOff` | fg @ 0.15 (on = accent) | `ot3:ui/qml/T3Switch.qml:14` | `#2f3240` | `#d6d9e0` |
| `inlineCode` | fg @ 0.10 | `ot3:ui/qml/T3Panel.qml:725-726` | `#282a37` | `#dfe0e7` |
| `composerBorder` | fg @ 0.09; focused accent @ 0.45 | `ot3:ui/qml/T3Composer.qml:265` | `#272936` / `#455884` | `#e0e2e8` / `#91b2f5` |
| `working` | `blue`, else accent | `ot3:ui/qml/T3Palette.qml:10` | `#7aa2f7` | `#1e66f5` |
| `done` | `green`, else accent | `ot3:ui/qml/T3Palette.qml:11` | `#9ece6a` | `#40a02b` |
| `waiting` | `yellow`, else accent | `ot3:ui/qml/T3Palette.qml:12` | `#e0af68` | `#df8e1d` |
| `failed` (urgent) | `red` (or `color1`) | `ot3:ui/qml/T3Palette.qml:13`, `omarchy:shell/Commons/Color.qml:159` | `#f7768e` | `#d20f39` |
| `warning` | `orange`, else `yellow`, else accent | `ot3:ui/qml/T3Palette.qml:15` | `#eb927b` | `#d84e2b` |
| `input` | `magenta`, else accent | `ot3:ui/qml/T3Palette.qml:16` | `#ad8ee6` | `#ea76cb` |

Derived uses (no extra role, same colours):

| Use | Colour | Source |
|---|---|---|
| Status label colour per row state | working/goal → `working`; done → `done`; failed → `failed`; limited/woke/approval → `warning`; input → `input`; plan → `waiting`; else `statusMuted` (the lib's `statusRole` names the role; `theme.tokens` `statusColors` has the colour per detail) | `ot3:ui/qml/T3Palette.qml:30-34` |
| Diff | addition `done`, deletion `failed`, hunk header `accent`, context `dim`; changed rows tinted 8 % | `ot3:ui/qml/T3DiffReview.qml:102-103` |
| PR state | open `done`, merged `magenta`, closed `failed` | `ot3:ui/qml/T3PullRequestBadge.qml:20` |
| Text selection | background `accent`, text `background` | `ot3:ui/qml/T3Panel.qml:1740` |
| Links | `accent`, no permanent underline | `ot3:ui/qml/T3Panel.qml:727`; `ot3:docs/NATIVE_VISUAL_AUDIT.md:43` |
| Focus ring (hardware keyboard only) | 1 dp `accent` | `ot3:ui/qml/T3IconButton.qml:20-21`, `ot3:ui/qml/T3ThreadRow.qml:180` |
| Sidebar tint behind the list | black @ 0.09 over background | `ot3:ui/qml/T3Panel.qml:1116` |
| Project chip | the project's slot colour @ 0.14 fill, text in the slot colour | `ot3:ui/qml/T3ProjectIcon.qml:16-17` |
| Project slot palette | `blue, green, magenta, cyan, orange, yellow, red, bright_blue, bright_magenta`, duplicates removed | `ot3:ui/qml/T3Palette.qml:17-22` |
| Scrim | black @ 0.5 | `ot3:ui/qml/T3Panel.qml:386` (Qt palette `shadow`) |

The two sample columns are `theme.tokens` (lib-t3-rust v0.3.4) for
`omarchy:themes/tokyo-night/colors.toml` and `omarchy:themes/catppuccin-latte/colors.toml`,
as `app/src/main/assets/themes/omarchy-themes.json` holds them; `OmarchyThemeCatalogTest`
checks them. Until UI-1f they were computed here with straight sRGB alpha compositing, which
put 19 of these 50 values one step off on one channel (tokyo-night `dim` was `#6c708a`).

## 2. Material 3 `ColorScheme` mapping

Material components keep their behaviour; their colours come from the ot3 roles so
nothing looks like stock Material. `surfaceTint` is transparent, because ot3 has no
tonal elevation (UI_SPEC 3.3).

| M3 role | ot3 role |
|---|---|
| `primary` | `accent` |
| `onPrimary` | `background` (ot3 `highlightedText`, `ot3:ui/qml/T3Panel.qml:386`): `theme.tokens` `onAccent` |
| `primaryContainer` / `onPrimaryContainer` | accent @ 0.14 / `accent` |
| `secondary` / `onSecondary` | `input` / `background` |
| `secondaryContainer` / `onSecondaryContainer` | accent @ 0.14 / `foreground` (navigation bar indicator) |
| `tertiary` / `onTertiary` | `waiting` / `background` |
| `error` / `onError` | `failed` / `background` |
| `errorContainer` / `onErrorContainer` | failed @ 0.14 / `failed` |

The two containers (accent @ 0.14, failed @ 0.14) are the only colours ot3 does not draw:
`theme.tokens` has no role for them, so `Ot3Colors` blends them from the lib's `accent`
and `failed` with straight alpha (`Ot3Blend`) until the lib returns them
(LIB_REQUESTS.md, "theme.tokens: Material containers").
| `background` / `onBackground` | `background` / `foreground` |
| `surface` / `onSurface` | `background` / `foreground` |
| `surfaceVariant` / `onSurfaceVariant` | `surface` / `dim` |
| `surfaceContainerLowest` | `background` |
| `surfaceContainerLow` | `changesCard` |
| `surfaceContainer` | `surface` |
| `surfaceContainerHigh` | `rowSelected` |
| `surfaceContainerHighest` | `cardBorder` |
| `surfaceBright` / `surfaceDim` | `rowSelected` / `background` |
| `outline` | `cardBorder` |
| `outlineVariant` | `divider` |
| `inverseSurface` / `inverseOnSurface` / `inversePrimary` | `foreground` / `background` / `accent` |
| `scrim` | black |
| `surfaceTint` | transparent |

## 3. Typography

ot3 base scale: `omarchy:shell/Commons/Style.qml:279,327-338` (base 12 px). Family: the
system UI font for text (ot3 sets no family on most `Text`, so Qt uses the application
font, `ot3:app/shell.qml:3`), `monospace` for code, keybindings and diffs
(`ot3:ui/qml/T3Panel.qml:912`, `ot3:ui/qml/T3DiffReview.qml:210`).

| ot3 token | ot3 px | Android sp | M3 slot | Used for |
|---|---|---|---|---|
| caption | 10 | 12 | `bodySmall`, `labelSmall` | project line, status label, time, field text in ot3 (`ot3:ui/qml/T3ThreadRow.qml:237,288`) |
| bodySmall | 11 | 13 | `labelMedium` | tooltips (`ot3:ui/style/OmarchyT3/ToolTip.qml:49`) |
| body | 12 | 14 | `bodyMedium`, `labelLarge` (Medium) | messages, composer, row title (Medium) (`ot3:ui/qml/T3Panel.qml:1741`, `ot3:ui/qml/T3Composer.qml:410`, `ot3:ui/qml/T3ThreadRow.qml:310`) |
| subtitle | 13 | 15 | `bodyLarge` | settings row labels |
| title | 14 | 16 | `titleSmall` (Medium) | sheet titles, card headers |
| heading | 16 | 19 | `titleMedium` (SemiBold) | page titles (`ot3:ui/qml/T3ScheduledTasks.qml:30`) |
| display | 24 | 28 | `headlineSmall` | settings page title on tablets |
| displayLarge | 28 | 33 | `headlineMedium` | empty-thread "T3" mark uses 42 px → 49 sp (`ot3:ui/qml/T3Panel.qml:1941-1948`) |
| Markdown h1 / h2 / h3 | 20 / 18 / 16 | 23 / 21 / 19 | — | weight 600, line height 1.3 (`ot3:ui/js/MarkdownStyle.js:36-37`) |
| Markdown code | body × 12/14 = 10 | 12 (mono) | — | `ot3:ui/js/MarkdownStyle.js:39` |
| Wordmark "Code" / "OMARCHY" | 18 bold / 8, letter spacing 2 | 21 bold / 9, letter spacing 2.3 sp | — | `ot3:ui/qml/T3Panel.qml:1147,1149` |
| Count pill digits | 8 bold | 9 bold | — | `ot3:ui/qml/T3StatusBadges.qml:19` |

Line height: 1.5 for prose and list items, 1.3 for headings
(`ot3:ui/js/MarkdownStyle.js:34,36`); block spacing 10.4 px → 12 sp (`:33`).
Text inputs on Android use body (14 sp) even where ot3 uses caption, so the soft keyboard
does not edit 12 sp text (UI_SPEC rule 3).

Monospace: bundle **Fira Code** (SIL OFL 1.1), Regular and Medium, as the app's mono
family. Nerd Font glyphs are not needed: ot3 uses them only for optional glyphs
(`ot3:ui/qml/T3IconButton.qml:8`, `ot3:ui/js/MarkdownLinks.js:30`) and Android draws
those as vector icons (UI_SPEC 3.8).

## 4. Spacing and sizes

Spacing scale (`omarchy:shell/Commons/Style.qml:234-260`), 1 px = 1 dp:

| Token | dp | Token | dp |
|---|---|---|---|
| xxs | 2 | xl | 10 |
| xs | 3 | xxl | 12 |
| sm | 4 | xxxl | 14 |
| md | 6 | huge | 18 |
| lg | 8 | panelPadding / popupPadding | 18 / 14 |

Component geometry:

| Component | ot3 | Android | Source |
|---|---|---|---|
| Thread card row | 82 high, radius 6, padding 10 × 8 | 82 dp min (grows with font scale), radius 6, padding 10 × 8, 4 dp gap | `ot3:ui/qml/T3ThreadRow.qml:92,178,218`; `ot3:docs/NATIVE_VISUAL_AUDIT.md:60` |
| Thread slim row | 36 high | **48 dp** (touch minimum) | `ot3:ui/qml/T3ThreadRow.qml:92` |
| Shelf header | 32 high, radius 5, label + inline rule + chevron | 40 dp drawn, 48 dp target | `ot3:ui/qml/T3Panel.qml:1231-1233`; `ot3:docs/NATIVE_VISUAL_AUDIT.md:74` |
| Project chip | 16, radius 4, text 50 % of height, bold | 16 dp, radius 4 | `ot3:ui/qml/T3ProjectIcon.qml:13-17` |
| Icon button | 28 × 28, radius 6 | 48 dp target, 20 dp icon, 36 dp pressed shape radius 6 | `ot3:ui/qml/T3IconButton.qml:22-24` |
| Inline icon | 16 | 16 dp | `ot3:ui/qml/T3Icon.qml:8-10` |
| Text field | radius 6 | radius 6, 48 dp high | `ot3:ui/qml/T3TextField.qml:19` |
| Select | 30 high, radius 7 | 40 dp drawn, radius 7; opens a sheet | `ot3:ui/qml/T3Select.qml:9,30` |
| Button | 36 high, padding 15, radius 7 | 40 dp drawn, padding 15, radius 7 | `ot3:ui/qml/T3PickerButton.qml:10-14` |
| Switch | 32 × 18, knob 12 | 36 × 20 dp, knob 14 dp, 48 dp target | `ot3:ui/qml/T3Switch.qml:11,17` |
| User bubble | radius 16, padding 12, max 80 % of lane, gap 16 (8 for commentary) | same | `ot3:ui/qml/T3Panel.qml:1665,1666,1702,1704,1728` |
| Content lane | max 736 | max 736 dp on tablets; full width minus 14 dp gutters on phones | `ot3:ui/qml/T3Panel.qml:177-178` |
| Composer | radius 17, margin 12, border 1, editor 56–130 high | radius 17, margin 12, editor 1–6 lines | `ot3:ui/qml/T3Composer.qml:239,264-269` |
| Send button | 32 circle | 40 dp circle in a 48 dp target | `ot3:ui/qml/T3Composer.qml:729-731` |
| File chip | 22 high, padding 7, icon 13, radius h/3.2 | 28 dp high, same proportions | `ot3:ui/qml/T3Composer.qml:65,73` |
| Attachment thumbnail (composer / message) | 56 × 56 / 220 × 160, radius 8 | same | `ot3:ui/qml/T3Composer.qml:343-359`; `ot3:ui/qml/T3MessageImage.qml:18-42` |
| Card (approval, question, queue) | radius 10, fill fg 0.035, border fg 0.12 | same | `ot3:ui/qml/T3Approvals.qml:18`, `ot3:ui/qml/T3Questions.qml:25` |
| Changed-files card | radius 10, padding 11, rows 25 | radius 10, padding 11, rows 40 dp | `ot3:ui/qml/T3Changes.qml:14-18,32` |
| Model picker rows / provider rail | 52 high / 44 wide | 56 dp / 56 dp | `ot3:ui/qml/T3ModelPicker.qml:65-92,118-144` |
| Menu / dialog / hover card | radius 7 / 10 / 8 | sheets: top radius 16; dialogs radius 10 | `ot3:ui/qml/T3ThreadRow.qml:327,348,412-413` |
| Count pills | 10 high, radius 2.5, widths 14 / 18 / 23 | 12 dp high, radius 3, widths 16 / 21 / 27 dp | `ot3:ui/qml/T3StatusBadges.qml:8,18,25` |

Borders are always 1 dp. Disabled content: 0.3 opacity for icon buttons, 0.4 for buttons
and switches (`ot3:ui/qml/T3IconButton.qml:25`, `ot3:ui/qml/T3PickerButton.qml:12`,
`ot3:ui/qml/T3Switch.qml:15`).

## 5. Motion

| Element | ot3 | Android | Source |
|---|---|---|---|
| Colour state changes | 130 ms | `tween(130)` | `ot3:ui/qml/T3ThreadRow.qml:181` |
| Text field border | 140 ms | `tween(140)` | `ot3:ui/qml/T3TextField.qml:23` |
| Switch knob | 120 ms OutCubic | `tween(120, EaseOutCubic)` | `ot3:ui/qml/T3Switch.qml:17` |
| Row arrives | 360 ms, opacity OutCubic, drift −18 → 0 OutBack 1.2 | `animateItem` placement spec with the same curves | `ot3:ui/qml/T3ThreadRow.qml:127-136` |
| Row departs | 300 ms InCubic | `animateItem` fade-out spec | `ot3:ui/qml/T3ThreadRow.qml:138-142` |
| Row moves between shelves | 520 ms InOutCubic | `animateItem` placement 520 ms | `ot3:ui/qml/T3Panel.qml:586,757` |
| Status icon pop | 460 ms OutBack 2.4 | spring approximating OutBack 2.4 | `ot3:ui/qml/T3ThreadRow.qml:283` |
| Spinner | 1.25 s cycle, arc stretches | same | `ot3:ui/qml/WorkIndicator.qml:21,47` |
| Live activity shimmer | 72 px band, 2.2 s cycle | 72 dp band, 2.2 s | `ot3:ui/qml/T3ActivityText.qml:18-35` |
| Navigation and panes | — | predictive back defaults; panes 260 ms cubic (T3 Mobile) | `t3m:src/features/layout/workspace-pane-animation.ts:8-11` |

All of it stops when the system animator scale is 0, following T3 Mobile's rule that each
animation opts into reduced motion (`t3m:src/lib/designTokens.ts:8`).
