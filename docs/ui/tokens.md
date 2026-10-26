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

Companion to UI_SPEC section 3.4 (rules, surfaces, reduced motion, acceptance). Rewritten
2026-10-25 from a full read of ot3's QML at the pinned snapshot; it corrects the first
version of this table (row arrival and departure durations, row-flight curve, missing
show/hide transitions).

### 5.1 `T3Motion.qml`

`T3Motion` is a two-property singleton, not a duration table
(`ot3:ui/qml/T3Motion.qml:5-12`). It drives one component, `T3Reveal`, the show/hide
transition of the sidebar, shelf headers and lists, find bar, usage strip, queue, tasks
and project drafts (`ot3:ui/qml/T3Reveal.qml:18-19,34`; instances `ot3:ui/qml/T3Panel.qml:1107,1227,1246,1272,1291,1315,1341,1504,1520`,
`ot3:ui/qml/T3Queue.qml:35`, `ot3:ui/qml/T3Tasks.qml:49`, `ot3:ui/qml/T3Composer.qml:272`).
Every other ot3 animation hard-codes its duration (5.3).

| Property | Values | Default | Set from | Source |
|---|---|---|---|---|
| `style` | `nebula`, `warp` ("Hyperspace"), `fold`, `spring`, `instant` | `nebula` | Preferences → Animations → **Show and hide** (`transitionStyle`) | `ot3:ui/qml/T3Motion.qml:8-9`; `ot3:ui/qml/T3Panel.qml:567`; `ot3:ui/qml/T3Preferences.qml:272` |
| `pace` | slow 1.6, normal 1, fast 0.6 (duration multiplier) | 1 | **Transition speed** (`transitionSpeed`) | `ot3:ui/qml/T3Motion.qml:10-11`; `ot3:ui/qml/T3Panel.qml:568`; `ot3:ui/qml/T3Preferences.qml:273` |

`T3Reveal` per style (`ot3:ui/qml/T3Reveal.qml:30-37`), every duration × `pace`,
overshoot 1.3:

| Style | ot3 | Compose | Notes |
|---|---|---|---|
| nebula (default) | 560 ms InOutCubic | `tween(560 × pace, easing = Ot3Easing.InOutCubic)` on a `progress` 0→1 | layout gap opens only from 60 % of progress, `reach = min(1, extent / 0.6)` (`:26`); content dissolves through `reveal.frag` while moving (`:60-70`) |
| warp | 460 ms InOutQuart | `tween(460 × pace, Ot3Easing.InOutQuart)` | `reveal.frag` |
| fold | 500 ms InOutSine | `tween(500 × pace, Ot3Easing.InOutSine)` | `reveal.frag` |
| spring | 520 ms OutBack 1.3 | `tween(520 × pace, Ot3Easing.outBack(1.3f))` | slide by (1 − extent) × size, opacity min(1, extent × 1.8), scale along the axis max(0.9, progress) (`:47,53,55-59`); ot3 forces it with the software renderer (`:17-19`) |
| instant | no animation | `snap()` | `:31` |

Compose: `Ot3Reveal(shown, horizontal = false)`, a `Layout` whose measured size is
`size × reach` and whose content gets the style's `graphicsLayer`; nebula, warp and fold
apply an AGSL port of `reveal.frag` on API 33+ (`RenderEffect.createRuntimeShaderEffect`)
and fall back to **spring** below API 33, as ot3 falls back with the software renderer.

### 5.2 Easing curves

QML's easing types are Robert Penner's functions; Compose's `EaseOutCubic` and friends
are cubic-Bézier approximations, and `CubicBezierEasing` cannot express OutBack with
ot3's overshoots. `Ot3Easing` therefore implements the exact functions as Compose
`Easing { t -> … }` (a `fun interface`):

| ot3 (`Easing.*`) | Uses | `Ot3Easing` | Nearest built-in (for previews only) |
|---|---|---|---|
| Linear (QML default when no easing is set) | colour behaviours, minimap, mic ring | `LinearEasing` | same |
| OutCubic | 15 | `1 − (1 − t)³` | `EaseOutCubic` = `CubicBezierEasing(0.33f, 1f, 0.68f, 1f)` |
| InCubic | 4 | `t³` | `EaseInCubic` |
| InOutCubic | 4 | `t < .5 ? 4t³ : 1 − (−2t + 2)³ / 2` | `EaseInOutCubic` |
| InOutQuart | 2 | `t < .5 ? 8t⁴ : 1 − (−2t + 2)⁴ / 2` | `EaseInOutQuart` |
| InOutSine | 5 | `−(cos(πt) − 1) / 2` | `EaseInOutSine` |
| OutQuad | 1 | `1 − (1 − t)²` | `EaseOutQuad` |
| OutBack(s) | 6 (s = 1.2, 1.3, 1.6, 2.2, 2.4) | `u = t − 1; u²((s + 1)u + s) + 1` | `EaseOutBack` is s ≈ 1.70158 only |

Counts are easing declarations in `ui/qml` at the pin. A `spring()` is used only where
the reference is a spring (T3 Mobile's swipe, 5.4); ot3's OutBack is never replaced by a
spring, so durations stay exact.

### 5.3 Every ot3 duration, by surface

| Surface | Element | ot3 | Compose | Source |
|---|---|---|---|---|
| Rows | Row speed `rowMoveMs` | slow 820, normal 520, fast 300 ms (**Section change speed**) | `Ot3Motion.rowMoveMs` | `ot3:ui/qml/T3Panel.qml:586`; `ot3:ui/qml/T3Preferences.qml:267` |
| | Row arrives | `arrive(rowMoveMs × 0.8)` = 416 ms: opacity 0 and drift x −18 at once, pause 20 % (83 ms), then 80 % (333 ms) opacity → 1 OutCubic and drift → 0 OutBack 1.2 | `animateItem(fadeInSpec = tween(333, 83, OutCubic))` + `graphicsLayer.translationX` from −18 dp, `tween(333, 83, outBack(1.2f))` (mirrored in RTL) | `ot3:ui/qml/T3Panel.qml:641,657`; `ot3:ui/qml/T3ThreadRow.qml:123,127-136` |
| | Row departs | `depart(rowMoveMs × 0.6)` = 312 ms: opacity → 0 and drift → +22, both InCubic; the list keeps the slot rowMoveMs + 60 = 580 ms | `animateItem(fadeOutSpec = tween(312, easing = InCubic))` + translationX → +22 dp | `ot3:ui/qml/T3Panel.qml:635,750`; `ot3:ui/qml/T3ThreadRow.qml:138-142` |
| | Neighbours close or open the gap | rowMoveMs × 0.85 = 442 ms OutCubic | `animateItem(placementSpec = tween(442, easing = OutCubic))` | `ot3:ui/qml/T3Panel.qml:751-754` |
| | Row moves inside a list | rowMoveMs = 520 ms InOutCubic | placement `tween(520, InOutCubic)` | `ot3:ui/qml/T3Panel.qml:755-758` |
| | Row changes section (flight) | `t` 0 → 1 over rowMoveMs; **Swoop** (`arc`, default) InOutQuart, **Glide** InOutCubic; bow = sin(πt); arc swing min(26, \|dy\| × 0.14), tilt ±1.4° × bow, scale 1 + 0.04 × bow (glide 0.015), shadow black @ 0.32 × bow, cross-fade smoothstep(0.18, 0.82); a row whose target is hidden (collapsed shelf) sinks and fades with smoothstep(0.55, 1); **Fade** = depart + arrive, **Off** = none | placement `tween(520, InOutQuart)` for the moving key plus a per-key `Animatable` `t` driving `graphicsLayer` (translationX = swing × bow, rotationZ, scale, `shadowElevation`); Fade and Off as ot3 | `ot3:ui/qml/T3RowFlight.qml:17-20,49-61,85-88`; `ot3:ui/qml/T3Panel.qml:585,608,611` |
| | Status icon change | scale 0.3 → 1, 460 ms OutBack 2.4; opacity 0 → 1, 200 ms OutCubic | two `Animatable`s restarted on label change | `ot3:ui/qml/T3ThreadRow.qml:279-286` |
| | Status icon tint | 320 ms linear | `animateColorAsState(tween(320, LinearEasing))` | `ot3:ui/qml/T3ThreadRow.qml:272` |
| | Working icon spin | `circle-dashed` turns 112.5°/s from the sky clock, only while the sky animates | `rotationZ = clock × 112.5f` from the shared `Ot3Ticker` | `ot3:ui/qml/T3ThreadRow.qml:276-278` |
| | Row background (pressed / selected / draft) | 130 ms linear | `animateColorAsState(tween(130, LinearEasing))` | `ot3:ui/qml/T3ThreadRow.qml:181` |
| | Draft pen reveal | 280 ms OutCubic; width × reveal, scale 0.4 + 0.6 × reveal | `animateFloatAsState(tween(280, OutCubic))` | `ot3:ui/qml/T3ThreadRow.qml:224-227` |
| Shelves | Header and list show / hide | `T3Reveal` (5.1) | `Ot3Reveal` | `ot3:ui/qml/T3Panel.qml:1227-1341` |
| | Chevron | icon swap `chevron-down` / `chevron-up`, not animated | same | `ot3:ui/qml/T3Panel.qml:1242,1287,1336` |
| Count pills | Digits and pills | not animated | same | `ot3:ui/qml/T3StatusBadges.qml:1-28` |
| Sky | Energy (idle ↔ busy) | 1 600 ms InOutSine | `animateFloatAsState(tween(1600, InOutSine))` | `ot3:ui/qml/T3Starfield.qml:27-28` |
| | Drift speed | 5 px/s idle → 42 px/s busy, weighted by energy, × star speed slow 0.5 / normal 1 / fast 1.8 | uniform per frame, dp/s | `ot3:ui/qml/T3Starfield.qml:29,33,106,108`; `ot3:ui/qml/T3Panel.qml:853` |
| | Meteors | rate 0.85 in the header (0.3 on the panel sky) × **Shooting stars** none 0 / few 0.4 / normal 1 / many 2.2 × energy | uniform | `ot3:ui/qml/T3Panel.qml:850,854,1126`; `ot3:ui/qml/T3Starfield.qml:47-48` |
| | Frame interval | idle 125 ms, busy 33 ms, explicit Full 16 ms, a meteor on screen 8 ms; Light ≥ 40 ms except during a meteor; Saver caps at 33 ms, Max uses 8 ms; dt clamped to 0.25 s | `withFrameNanos` loop that skips frames to the same intervals | `ot3:ui/qml/T3Starfield.qml:98-99`; `ot3:ui/qml/T3Ticker.qml:22-27,52` |
| Timeline | Spinner (`WorkIndicator`) | phase time / 1.25 s; head and tail 270° InOutCubic offset 0.6; rotation 225°/s; min sweep 14°; track alpha 0.22; entry opacity 220 ms OutCubic and scale 0.6 → 1 320 ms OutBack 1.6 | `Ot3Spinner` on `Ot3Ticker` | `ot3:ui/qml/WorkIndicator.qml:21-27,35,44,47,49-53` |
| | Live activity shimmer | 72 px band, 2.2 s cycle, base text opacity 0.50 | 72 dp band, `infiniteRepeatable(tween(2200, LinearEasing))` | `ot3:ui/qml/T3ActivityText.qml:12,17,33` |
| | New messages, activity expand, jump to latest, copy button | not animated (copy only resets after 1.8 s) | same | `ot3:ui/qml/T3Panel.qml:1934-1940`; `ot3:ui/qml/T3CopyButton.qml:18` |
| | Minimap marker | 150 ms linear | `tween(150, LinearEasing)` | `ot3:ui/qml/T3TimelineMinimap.qml:103,105` |
| | Wheel glide | `clamp(\|d\| × 5, 80, 1000)` ms OutCubic | not ported: touch scrolling uses Android's fling | `ot3:ui/js/WheelMotion.js:4,15` |
| Composer | Focus border, send button colour | 130 ms linear | `tween(130, LinearEasing)` | `ot3:ui/qml/T3Composer.qml:265-266,732` |
| | Draft saved check | opacity 240 ms in / 140 ms out OutCubic; scale 0.35 → 1, 420 ms OutBack 2.2 in, 140 ms InCubic out | same values | `ot3:ui/qml/T3Composer.qml:683-689` |
| | Draft saving spinner | opacity 160 ms in / 260 ms out InOutSine | same | `ot3:ui/qml/T3Composer.qml:694` |
| | Dictation bubble | grow 520 ms OutBack 1.6 / shrink 220 ms InCubic; red dot 1 → 0.35 → 1 over 700 + 700 ms InOutSine, looping; level bars 120 ms OutQuad; mic ring 70 ms linear; glow and border colour 120 ms | out of v1 with dictation (UI_SPEC 4.4.8); values kept for when it lands | `ot3:ui/qml/T3Composer.qml:536,559-562,589,603,624-628,642` |
| Controls | Switch knob | 120 ms OutCubic | `tween(120, OutCubic)` | `ot3:ui/qml/T3Switch.qml:17` |
| | Text field border | 140 ms linear | `tween(140, LinearEasing)` | `ot3:ui/qml/T3TextField.qml:23` |
| | Icon button, picker row press | 130 / 100 ms linear | same | `ot3:ui/qml/T3IconButton.qml:26`; `ot3:ui/qml/T3PickerRow.qml:18` |
| | Tooltip | in 120 ms, out 80 ms | same | `ot3:ui/style/OmarchyT3/ToolTip.qml:53-54` |
| Panels | Panel card geometry / opacity | 190 ms OutCubic / 140 ms OutCubic | desktop window chrome: not ported (navigation follows T3 Mobile, 5.4) | `ot3:ui/qml/PinnedKeyboardPanel.qml:400-408,426-429` |
| Quota and usage | Bars | not animated in ot3 (fills are bindings) | Android adds none either; the strip itself appears through `Ot3Reveal` | `ot3:ui/qml/QuotaStrip.qml:87`; `ot3:ui/qml/T3UsagePage.qml:58,71`; `ot3:ui/qml/T3Panel.qml:1520` |
| OmaBridge | Indicator | not animated (60 s refresh timer) | same | `ot3:ui/qml/T3OmaBridgeIndicator.qml:29` |
| Auras | Row comet, pulse, glints, burst | aura fade 600 ms, burst 1 300 ms, pulse sin(clock × 1.8), glints fract(clock × 0.45) | not ported in v1 (UI_SPEC 3.7) | `ot3:ui/qml/T3ThreadRow.qml:144-150,194-195`; `ot3:ui/assets/shaders/threadaura.frag:88,95,108,114` |

### 5.4 T3 Mobile motion kept for navigation

| Element | T3 Mobile | Compose | Source |
|---|---|---|---|
| Stack push / pop | react-native-screens default on Android; `none` for Thread and PR in the split layout, and for the Settings tab | Navigation Compose default enter/exit, predictive back on; `EnterTransition.None` for detail replacement in the split layout | `t3m:src/Stack.tsx:947,1059-1063`; `t3m:src/lib/android-workspace-navigation.ts:106-110` |
| Predictive back | opted in | `android:enableOnBackInvokedCallback="true"`, `PredictiveBackHandler` for sheets and search | `t3m:app.config.ts:311,461` |
| Panes | 260 ms `Easing.inOut(Easing.cubic)`, `ReduceMotion.System` | `tween(260, Ot3Easing.InOutCubic)` | `t3m:src/features/layout/workspace-pane-animation.ts:8-12` |
| Sheets | native form sheet; RN modals fade or slide | M3 `ModalBottomSheet` defaults | `t3m:src/native/sheet-surface.ts:1-3`; `t3m:src/features/keyboard/CommandPalette.tsx:417` |
| Swipe actions | spring damping 26, mass 0.7, stiffness 330, overshoot clamped; dismissal slide and fade 220 ms out-cubic, then the row collapses 180 ms | `spring(dampingRatio = 0.855f, stiffness = 471f)` with the value clamped at the target; `tween(220, OutCubic)` then `tween(180)` height | `t3m:src/features/home/thread-swipe-actions.tsx:54-59,338-351` |
| Connection dot while connecting | pulse, 1 100 ms out-cubic, repeating | `infiniteRepeatable(tween(1100, OutCubic), RepeatMode.Reverse)` on alpha | `t3m:src/features/connection/ConnectionStatusDot.tsx:58-61` |
| Usage tabs | `FadeIn.duration(160)` | `fadeIn(tween(160))` | `t3m:src/features/usage/UsageRouteScreen.tsx:261-263` |
| Extended FAB | expands at y ≤ 8, toggles after 12 px of opposite travel; Compose's own animation | `ExtendedFloatingActionButton(expanded = …)` | `t3m:src/features/home/fab-scroll.ts:13-15`; `t3m:src/features/home/ScrollComposeButton.android.tsx:47-48` |
| Reduced motion | "each animation must opt into system reduced motion"; `MOBILE_MOTION` adjust 120, enter 200, exit 150 | UI_SPEC 3.4.4 | `t3m:src/lib/designTokens.ts:8-13` |

The spring conversion: Reanimated's (k = 330, c = 26, m = 0.7) has ω = √(k/m) = 21.7 rad/s
and ζ = c / (2√(km)) = 0.855; Compose's `spring` takes stiffness = ω² = 471 for unit mass.
