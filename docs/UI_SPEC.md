# ot3-Android UI specification

Status: proposal, 2026-10-25. Scope: every screen of ot3-Android v1, its visual system,
and the PR plan that gets there from the current MVP. Code is out of scope; this
document only decides what the code must do.

The current app connects with a URL and a token, shows a live sidebar and a live thread,
and sends messages. It works, but it looks like stock Material 3: a dynamic-colour theme
(`app/src/main/kotlin/dev/avillagran/ot3android/ui/theme/Theme.kt:16-33`), a plain
`TopAppBar` and text rows (`.../ui/sidebar/SidebarScreen.kt:97-272`), and a one-line
banner for approvals (`.../ui/thread/ThreadScreen.kt:316`). The goal is **a mobile
version of ot3**: ot3's look and behaviour, arranged the way T3 Mobile arranges T3 Code
on a phone, with every derived value coming from `lib-t3-rust`.

Auxiliary tables:

- [ui/tokens.md](ui/tokens.md): colour roles with golden values, type scale, sizes, motion.
- [ui/omarchy-themes.md](ui/omarchy-themes.md): importing Omarchy themes into the APK.
- [ui/strings.md](ui/strings.md): string sourcing and the mobile-only strings.
- [ui/screen-map.md](ui/screen-map.md): every ot3 audit row mapped to an Android screen and PR.
- [ui/reference/](ui/reference/README.md): 13 ot3 captures used as the visual target.

## 0. Sources and citations

Every claim about ot3 or T3 Mobile cites `file:line` in a pinned snapshot:

| Prefix | Repository | Pin | Paths relative to |
|---|---|---|---|
| `ot3:` | omarchy-t3-code (desktop, QML) | `7b5b1de` (main, 2026-10-24) | repository root |
| `t3m:` | t3code-nightly, T3 Mobile (Expo) | `680152e27` | `apps/mobile/` |
| `t3:` | t3code-nightly, other packages and docs | `680152e27` | repository root |
| `lib:` | lib-t3-rust | `v0.3.2` (`26e67a7`), the version in `libs/lib-t3-rust.lock` | repository root |
| `omarchy:` | Omarchy 4.0.2 (MIT) | installed package | `/usr/share/omarchy/` |
| none | this repository | `ae993ba` | repository root |

Two caveats from the research. The line numbers inside ot3's own audit matrices refer to
an older working tree (`ot3:docs/audit/CHAT_PARITY.md:3`), so this spec cites ot3 code,
not audit cells. Some ot3 captures in `ui/reference/` predate the pinned snapshot;
where a capture and the code differ, the code wins.

## 1. Principle

ot3-Android is **ot3 on a phone**: the screens, navigation and gestures of T3 Mobile,
wearing ot3's palette, typography, components, wording and behaviour, fed only by
`lib-t3-rust`.

| Layer | Source of truth | Why |
|---|---|---|
| Screen hierarchy, navigation, gestures, sheets, tablet columns | T3 Mobile | It is T3 Code's own answer for touch screens, tested on phones, tablets and foldables. ot3's layout is a desktop side panel that does not fit a 6-inch screen. |
| Colours, density, typography, iconography, component shapes | ot3 | The owner's goal: it must look like ot3. ot3 takes its colours from the live Omarchy theme, so the Android app ships the Omarchy themes (3.1). |
| Texts, statuses, ordering, what each action does | ot3, which follows T3's semantics | The same thread must read the same way on the desktop and on the phone. Strings come from ot3's catalog (3.6). |
| Every derived value (status, order, activity label, changed files, pending requests, queue, model rows, diff hunks) | `lib-t3-rust` | Architecture rule: no protocol logic in Kotlin (`docs/ARCHITECTURE.md:15-16`). Missing pieces go to `LIB_REQUESTS.md` (section 6). |

### Decision rules when the two references disagree

1. **Navigation and gestures: T3 Mobile wins.** Back is the system back gesture
   (predictive back, as T3 Mobile opts in at `t3m:app.config.ts:311`), never an
   on-screen "Back to chat" link. A desktop popup becomes a bottom sheet; a context menu
   becomes a long-press sheet; a hover action becomes a swipe action or a long-press item.
2. **Appearance: ot3 wins.** Colours, row anatomy, badge wording and colours, card radii,
   the sky header and the icon set are ot3's, even where Material 3 has a default.
   Material 3 is used for its components' behaviour (accessibility, IME, ripple, sheet
   physics), restyled with ot3 tokens.
3. **Touch beats density.** ot3's density is kept, but every interactive element gets a
   48 dp touch target, by widening the hit area, not the drawing. The one visible
   exception is the slim thread row, which grows from 36 to 48 dp (3.3).
4. **Wording and semantics: ot3 wins**, unless T3 Mobile has a state ot3 lacks (for
   example "offline, showing saved messages"); then the state is added with an
   ot3-style string listed in [ui/strings.md](ui/strings.md).
5. **Platform conventions win over both** where Android users rely on them: edge-to-edge
   with system-bar insets, IME insets for the composer, the share sheet, the notification
   permission, `sp` text that follows the system font scale, and RTL (ot3 ships Arabic).
6. **Data beats both.** If a screen needs a value neither the lib nor the snapshot gives,
   the screen shows less until the lib exposes it. No Kotlin heuristics.
7. **Desktop-only stays out** (4.13).

## 2. Navigation map

### 2.1 Destinations

Compose: one activity, Navigation Compose with **typed `@Serializable` routes** (this
settles the pending decision at `docs/DECISIONS.md:294`), nested graphs per tab.

| Android destination | Screen | T3 Mobile counterpart | Presentation | Reached from |
|---|---|---|---|---|
| `Home` | Threads of every connected environment (4.1) | `Home` → `WorkspaceRootScreen` (`t3m:src/Stack.tsx:707-716`) | root of the **Chats** tab | launch, tab |
| `Thread(environmentId, threadId, prompt?)` | Thread and composer (4.3, 4.4) | `Thread` (`t3m:src/Stack.tsx:776-780`; params `t3m:src/features/threads/ThreadRouteScreen.tsx:152-156`) | push | row tap, palette, notification, deep link |
| `ThreadChanges(environmentId, threadId, source, path?)` | Changes and diff (4.6) | `ThreadReview` (`t3m:src/Stack.tsx:806-810`) | push | changed-files card, Git sheet |
| `NewThread` → `NewThreadDraft(...)` | New thread (4.7) | `NewTaskSheet` nested stack (`t3m:src/Stack.tsx:457-546`), a card on Android (`t3m:src/Stack.tsx:1050-1076`) | push | FAB, palette, "New thread on {branch}" |
| `AddProject(...)` | Add project (4.7.4) | `AddProject` flow (`t3m:src/Stack.tsx:522-544`) | push | New thread, Home overflow |
| `PullRequests` | Pull request list (4.8) | Android workspace PR tab (`t3m:src/features/workspace/WorkspaceRootScreen.android.tsx:70-78`) | root of the **PRs** tab | tab |
| `PullRequest(environmentId, projectId, repository, number)` | Pull request detail (4.8) | `PullRequest` (`t3m:src/Stack.tsx:717-724`) | push | PR row, PR badge, link in a message |
| `PullRequestFile(...)` | One file of a PR | `PullRequestFile` (`t3m:src/Stack.tsx:725-732`) | push | PR files sheet |
| `Settings` and its pages | Settings (4.9) | `SettingsSheet` nested stack (`t3m:src/Stack.tsx:425-450,938-949`), a tab page on Android (`t3m:src/Stack.tsx:572-577`) | root of the **Settings** tab | tab |
| `Environments`, `AddEnvironment(mode?, pairingUrl?)` | Environments (4.2) | `Connections`, `ConnectionsNew` (`t3m:src/Stack.tsx:972-998`; params `t3m:src/features/connection/ConnectionsNewRouteScreen.tsx:36-41`) | push | Settings, header status, empty Home |
| `Usage`, `Archive`, `Licenses` | 4.10, 4.12, 4.9 | Settings sub-routes (`t3m:src/Stack.tsx:213-422`) | push | Settings |
| `CommandPalette` | Command palette (4.11) | `t3m:src/features/keyboard/CommandPalette.tsx:136` | full-screen dialog | Home overflow, Ctrl+K |

The three tabs (Chats, PRs, Settings) are T3 Mobile's Android tabs
(`t3m:modules/t3-native-controls/android/src/main/java/expo/modules/t3nativecontrols/T3WorkspaceNavigationModule.kt:66,357-365`):
a `NavigationBar` on phones, hidden while the keyboard is up
(`t3m:src/features/workspace/AndroidWorkspaceNavigation.android.tsx:78`), and a 72 dp
rail on tablets (2.4). They replace ot3's sidebar-header PR toggle
(`ot3:ui/qml/T3Panel.qml:1152`) and footer Preferences button
(`ot3:ui/qml/T3Panel.qml:1372`). Selecting a thread or a pull request resets the tab's
stack to `[root, destination]`, as T3 Mobile does
(`t3m:src/lib/android-workspace-navigation.ts:30-50`).

Change from today: the current graph is `environments → sidebar/{environmentId} →
thread/{environmentId}/{threadId}` plus `connect` (`app/src/main/kotlin/dev/avillagran/ot3android/ui/navigation/Ot3NavHost.kt:26-38`).
Home now lists the threads of every enabled environment in one list, which is what both
references do (T3 Mobile adds the environment label only when more than one is saved,
`t3m:src/features/home/HomeScreen.tsx:583-586,649-652`; ot3 keys rows by environment,
`ot3:ui/js/SidebarModel.js:60`). The environment list moves under Settings, and
`connect` becomes `AddEnvironment`.

### 2.2 Sheets

Bottom sheets are M3 `ModalBottomSheet`s hosted by a small sheet navigator, so each has a
back-stack entry, survives process death and gets predictive back, like T3 Mobile's
form sheets (`t3m:src/native/sheet-surface.ts:1-3`). Styling: background `background`,
top radius 16 dp, a 1 dp `cardBorder` top edge, ot3 drag handle (32 × 4 dp, fg @ 0.15),
scrim black @ 0.5. Two stops: a partial height (the first detent below) and 0.92 of the
window.

| Sheet | Content (ot3) | T3 Mobile counterpart, detents | Stops |
|---|---|---|---|
| Thread actions | Row context menu (`ot3:ui/qml/T3ThreadRow.qml:378-410`) | Long-press menu (`t3m:src/features/threads/thread-list-v2-items.tsx:1342-1365`) | content height |
| Snooze until | In 1 hour, In 3 hours, This evening, Tomorrow, Next week, Custom… (`ot3:ui/qml/T3ThreadRow.qml:104-110,356-367`) | "Snooze until" menu (`t3m:src/features/threads/threadListV2.ts:95-118`) | content |
| Project actions | Rename, Group into…, Copy Path, Project settings, Remove (`ot3:ui/qml/T3ProjectMenu.qml:62-72`) | — | content |
| Thread list options | Status, Project, Environment filters (`ot3:ui/qml/T3Panel.qml:1069-1104`) | "Thread list options" (`t3m:src/features/home/home-list-filter-menu.ts:32-91`) | content |
| Model picker | Provider rail, search, model rows, favourites (`ot3:ui/qml/T3ModelPicker.qml:54-150`) | `ThreadSettingsSheet` (`t3m:src/Stack.tsx:851-864`) | 0.92 |
| Model options | Effort and descriptors (`ot3:ui/qml/T3ModelOptions.qml:18-24`) | Options section (`t3m:src/features/threads/ThreadSettingsSheet.tsx:838-937`) | content |
| Permission mode | Supervised, Auto-accept edits, Auto, Full access (`ot3:ui/qml/T3PermissionPicker.qml:11-21`) | Runtime section (`t3m:src/features/threads/thread-settings-options.ts:8-27`) | content |
| Attach | Photos, Files, Camera | "+" menu (`t3m:src/features/threads/ComposerAttachmentButton.tsx:8-59`) | content |
| Send options | Queue, Steer the active run, Send and start new thread | Long-press send (`t3m:src/features/threads/ThreadComposer.tsx:238-289`) | content |
| Queue | Reorder, edit, steer, remove (`ot3:ui/qml/T3Queue.qml:37-80`) | `ThreadQueue` [0.65, 0.95] (`t3m:src/Stack.tsx:865-873`) | 0.65 / 0.95 |
| Agents | Subagent cards (`ot3:ui/qml/T3SubagentCards.qml`) | `ThreadAgents` [0.5, 0.9] (`t3m:src/Stack.tsx:883-891`) | 0.5 / 0.9 |
| Git & changes | Status, changes, actions (`ot3:ui/qml/T3GitControls.qml`) | `GitOverview` [0.55, 0.92] (`t3m:src/Stack.tsx:892-900`) | 0.55 / 0.92 |
| Thread details | Workspace, branch, model, provider history (`ot3:ui/qml/T3ThreadRow.qml:325-342`) | — | 0.55 / 0.92 |
| Tool inspector | Details, Metadata, Raw item (`ot3:ui/qml/T3ToolInspector.qml:99-165`) | Expanded work rows (`t3m:src/features/threads/thread-work-log.tsx:1276`) | 0.92 |
| Thread pull requests | Linked PRs (`ot3:ui/qml/T3PullRequestBadge.qml:36-55`) | `ThreadPullRequests` [0.55, 0.92] (`t3m:src/Stack.tsx:901-909`) | 0.55 / 0.92 |
| PR files, PR review | File list; comment / request changes / approve (`ot3:ui/qml/T3PullRequests.qml:138-147`) | `PullRequestFiles` [0.6, 0.92], `PullRequestReview` [0.75, 0.92] (`t3m:src/Stack.tsx:733-742,767-775`) | as T3 |
| Branch picker | Search or create a branch (`ot3:ui/qml/T3BranchPicker.qml:48-72`) | `NewTaskBranch` (`t3m:src/features/threads/NewTaskContextPickerScreens.tsx:443,529`) | 0.92 |

Confirmations (Rename, Archive, Delete, Remove connection, Merge) are `AlertDialog`s with
ot3's dialog shape (radius 10, padding 18, accent border, failed border for destructive
actions, `ot3:ui/qml/T3ThreadRow.qml:412-413`) and ot3's texts (4.1.5). Images open in a
full-screen viewer with pinch zoom, like T3 Mobile's `FilePreviewModal`
(`t3m:src/features/threads/ThreadFeed.tsx:460-533`).

### 2.3 Gestures

| Gesture | Where | Behaviour | T3 Mobile source | ot3 desktop equivalent |
|---|---|---|---|---|
| System back, predictive | everywhere | closes the top sheet; then hides the keyboard; then clears and closes search; then detail → list | `t3m:src/features/home/ThreadListToolbar.tsx:60-76`; `t3m:src/features/workspace/useAndroidWorkspaceNavigation.ts:34-49` | Esc (`ot3:ui/qml/T3Panel.qml:1175`) |
| Swipe left on a thread row | Home | Primary action: **Settle** on cards, **Move to active** on settled rows, **Wake** on snoozed rows, **Archive** without settlement support; secondary **Snooze** opens Snooze until. A full swipe past max(actions + 44 dp, 58 % of the width) commits with a haptic. No delete swipe on Home. | `t3m:src/features/threads/threadListV2.ts:95-118`; `t3m:src/features/home/thread-swipe-actions.tsx:81-87,257-259,418` | hover clock and check (`ot3:ui/qml/T3ThreadRow.qml:290-305`) |
| Long press a thread row | Home | Thread actions sheet | `t3m:src/features/threads/thread-list-v2-items.tsx:1342-1365` | right click (`ot3:ui/qml/T3ThreadRow.qml:213-216`) |
| Long press the project chip or name | Home | Project actions sheet | — | right click on the chip (`ot3:ui/qml/T3ThreadRow.qml:235,240`) |
| Long press a message | Thread | Copy, Copy prompt link, Fork from this response, Edit from here | `t3m:src/features/threads/ThreadFeed.tsx:352-383,386-457` | hover action row (`ot3:ui/qml/T3Panel.qml:1892-1906`) |
| Long press send | Composer | Send options sheet | `t3m:src/features/threads/ThreadComposer.tsx:238-289` | Ctrl-click (`ot3:ui/qml/T3Composer.qml:250-254`) |
| Tap the timeline | Thread | collapses the composer card | `t3m:src/features/threads/ThreadDetailScreen.tsx:1254-1277` | — |
| Pull to refresh | PR list, Archive, Usage, diff, Git sheet; **not** Home or the thread (both are live streams) | refresh | `t3m:src/features/pull-requests/PullRequestsScreen.tsx:248-251`; `t3m:src/features/archive/ArchivedThreadsScreen.tsx:451-453`; `t3m:src/features/review/ReviewSheet.tsx:781-788` | Refresh buttons (`ot3:ui/qml/T3Panel.qml:1411`) |
| Drag a queued message | Queue sheet | reorder | `t3m:src/features/threads/ThreadQueueControl.tsx:254-358` | drag grip (`ot3:ui/qml/T3Queue.qml:53-70`) |
| Hardware keyboard (tablets) | everywhere | Ctrl+K palette, Ctrl+N new thread, Ctrl+F find, Ctrl+Shift+[ / ] previous / next thread, Ctrl+1–9 thread n, Enter send, Shift+Enter newline, Esc | iOS map `t3m:modules/t3-native-controls/ios/T3KeyboardCommandsModule.swift:39-68` (Android has only two, `t3m:modules/t3-native-controls/android/src/main/java/expo/modules/t3nativecontrols/T3KeyboardCommandsModule.kt:37-46`) | ot3 keymap (`ot3:ui/js/NativeKeybindings.js:251,277,293-303`) |

Compose: `PredictiveBackHandler`/`BackHandler`, `SwipeToDismissBox` with a custom
positional threshold, `combinedClickable(onLongClick)` with
`HapticFeedbackType.LongPress`, `PullToRefreshBox`, `onPreviewKeyEvent` plus
`onProvideKeyboardShortcuts` so the system shortcut helper lists them.

The current sidebar's pull to refresh (`app/src/main/kotlin/dev/avillagran/ot3android/ui/sidebar/SidebarScreen.kt:129`)
goes away; "Refresh" moves to the Home overflow menu, as in ot3's footer.

### 2.4 Tablets and foldables

T3 Mobile's breakpoints, all in `t3m:src/lib/layout.ts`, are kept exactly:

| Layout | Condition | Panes | Source |
|---|---|---|---|
| Compact | width < 720 dp or height < 600 dp | bottom bar, one stack | `t3m:src/lib/layout.ts:9-10,104-108` |
| Split | width ≥ 720 dp and height ≥ 600 dp | 72 dp rail (expands to 240 dp over a scrim, with the New thread button) + list pane clamp(32 % of width, 280, 380) + detail | `t3m:src/lib/layout.ts:126-134`; `.../T3WorkspaceNavigationModule.kt:204-311,377-378` |
| Split + inspector | content width ≥ 960 dp (files inspector needs a viewport ≥ 820 dp) | + inspector clamp(28 %, 260, 320), max 480, main pane keeps ≥ 560 | `t3m:src/lib/layout.ts:15,43-47,194-213,226-247,282-297` |

- List pane: Home or the PR list. Detail: the selected thread or PR, or an empty state
  ("Select a thread", or "No environments connected" with Add environment;
  `t3m:src/features/home/HomeRouteScreen.tsx:158-203`;
  `t3m:src/features/layout/WorkspaceEmptyDetail.tsx:24-46`). Selecting replaces the detail
  without animation (`t3m:src/lib/android-workspace-navigation.ts:106-110`).
- Inspector: the diff's file tree, the changed-files list or the Git sheet's content,
  instead of a sheet (`t3m:src/features/threads/ThreadRouteScreen.tsx:523-546`;
  `t3m:src/features/review/ReviewSheet.tsx:636-642`).
- Settings tab: the rail stays and the list pane hides
  (`t3m:src/features/layout/AdaptiveWorkspaceLayout.tsx:256-258,297-304`).
- With the soft keyboard up in the composer, the list pane steps aside
  (`t3m:src/features/layout/AdaptiveWorkspaceLayout.tsx:288-299`).
- Pane changes animate 260 ms cubic (`t3m:src/features/layout/workspace-pane-animation.ts:8-11`).
- Foldables: with a vertical hinge, the list/detail split snaps to it when the left side
  keeps ≥ 280 dp and the right ≥ 320 dp (`t3m:src/lib/layout.ts:146-162`; posture logic
  `.../T3FoldPostureModule.kt:65-128`).
- Rotation: portrait on phones, free rotation when the smallest width is ≥ 600 dp
  (`t3m:plugins/withAndroidTabletOrientation.cjs:3-24`).

Compose: `NavigationSuiteScaffold` (bar ↔ rail, with the suite type chosen by these
breakpoints rather than Material's window classes) and `ListDetailPaneScaffold` /
`ThreePaneScaffold` with a custom `PaneScaffoldDirective` computed from the table and from
`WindowInfoTracker` folding features.

## 3. ot3's visual system in Compose

### 3.1 Colour

#### 3.1.1 Where ot3's colours come from

ot3 does not define colours. It reads the live Omarchy theme: `Commons/Color.qml` loads
`~/.local/state/omarchy/current/theme/colors.toml` (`omarchy:shell/Commons/Color.qml:17,226,233`)
and `T3Palette` re-reads the same file for its status colours
(`ot3:ui/qml/T3Palette.qml:26,47-54`) and reloads on a theme switch
(`ot3:ui/qml/T3Palette.qml:58-66`). Everything else is the foreground or the accent at
an opacity over the background: secondary text is fg @ 0.57, dividers fg @ 0.09, bubbles
fg @ 0.045 (`ot3:ui/qml/T3Panel.qml:380-387`). Status colours map to theme keys:
working = blue, done = green, waiting = yellow, failed = red, warning = orange, input =
magenta (`ot3:ui/qml/T3Palette.qml:10-16`).

#### 3.1.2 Omarchy themes on Android

A generator converts the 22 Omarchy themes into
`app/src/main/assets/themes/omarchy-themes.json`, with every ot3 role precomputed as an
opaque colour. Keys, fallbacks, schema and the generator contract:
[ui/omarchy-themes.md](ui/omarchy-themes.md). Role list and golden values for
tokyo-night and catppuccin-latte: [ui/tokens.md](ui/tokens.md) section 1.

- **Light or dark:** one rule, Rec.709 luminance of `background` > 0.5 → light, which is
  ot3's sky rule (`ot3:ui/qml/T3Starfield.qml:58`). ot3's two other rules
  (`ot3:ui/js/DocumentTheme.js:4`, `ot3:ui/qml/T3Backend.qml:1076`) and every theme's
  `mode` agree with it on all 22 themes (17 dark, 5 light); the generator fails if a new
  theme breaks that.
- **Material 3 mapping:** [ui/tokens.md](ui/tokens.md) section 2. `primary` is the
  accent, surfaces are the fg-over-bg composites, `surfaceTint` is transparent (no tonal
  elevation), `error` is the theme's red.

#### 3.1.3 Choosing a theme

Revised 2026-10-25 (Andrés): ot3-Android is "our Omarchy T3", so by default it wears
**the Omarchy theme that is active on the machine it is connected to**, the way ot3 on
the desktop follows the live theme (`ot3:ui/qml/T3Palette.qml:47-66`). Picking one of the
22 themes by hand becomes the fallback, not the normal mode.

- **Follow the environment's theme (default).** On an Omarchy host the active theme is
  `~/.local/state/omarchy/current/theme/colors.toml`, its name
  `~/.local/state/omarchy/current/theme.name` (the files ot3 watches,
  `ot3:ui/qml/T3Palette.qml:49,59`). The app reads them through the environment's T3
  server with the lib's typed API: the host's HOME from `filesystem.getMetadata` (which
  expands `~`; T3 does not expand `~` in `readFile`), then
  `projects.readFile(cwd = HOME, relativePath = ".local/state/omarchy/current/theme/colors.toml",
  allowMissing = true)`, and converts the TOML with the lib's `theme.tokens(colors, id)`
  (roles, `appearance`, `statusColors`, `projectPalette`, `projectFills`, `sky`). No TOML
  parsing or colour derivation in Kotlin. Scope `filesystem:read`.
- **Refresh:** on connect, on return to the foreground, when the selected environment
  changes, and by polling `theme.name` every few minutes while the app is visible (Omarchy
  switches themes with `omarchy-theme-set`; T3 sends no event for it). The last
  `colors.toml` per environment is cached so the app paints at once on launch.
- **Which environment wins:** the one whose thread or Home is on screen; in the merged
  Home, the **main** environment (the last used, or the one marked in Settings). Theme
  changes cross-fade with ot3's colour-change motion (3.4).
- **Fallback:** when the environment is not Omarchy (no file, e.g. an Ubuntu host), the
  file cannot be read (missing scope, error) or the user turns following off, the manual
  choice applies: Settings → Appearance, **Theme** Follow system / Dark / Light; **Dark
  theme** and **Light theme**, each one of the Omarchy themes of that appearance (bundled,
  produced by `theme.tokens` at build time), plus **Material You** at the end of each list
  (`t3m:src/lib/mobileTheme.ts:19`). Defaults tokyo-night and catppuccin-latte (one
  constant).
- **Settings → Appearance** shows, first, the switch **Follow the environment's Omarchy
  theme** (on by default) with the detected theme name and environment ("Tokyo Night ·
  omarchy"), or why it is not available ("node is not running Omarchy"); the manual
  options below it apply when following is off or unavailable.
- The theme picker previews each theme as a mini Home row (sky strip, a card row with a
  status label, a slim row).
- Edge-to-edge: transparent system bars; bar icons follow the appearance.
- Material You derives a `ColorScheme` from the wallpaper and maps it back onto ot3 roles
  (accent = `primary`, background = `surface`, foreground = `onSurface`); status
  colours keep the dark or light default theme's values so "Failed" stays red.

**Single source of colour values:** every role comes from the lib's `theme.tokens`, both
for the live theme and for the 22 bundled ones. The lib reproduces how ot3 really draws
(`Qt.rgba(fg, alpha)` over the background, Qt's 8-bit premultiplied blend) and matches
1 692 channels read from the screen; `docs/ui/tokens.md` golden values follow it (it had
19 of 50 values off by one per channel, e.g. tokyo-night `dim` `#6b718a`, `surface`
`#21222e`).

This replaces today's `Ot3Theme(dynamicColor = true)`
(`app/src/main/kotlin/dev/avillagran/ot3android/ui/theme/Theme.kt:16-33`) and the earlier
"Dynamic color by default" of `docs/ARCHITECTURE.md` layer 3.

Compose API: an immutable `Ot3Colors` provided through `LocalOt3Colors`, built from a
`theme.tokens` result (live or bundled); `Ot3Theme { }` wraps `MaterialTheme(colorScheme,
typography, shapes)`. Components read ot3 roles (`Ot3Theme.colors.working`) for anything
ot3-specific and Material roles only inside Material components.

### 3.2 Typography

- **Families:** the system UI font for text (ot3 sets no family on most text, so Qt uses
  the application font, `ot3:app/shell.qml:3`); **Fira Code** (OFL 1.1, bundled,
  Regular and Medium) for code, diffs, paths and command lines, where ot3 uses the
  `monospace` alias (`omarchy:shell/Commons/Style.qml:269,476`;
  `ot3:ui/qml/T3DiffReview.qml:210`).
- **Scale:** ot3's scale × 7/6, so ot3's body 12 px is Android's 14 sp and every ratio
  holds: caption 12, bodySmall 13, body 14, subtitle 15, title 16, heading 19 sp
  (`omarchy:shell/Commons/Style.qml:327-338`). Full table and M3 slot mapping:
  [ui/tokens.md](ui/tokens.md) section 3.
- **Markdown:** line height 1.5, headings 23 / 21 / 19 sp at weight 600 with line height
  1.3, code 12 sp mono on `surface`, block spacing 12 sp (`ot3:ui/js/MarkdownStyle.js:32-45`).
- **Wordmark:** "Code" 21 sp bold after the 32 × 20 dp T3 logo; "OMARCHY" 9 sp, letter
  spacing 2.3 sp, `dim` (`ot3:ui/qml/T3Panel.qml:1146-1149`).
- Text follows the system font scale (`sp`); row heights are minimums, not fixed.

### 3.3 Spacing, sizes, radii, elevation

- **1 ot3 px = 1 dp** for lengths, radii and spacing (ot3 px are logical pixels,
  `omarchy:shell/Commons/Style.qml:211-223`). Spacing tokens xxs 2 … huge 18 dp
  (`omarchy:shell/Commons/Style.qml:234-260`).
- **Touch:** 48 dp minimum. Icon buttons draw a 20 dp icon in a 48 dp target; the slim
  thread row is 48 dp instead of 36 (`ot3:ui/qml/T3ThreadRow.qml:92`).
- **Radii:** thread row 6, menu-like controls 7, card 10, user bubble 16, composer 17,
  project chip 4 (`ot3:ui/qml/T3ThreadRow.qml:178`; `ot3:ui/qml/T3Approvals.qml:18`;
  `ot3:ui/qml/T3Panel.qml:1704`; `ot3:ui/qml/T3Composer.qml:264`;
  `ot3:ui/qml/T3ProjectIcon.qml:13-14`). Sheets, which ot3 does not have, take 16.
- **Borders:** 1 dp everywhere.
- **Elevation:** none. ot3 draws no shadows in normal use (its only one is on a row flying
  between sections, `ot3:ui/qml/T3RowFlight.qml:59-61`); surfaces separate by tint and
  hairline. Sheets and dialogs sit on a black @ 0.5 scrim.
- **Phone gutters:** 14 dp (xxxl) on the timeline and lists; tablets use ot3's 26 dp
  timeline margin and 736 dp content lane (`ot3:ui/qml/T3Panel.qml:177-178,1583`).

Every component size: [ui/tokens.md](ui/tokens.md) section 4.

### 3.4 Motion and reduced motion

- Durations and curves are ot3's: colour changes 130 ms, switch 120 ms OutCubic, row
  arrival 360 ms with an OutBack drift, departure 300 ms, shelf moves 520 ms, status icon
  pop 460 ms ([ui/tokens.md](ui/tokens.md) section 5). Navigation uses Android's
  predictive-back transitions; panes use T3 Mobile's 260 ms.
- ot3 has its own animation preference instead of an OS hook: **Animated starfield**
  Auto / Full / Light / Off (`ot3:ui/qml/T3Preferences.qml:260`), with Auto dropping to
  Light after 90 slow ticks (`ot3:ui/qml/T3Starfield.qml:113-115`). Android keeps the
  preference and adds the platform rules: animator duration scale 0 → Off; battery saver
  → Light. T3 Mobile requires every animation to opt into reduced motion
  (`t3m:src/lib/designTokens.ts:8`).
- Lazy lists use `animateItem` with ot3's specs; under Off they snap.

### 3.5 The sky header

ot3's header is a procedural starfield shader behind the wordmark and the search row,
fading out towards the list (`ot3:ui/qml/T3Panel.qml:1121-1128`;
`ot3:ui/assets/shaders/starfield.frag`). Android draws the same:

| Element | ot3 rule | Source |
|---|---|---|
| Size | header height = search row bottom + 12; brand row 62; fades over its lower part, `fade = 1 − brand.height × 0.85 / height` | `ot3:ui/qml/T3Panel.qml:1121-1127,1135` |
| Base | light sky: surface tinted with fg @ 0.06; dark sky: surface darkened 2.4× | `ot3:ui/qml/T3Panel.qml:1125` |
| Brightness, nebula, meteor rate | 0.9 light / 1.25 dark; nebula 0.55 (accent up to 35 % towards the top-left); meteor rate 0.85 | `ot3:ui/qml/T3Panel.qml:1126`; `ot3:ui/assets/shaders/starfield.frag:104-106` |
| Stars | three parallax layers with one star at most per grid cell: cells 29 / 53 / 97 px, densities 0.42 / 0.38 / 0.32, radii 0.55 / 0.8 / 1.15 (× 0.55–1.25 per star), weights 0.55 / 0.8 / 1, drift factors 0.30 / 0.62 / 1 | `ot3:ui/assets/shaders/starfield.frag:47,88-90` |
| Twinkle | `0.55 + 0.45 · sin(t · (0.6…2.8) + h · 61)` | `ot3:ui/assets/shaders/starfield.frag:48-49` |
| Colour | star ink (foreground) mixed towards the accent by up to 0.6 | `ot3:ui/assets/shaders/starfield.frag:92`; `ot3:ui/qml/T3Starfield.qml:59` |
| Drift | 5 dp/s idle, **42 dp/s while any thread works**, eased over 1 600 ms InOutSine, direction (1, 0.18) | `ot3:ui/qml/T3Starfield.qml:28-33`; `ot3:ui/assets/shaders/starfield.frag:84` |
| Comets | 4 channels with periods 2.1 + 1.37·n s; a channel fires when its hash < rate × scale × energy, and energy is 0 when idle, so **comets only appear while something works**; 0.75–1.25 s each; right to left and downwards; tail clamp(0.22 × diagonal, 60, 240) dp; colour meteor ink mixed 25 % with the accent | `ot3:ui/assets/shaders/starfield.frag:56-78,113` |
| Light themes | star ink = foreground; meteor ink = foreground on light skies and white on dark ones; ink gain 1.7 on light skies instead of 1 | `ot3:ui/qml/T3Starfield.qml:59-63`; `ot3:ui/assets/shaders/starfield.frag:98-100` |

Implementation: on API 33+, an AGSL `RuntimeShader` port of `starfield.frag` (the shader
is the same author's) drawn with `drawWithCache` + `ShaderBrush`, uniforms updated from a
`withFrameNanos` loop at ot3's ambient frame interval (33 ms; `ot3:ui/qml/T3Ticker.qml:22-26`).
Below API 33 (minSdk is 24, `app/build.gradle.kts:15`), a `Canvas` version of the same
grid hash draws the stars and comets without the nebula. The loop runs only while the
header is visible and the app is resumed; Off draws a static frame; Light keeps comets
only in the header, as ot3's Light mode does (`ot3:ui/qml/T3Starfield.qml:47-48`).

The wordmark row sits on the sky: T3 logo (`t3-logo`, 32 × 20 dp, fg), "Code", "OMARCHY",
and on the right the count pills and the connection dot (4.1.1).

### 3.6 Strings

All texts are ot3's English source strings with ot3's 20-locale catalog, generated into
`strings_ot3.xml` per locale; the few mobile-only states have their own reviewed list.
Rules, generator and the list: [ui/strings.md](ui/strings.md). The layout is RTL-safe
(`start`/`end` everywhere) because the catalog includes Arabic.

### 3.7 Status, badges and chips

- **Row status** comes from ot3's ten-state detail: Approval, Input, Plan ready, Waiting,
  Goal, Working, Limited, Failed, Woke, Done (`ot3:ui/qml/T3Panel.qml:745`;
  `ot3:ui/js/SidebarModel.js:5-17`). Each shows a 16 dp icon and a caption label in its
  colour: `shield-question` approval, `message-circle-question` input and plan,
  `circle-dot-dashed` waiting, `circle-dashed` working and goal (turns at 112.5°/s when
  animations are on), `circle-alert` limited and failed, `circle-check` done
  (`ot3:ui/qml/T3ThreadRow.qml:271,277`); colour by `ot3:ui/qml/T3Palette.qml:30-34`.
  "Done" marks an unseen completion; a seen one shows its age instead
  (`ot3:docs/NATIVE_VISUAL_AUDIT.md:64`).
- **Count pills** ("work / ready / input"): ot3's bar badges, working (blue) and done
  (green) stacked, waiting (yellow) beside them, 12 dp high, radius 3, digits 9 sp bold in
  `background`, capped at "99+" (`ot3:ui/qml/T3StatusBadges.qml:8-26`). They sit in the
  Home brand row and never count settled threads
  (`ot3:docs/NATIVE_VISUAL_AUDIT.md:95-97`). Data: lib `sidebar.view` counts (U2).
- **Project chip:** 16 dp, radius 4, the project's slot colour @ 0.14 with the monogram
  in bold at half the chip height (`ot3:ui/qml/T3ProjectIcon.qml:9-19`). Slot and
  monogram ("omarchy-t3-code" → "OC") are derived by ot3 (`ot3:ui/qml/T3Palette.qml:36-46`;
  `ot3:src/lib.rs:729-755`); the lib must expose both (U21).
- **PR badge:** state-coloured PR icon (open done, merged magenta, closed failed) and the
  number (`ot3:ui/qml/T3PullRequestBadge.qml:20-31`); tap opens the PR or, with several,
  the Thread pull requests sheet (`t3m:src/features/threads/use-open-thread-pull-requests.ts:13-33`).
  Data: `pull_requests::sidebar_badge` (`lib:src/pull_requests.rs:82`, U19).
- **Not ported in v1:** the row auras (working comet around the row, glints, bursts;
  `ot3:ui/assets/shaders/threadaura.frag`, colours at `ot3:ui/qml/T3ThreadRow.qml:203-205`),
  for list performance on mid-range phones.

### 3.8 Iconography

- ot3 uses **Lucide** (ISC, v0.564.0) at a 24 viewBox with stroke 1.75, plus brand marks
  (`t3-logo`, provider logos) in `ui/assets/icons/` (`ot3:ui/js/IconAssets.js:14-22,64`;
  `ot3:docs/LICENSES.md:43`). Nerd Font glyphs only appear for optional link glyphs.
- Android converts the same SVGs to `VectorDrawable`s at build time (stroke 1.75, round
  caps), tinted with ot3 roles; no Material Symbols. The Lucide and T3 notices go into
  Settings → About → Licenses.
- Icons by area, as ot3 names them: Home `search`, `list-filter`, `folder-plus`,
  `square-pen`, `git-pull-request`, `chart-no-axes-column`; rows `pin`, `clock`, `check`,
  `undo-2`, `folder-git-2`, machine icons; composer `paperclip`, `sliders-horizontal`,
  `arrow-up`, `stop-filled`, `list-plus`, `corner-up-right`, `refresh-cw`; changes
  `chevron-down`, `external-link`, `file`, `eye`; PRs `git-pull-request`, `git-merge`,
  `git-pull-request-closed`, `git-pull-request-draft` (`ot3:ui/qml/T3Panel.qml:1140-1186`;
  `ot3:ui/qml/T3ThreadRow.qml:46`; `ot3:ui/qml/T3Composer.qml:721`;
  `ot3:ui/qml/T3Changes.qml:21-44`; `ot3:ui/qml/T3PullRequestBadge.qml:21`).

### 3.9 Component kit

Each ot3 QML component becomes one composable in `app/.../ui/kit/`, used by every
screen; Material components appear only inside them.

| ot3 | Compose | Notes |
|---|---|---|
| `T3IconButton` | `Ot3IconButton` | 48 dp target, 20 dp icon, pressed fill fg @ 0.07, disabled 0.3, 1 dp accent ring for keyboard focus (`ot3:ui/qml/T3IconButton.qml:20-25`) |
| `T3PickerButton` | `Ot3Button` | primary (accent fill), secondary, outline (fg @ 0.22); radius 7 (`ot3:ui/qml/T3PickerButton.qml:10-14`) |
| `T3TextField` | `Ot3TextField` | fill fg @ 0.04, border fg @ 0.14 → accent on focus, radius 6, body text (`ot3:ui/qml/T3TextField.qml:12-23`) |
| `T3Select` | `Ot3Select` | ot3's field look; opens a sheet list instead of a popup (rule 1) (`ot3:ui/qml/T3Select.qml:9-60`) |
| `T3Switch` | `Ot3Switch` | on accent, off fg @ 0.15, pill 36 × 20 dp (`ot3:ui/qml/T3Switch.qml:11-17`) |
| `T3PreferenceSection` / `T3PreferenceField` | `Ot3SettingsGroup` / `Ot3SettingsRow` | section title with icon, card of rows separated by dividers (`ui/reference/ot3-preferences-source-control.png`) |
| `T3ThreadRow` | `ThreadCardRow`, `ThreadSlimRow` | 4.1.3 |
| shelf header in `T3Panel` | `ShelfHeader` | label, inline rule, chevron (`ot3:ui/qml/T3Panel.qml:1225-1367`) |
| `T3ProjectIcon`, `T3PullRequestBadge`, `T3StatusBadges` | `ProjectChip`, `PrBadge`, `CountPills` | 3.7 |
| `T3Starfield` | `SkyHeader` | 3.5 |
| `WorkIndicator` | `Ot3Spinner` | stretching arc, 1.25 s (`ot3:ui/qml/WorkIndicator.qml:13-53`) |
| `T3ActivityText` | `ActivityText` | live shimmer 72 dp band, 2.2 s (`ot3:ui/qml/T3ActivityText.qml:18-35`) |
| `T3CopyButton` | `CopyButton` | copy → loader → check / alert, resets after 1.8 s (`ot3:ui/qml/T3CopyButton.qml:12-18`) |
| `T3ActivityBlock`, `T3Changes`, `T3ContextDivider`, `T3SubagentCards`, `T3PlanWorkflow`, `T3RunError`, `T3SecretRequestCard`, `T3Approvals`, `T3Questions`, `T3Queue`, `T3Tasks` | timeline and composer-stack cards | 4.3–4.5 |
| `T3ModelPicker`, `T3PermissionPicker`, `T3ModelOptions`, `T3BranchPicker` | sheets | 4.4, 4.7 |
| `ToolTip` (`ui/style/OmarchyT3`) | `Ot3Tooltip` | long-press tooltip on icon buttons, `Color.tooltip` background, bodySmall (`ot3:ui/style/OmarchyT3/ToolTip.qml:31-49`) |

A debug-only **Component catalog** screen shows every kit component in every theme, as
T3 Mobile's dev catalog does (`t3m:src/features/development/ComponentCatalogScreen.tsx:61-280`);
it is also the fixture for the screenshot tests (section 5).

## 4. Screens

Each screen lists what ot3 shows, the structure T3 Mobile uses, the Compose screen, its
states, its interactions and the data it consumes. "Interim" names what the screen does
until a lib request in section 6 lands.

### 4.1 Home (ot3's sidebar)

- **ot3:** sidebar `ot3:ui/qml/T3Panel.qml:1107-1416`, rows `ot3:ui/qml/T3ThreadRow.qml`,
  order and status `ot3:ui/js/SidebarModel.js`, `ot3:docs/SIDEBAR_ORDER.md`. Audit route
  rows `ot3:docs/SCREEN_PARITY_AUDIT.md:85,88`; chat rows 2–9
  (`ot3:docs/audit/CHAT_PARITY.md:10-17`). Captures `ui/reference/ot3-sidebar-and-thread.png`,
  `ot3-thread-context-menu.png`, `ot3-timeline-shelves-composer.png`.
- **T3 Mobile:** `t3m:src/features/home/HomeRouteScreen.tsx:205-313` (header, list, undo
  notice, FAB); list `t3m:src/features/home/HomeScreen.tsx:840-865`; buckets and order
  `t3m:src/features/threads/threadListV2.ts:640-662,706-760`; Android toolbar
  `t3m:src/features/home/ThreadListToolbar.tsx:91-118`; FAB
  `t3m:src/features/home/AndroidHomeFab.android.tsx:38-53`.

```
┌─────────────── sky ───────────────┐
│ ▣ T3 Code      ▮▮ ▮   ● Online  ⋮ │  brand row: wordmark, count pills, connection
│   OMARCHY                          │
│ ⌕ Search threads…            ☰    │  search field + filter
├────────────────────────────────────┤
│ OC omarchy-t3-code      ✓ Done    │  card rows (82 dp): pinned, then active
│ Migrar Omarchy Ares a Plugin       │
│ ⎇ main                  ⑂ 314  ◎  │
│ …                                  │
│ Working (1) ─────────────────── ˅ │  shelves (40 dp headers)
│ Snoozed (1) ─────────────────── ˅ │
│ OC snoozed                    3h  │  slim rows (48 dp)
│ Settled (5) ─────────────────── ˅ │
│                     [✎ New thread] │  extended FAB
├────────────────────────────────────┤
│    Chats        PRs      Settings  │
└────────────────────────────────────┘
```

#### 4.1.1 Header

- Sky (3.5) behind two rows. Brand row: wordmark (3.2), count pills (3.7), connection
  dot and label, overflow.
- **Connection:** ot3's 6 dp health dot, accent when live and failed when stale
  (`ot3:ui/qml/T3Panel.qml:1159`), followed by T3 Mobile's status text after an 800 ms
  delay: "Syncing threads...", "Reconnecting to {environment}", "You are offline",
  the error, or nothing when connected
  (`t3m:src/features/home/WorkspaceConnectionTitle.tsx:20-63`;
  `t3m:src/features/home/workspace-connection-status.ts:19-35`). Tap opens Settings →
  Environments (`t3m:src/features/home/WorkspaceConnectionTitle.tsx:133-166`).
  Data: `ConnectionSupervisor.connection` per environment (existing).
- **Overflow:** Add project (`ot3:ui/qml/T3Panel.qml:1185`), Show provider usage
  (`ot3:ui/qml/T3Panel.qml:1153-1158`, shows the quota strip of 4.10 under the header),
  Group working, snoozed and settled threads (`ot3:ui/qml/T3Panel.qml:1410`), Refresh
  (`ot3:ui/qml/T3Panel.qml:1411`), Command palette.

#### 4.1.2 Search and filters

- Field "Search threads…" with a clear button (`ot3:ui/qml/T3Panel.qml:1162-1187`),
  matching title, project and environment, including settled threads while searching
  (`ot3:ui/js/SidebarModel.js:37,40`). From two characters, a "Messages" section adds the
  server's thread search, debounced 250 ms, as ot3's palette and T3 Mobile's list do
  (`ot3:ui/qml/T3Panel.qml:917-919`; `t3m:src/features/threads/threadListV2.ts:625-638`).
  Data: `orchestration.searchThreads(query, limit)` (typed API, `lib:README.md`
  orchestration table).
- Filter button opens Thread list options: **Status** All, Working, Done, Failed,
  Pending, Settled with counts (`ot3:ui/qml/T3Panel.qml:1079-1089`); **Project** "All
  projects" + projects (`ot3:ui/qml/T3Panel.qml:1096-1102`); **Environment** when more than
  one (`t3m:src/features/home/home-list-filter-menu.ts:32-91`). The icon fills while a
  filter is active (`t3m:src/features/home/ThreadListToolbar.tsx:78-101`).

#### 4.1.3 Thread rows

Card row (82 dp min; active and pinned threads; `ot3:ui/qml/T3ThreadRow.qml:176-324`):

1. Caption line: draft pen if this thread has unsent text (`:222-228`), subagent icon
   (`:229`), project chip, project name (`:230-237`), environment name when more than one
   environment is saved, pin (`:242`), subagent toggle "Show {count} subagents"
   (`:244-256`), then the status icon and label, or the time (`:269-288`), or "Woke"
   (`:260-268`).
2. Title, body Medium, one line, ellipsis (`:307-313`).
3. Caption line: worktree or machine icon and branch or environment (`:317-318`), PR
   badge (`:319`), remote machine icon (`:320`), provider icon (`:321`).

Slim row (48 dp; settled, snoozed and subagent rows): chip, title, PR badge, time or
snooze countdown (`ot3:docs/NATIVE_VISUAL_AUDIT.md:61`).

- **Time:** elapsed while working ("12s", "3m 05s"), countdown while snoozed (in
  `working` colour), otherwise age now / m / h / d (`ot3:ui/qml/T3Panel.qml:541-545,691-696,773`;
  `ot3:ui/qml/T3ThreadRow.qml:288`).
- **Surfaces:** pressed `rowPressed`, draft `rowDraft`, selected `rowSelected` (tablets
  only) (`ot3:ui/qml/T3ThreadRow.qml:179`); 4 dp between cards.
- **Subagents** nest under their parent up to depth 4 when expanded
  (`ot3:ui/js/SidebarModel.js:77-123`).
- **Data:** `ShellThread` rows of each environment's shell snapshot (existing
  `core/.../shell/ShellReducer.kt`), status from `sidebar::sidebar_status` and
  `sidebar::metadata` (woke, snoozed, `canSnooze`; `lib:src/sidebar.rs:19,37`), plus
  ot3's plan / done / woke refinements (`ot3:ui/js/SidebarModel.js:5-17`): all through
  `sidebar.view` (U2). Interim: today's `SidebarModel.kt` statuses (`NeedsYou`,
  `Working`, `Failed`, `core/.../shell/SidebarModel.kt:9-22`) mapped to Approval/Input,
  Working and Failed; no Done, Plan, Woke or Limited until U2. (2026-10-25: U2 closed in
  lib v0.3.3, `SidebarModel.kt` removed; see the note at the top of section 6.)

#### 4.1.4 Order and shelves

- Order: pinned first by `pinOrderKey`; then active rows by inbox time when shelves are
  grouped, or by `activeOrderKey` / `activeAnchorAt` otherwise
  (`ot3:ui/js/SidebarModel.js:61-70`; `ot3:docs/SIDEBAR_ORDER.md:7-15`). T3 Mobile shows
  the same order: pinned, active, Working, Snoozed, Settled
  (`t3m:src/features/threads/threadListV2.ts:706-760`).
- Shelves **Working ({count})** (label in `working`), **Snoozed ({count})**, **Settled
  ({count})** (labels `dim`), each a collapsible header with an inline rule and chevron
  (`ot3:ui/qml/T3Panel.qml:1225-1367`). ot3's user rules hold: collapsed shelves stay
  collapsed across search and restarts, empty shelves are hidden
  (`ot3:docs/NATIVE_VISUAL_AUDIT.md:95-97`). One `LazyColumn` holds everything (T3
  Mobile's topology); ot3's per-shelf scrollers are a desktop choice
  (`ot3:docs/NATIVE_VISUAL_AUDIT.md:80`). Settled pages with "Show more ({count}
  settled hidden)" (`t3m:src/features/threads/thread-list-v2-items.tsx:283-303`).
- Working shelf only exists when grouping is on (`ot3:ui/qml/T3Panel.qml:556`).
- Data: `sidebar.view` sections (U2). Interim: `SidebarModel.kt` active/settled split
  (`core/.../shell/SidebarModel.kt:45-64`), extended to several environments.
  (2026-10-25: superseded by `sidebar.view`; see the note at the top of section 6.)

#### 4.1.5 Actions

- **Swipe** (2.3): Settle / Move to active / Wake / Archive; Snooze.
- **Thread actions sheet** (long press), ot3's menu in ot3's order
  (`ot3:ui/qml/T3ThreadRow.qml:378-410`): New thread on {branch}; Pin thread / Unpin
  thread; Move up / Move down; Mark settled / Move to active; Snooze thread / Wake thread
  now; Rename thread; Regenerate title; Mark unread; Filter by {project} / All projects;
  Auto-settle behavior ▸ Enabled / Disabled; Copy ▸ Path, Branch, Thread ID; Project
  settings; Archive thread; Delete. Items are gated by server capabilities
  (`threadPinning`, `threadSettlement`, `threadSnooze`, `threadTitleRegeneration`,
  `threadAutoSettleOptOut`) and write access, as in ot3. The sheet header shows ot3's
  hover card content: title, project, environment, branch, model, provider history and
  "Usage limit reached" / "Error occurred" (`ot3:ui/qml/T3ThreadRow.qml:325-342`).
- **Confirmations:** Rename, Archive and Delete use ot3's dialog; Delete reads "This
  permanently clears conversation history for this thread." / "This action cannot be
  undone." with Retry after a failure (`ot3:ui/qml/T3ThreadRow.qml:411-427`).
- **Project actions sheet:** Rename, Group into…, Copy Path, Project settings, Remove,
  with ot3's dialog fields (default model, default environment, Auto pull, "Delete all
  project threads"; `ot3:ui/qml/T3ProjectMenu.qml:62-126`).
- **FAB:** extended "New thread" with ot3's `square-pen` icon, collapsing on scroll
  (`t3m:src/features/home/AndroidHomeFab.android.tsx:38-53`); absent in the split list
  pane, where the rail carries it.
- **Data:** commands from `sidebar::command` and `sidebar::thread_action`
  (`lib:src/sidebar.rs:63,71`) sent with `orchestration.dispatchCommand` through the
  outbox; existing request item 4 in `LIB_REQUESTS.md`. Interim: the sheet shows only
  Copy and Filter (no mutation) until the builders are reachable.

#### 4.1.6 States

| State | ot3 / T3 Mobile text | Source |
|---|---|---|
| No environments | "No environments connected" + Add environment | `t3m:src/features/home/HomeScreen.tsx:145-210,742-757` |
| Connecting / loading | "Connecting to environment" / "Loading threads..." with the sky still drawn | `t3m:src/features/home/HomeScreen.tsx:145-210` |
| Empty | "No threads here" | `ot3:ui/qml/T3Panel.qml:1218-1223` |
| Stale / offline | "T3 is offline", rows dimmed, cached list kept | `ot3:ui/qml/T3Panel.qml:1218-1223` |
| Unauthorized | edit-token action, as today | `app/src/main/kotlin/dev/avillagran/ot3android/ui/sidebar/SidebarScreen.kt:113-118` |
| No search results | "No results" | `t3m:src/features/home/HomeScreen.tsx:791-811` |

### 4.2 Environments and adding one

- **ot3:** `ot3:ui/qml/T3Connections.qml:46-147` (environment rows with state, enabled
  switch, Rename, Reconnect, routes, Remove; Add connection by pairing link);
  `ot3:ui/qml/T3ConnectionAccess.qml`. Audit `ot3:docs/SCREEN_PARITY_AUDIT.md:91,95`.
- **T3 Mobile:** `t3m:src/features/settings/SettingsEnvironmentsRouteScreen.tsx:97-183`,
  rows `t3m:src/features/connection/ConnectionEnvironmentRow.tsx:104-263`, Add
  environment `t3m:src/features/connection/ConnectionsNewRouteScreen.tsx:190-395`.
- **Compose:** the current `EnvironmentsScreen` and `ConnectScreen` restyled and
  reorganised:
  - Environments list: status dot, name, status text ("Connected", "Reconnecting",
    "Connecting", "Offline", "Connection failed"; `t3m:src/features/connection/connectionTone.ts:4-48`),
    "Retrying · attempt {n}", enabled switch; tap expands Label, URL, Save, Reconnect,
    Remove. Remove confirms with ot3's "Forget this connection on this device? Remote
    projects and messages are kept." (`ot3:ui/qml/T3Connections.qml:138-147`).
  - Add environment, top to bottom: QR card "Tap to scan" (CameraX + a barcode decoder;
    the decoded text goes to the lib parser); "Pairing link" field with ot3's placeholder
    `https://host:3773` and a masked "Pairing code" (`ot3:ui/qml/T3Connections.qml:130-133`);
    an "Advanced: URL and token" section with today's fields. SSH is desktop-only.
- **States:** camera permission denied, invalid code, environment mismatch, unreachable,
  unauthorized, each with ot3's or T3 Mobile's wording
  (`t3m:src/features/connection/ConnectionsNewRouteScreen.tsx:118-125,146`).
- **Data:** `fetchDescriptor`, `pairBearer` (ABI operations; Kotlin wrappers are request
  item 8), `connection_catalog::pairing_target` for links and QR payloads
  (`lib:src/connection_catalog.rs:5`, U17). Interim: URL + token only, as today.

### 4.3 Thread

- **ot3:** header `ot3:ui/qml/T3Panel.qml:1471-1548`, timeline `ot3:ui/qml/T3Panel.qml:1559-1954`,
  composer stack `ot3:ui/qml/T3Panel.qml:1956-2036`; rows come from ot3's Rust
  `conversation_timeline` with roles user, assistant, activity, changes, context, plan,
  secret, subagents and error, each with a `rev` fingerprint (`ot3:src/lib.rs:538-617`).
  Audit `ot3:docs/SCREEN_PARITY_AUDIT.md:83,53`; chat rows 17–38
  (`ot3:docs/audit/CHAT_PARITY.md:30-51`).
- **T3 Mobile:** `t3m:src/features/threads/ThreadRouteScreen.tsx:987-1007` (header +
  pane), `t3m:src/features/threads/ThreadDetailScreen.tsx:1292-1667` (feed, then the
  bottom overlay in a `KeyboardStickyView`), feed `t3m:src/features/threads/ThreadFeed.tsx`.

```
┌────────────────────────────────────┐
│ ←  Migrar Omarchy Ares a Plugin  ⎇ ⋮│  title; subtitle "OC omarchy-t3-code · node"
│    OC omarchy-t3-code · node        │
├────────────────────────────────────┤
│ [quota strip]  [subagent bar]       │  optional
│                ┌─────────────────┐ │
│                │ user bubble      │ │  right, ≤ 80 %
│                └─────────────────┘ │
│ assistant Markdown, full width      │
│ › Ran commands (6)                  │  activity block
│ ┌ 22 Changed files +497 −48     ↗ ┐ │  changed-files card
│ └─────────────────────────────────┘ │
│   ─── Context compacted ───         │
│ (◌ Running commands · 1m 05s · 2 ˅)│  working pill
│ [approval / question card]          │
│ ╭ Ask anything, @files/folders… ──╮ │  composer (4.4)
│ ╰ 📎 ◍ GPT-6.1 ▾  🔒  Plan  ⚙   ➤ ╯ │
└────────────────────────────────────┘
```

#### 4.3.1 Top bar and thread chrome

- **Title** (title 16 sp Medium) and subtitle "{project} · {environment}" in `dim` with
  the project chip, T3 Mobile's subtitle (`t3m:src/features/threads/ThreadRouteScreen.tsx:425-430`)
  with ot3's project icon (`ot3:ui/qml/T3Panel.qml:1477-1485`). Back arrow on compact
  layouts only (`t3m:src/features/threads/ThreadHeader.tsx:101-103`).
- **Actions**, split by T3 Mobile's Android rule (one visible action on phones, three at
  600 dp and wider, the rest in overflow; `t3m:src/components/ui/headerMenu.android.ts:45-56`):
  Git & changes (`git-branch`, `ot3:ui/qml/T3Panel.qml:1489`); Find in conversation
  (`:1494`); Thread details (`:1487`); Agents, when the thread has subagents; Merge this
  conversation back (`:1486`), when available; Copy thread reference; Rename; Archive.
  Desktop-only header items (side panel, native terminal, window pin, Open in T3,
  close) are dropped (4.13).
- **Find in conversation:** a search bar under the top bar with "N/M" or "No matches",
  previous / next, close; matches outlined in accent (`ot3:ui/qml/T3Panel.qml:1504-1519,1706-1707`).
- Under the top bar, in order: "Local backend is offline"-style connection notice
  (`ot3:ui/qml/T3Panel.qml:1498-1502`), quota strip when enabled (`:1520-1537`),
  subthread bar "Subagent of {title}" with i/N and previous / next / parent
  (`ot3:ui/qml/T3SubthreadBar.qml:14-27`), thread error banner "The thread stopped with an
  error" / "Usage limit reached" (`ot3:ui/qml/T3ThreadErrorBanner.qml:19-40`).
- **Thread details sheet:** workspace, branch, model, provider history (read-only in v1;
  `lib:src/thread_details.rs:9,16` resolves workspace and editors).

#### 4.3.2 Timeline and messages

- **List:** a `LazyColumn` keyed by row id, `contentType` by role, starting at the
  bottom, following the tail while at the end and keeping position otherwise (T3 Mobile
  `t3m:src/features/threads/ThreadFeed.tsx:2721-2735,2767-2768`; ot3 tail policy
  `ot3:docs/NATIVE_VISUAL_AUDIT.md:45-47`). The current `reverseLayout = true` list
  (`app/src/main/kotlin/dev/avillagran/ot3android/ui/thread/ThreadScreen.kt:214-215`) already
  behaves this way and stays. The timeline runs under the composer stack with a bottom
  inset equal to its height (`ot3:ui/qml/T3Panel.qml:1584-1585`).
- **Load earlier messages** at the top, "Loading…" while busy
  (`ot3:ui/qml/T3Panel.qml:1561-1572`). Data: history paging (request item 7, U18).
- **User message:** right-aligned bubble, `surface` fill, radius 16, padding 12, width
  min(80 % of the lane, content), selectable plain text (`ot3:ui/qml/T3Panel.qml:1665,1701-1737`).
  No author row (ot3 keeps "You" as the accessible name only, `:1742`). Below the
  bubble, right-aligned: date "dd MMM · HH:mm" (`:690`) and copy; long press opens the
  message sheet (2.3). Steer badge "Steer" with `corner-up-right` (`:1689-1695`). Messages
  from automations fold into a 36 dp line "Sent by automation" / "Sent by T3 Code" /
  "Sent by another agent" (`:1673-1723`). Attachments: 220 × 160 thumbnails, radius 8
  (`ot3:ui/qml/T3MessageImage.qml:18-42`), file chips (`ot3:ui/qml/T3Panel.qml:1837-1857`).
- **Assistant message:** no bubble, full lane width, selectable Markdown. Only the last
  reply of a run carries copy, fork and time; earlier replies ("commentary") have an
  8 dp gap and no actions; actions hide while streaming (`ot3:ui/qml/T3Panel.qml:1657-1665,1894`).
  Streaming shows today's cursor (`app/src/main/kotlin/dev/avillagran/ot3android/ui/thread/ThreadScreen.kt:254`).
- **Markdown:** ot3 renders with pulldown-cmark (tables, strikethrough, optional math),
  allows only http, https, file and `t3-thread` links, escapes raw HTML, turns inline-code
  paths into file links, and adds a toolbar to each code block (language, Run in terminal
  when runnable, Copy code) and "Copy as Markdown · Copy as CSV" after each table
  (`ot3:src/lib.rs:119-171`). Link glyphs: GitHub mark, globe, thread, file type
  (`ot3:ui/js/MarkdownLinks.js:5-31`). Android: a display-only CommonMark renderer to
  Compose (`AnnotatedString` blocks inside `SelectionContainer`), ot3's styles (3.2),
  tables scroll horizontally, code blocks scroll horizontally with Copy code; Run in
  terminal and Mermaid / QML previews are out of v1. Link safety and classification come
  from the lib (U10); interim: only http(s) links are tappable.
- **Quote:** selecting text offers "Quote", inserting `> ` lines into the composer
  (`ot3:ui/qml/T3Panel.qml:32-36,1744-1750`).
- **Empty thread:** ot3's "T3" mark in accent @ 0.55, "Your work, within reach."
  (`ot3:ui/qml/T3Panel.qml:1941-1948`).

#### 4.3.3 Activity blocks and thinking

- ot3 merges consecutive activity rows of the same run into one block with a count and up
  to 600 entries (`ot3:src/lib.rs:607-610`). Labels pair live and done forms ("Running
  commands" / "Ran commands", "Reading files" / "Read files", "Editing files" / "Edited
  files", "Calling tool" / "Tool", "Context compacted") and format as "{activity} ·
  {title}", "{activity} ({count})", "{activity} · {count} failed"
  (`ot3:ui/qml/T3Backend.qml:1900-1910`). T3 Mobile's "Worked for {duration}" run fold
  (`t3m:src/lib/threadActivity.ts:1209-1219`) is not adopted (rule 4).
- **Block** (`ot3:ui/qml/T3ActivityBlock.qml:22-50`): header row (chevron when more than
  one entry, spinner while active, red alert when failed, `ActivityText` with shimmer while
  live); expanded list of 40 dp entry rows (ot3's 28, rule 3) capped at 220 dp, each with a
  status icon; a single failed tool shows its input and failure detail in mono
  (`ot3:ui/qml/T3ActivityBlock.qml:12-36`).
- **Thinking:** reasoning is an activity row "Thinking" (`ot3:ui/qml/T3Backend.qml:1901-1902`;
  category `think`, `lib:src/activity.rs:38-60`), not an inline fold; its text opens in
  the inspector as plain text (`ot3:docs/TOOL_INSPECTOR.md:75`).
- **Inspector sheet:** Details / Metadata / Raw item tabs, status and exit code, "Loading
  output…", "Output is no longer available.", "No output.", "Follow live output" for
  commands, sections with copy (`ot3:ui/qml/T3ToolInspector.qml:91-165`). Data:
  `tool_inspector::scope`, `request`, `parse` (`lib:src/tool_inspector.rs:61,89,236`,
  U25) then `orchestration.getTurnItem`; live output from
  `orchestration.subscribeCommandOutput` (typed stream).
- **Data:** rows from the timeline view (U0), labels from `activity::describe`
  (`lib:src/activity.rs:38`, U3). Interim: activity rows hidden; the working pill still
  shows that the agent is working.

#### 4.3.4 Changed-files card

- ot3 card: radius 10, `changesCard` fill, padding 11; header chevron, "{n} Changed
  files", "+a" in `done`, "−d" in `failed`, `external-link` "Open diff"; file rows with a
  kind-tinted file icon, middle-elided path, +/− and an `eye` preview
  (`ot3:ui/qml/T3Changes.qml:14-48`). Capture `ui/reference/ot3-desktop-overview.png`.
- Phone: rows 40 dp, first 5 shown with "Show all {n}" (ot3 caps at 240 px). Tap a row →
  `ThreadChanges` at that file; Open diff → first file (`ot3:ui/qml/T3Panel.qml:1885-1888`).
  T3 Mobile's compact "+adds −dels ›" stat (`t3m:src/features/threads/ThreadFeed.tsx:1506-1540`)
  is not used: ot3's card wins on appearance.
- Data: `changes::summaries` (`lib:src/changes.rs:4`, U4): per run `fromTurnCount`,
  `toTurnCount`, files with kind and counts. Interim: card hidden.

#### 4.3.5 Dividers, errors and cards

- **Context divider:** "Context compacted", "Compacting context", "Context handoff",
  "Context handoff pending", centred between hairlines at 18 %; handoffs show provider
  icon + model → provider icon + model (`ot3:ui/qml/T3ContextDivider.qml:10-32`). Data:
  `lifecycle::describe` (`lib:src/lifecycle.rs:4`, through U0).
- **Provider error card:** "Provider error", "Usage limit reached", "Retrying provider
  ({n}/{max})", "Provider recovered ({n} retries)", copy, "Retrying in {s}s.", cause list
  (`ot3:ui/qml/T3RunError.qml:13-73`). Data: `failure_context::present`
  (`lib:src/failure_context.rs:95`, through U0).
- **HTML render / MCP app items:** a card with the title and "Open in browser"; the inline
  renderer is out of v1 (4.13). Data: `documents::html_render`, `documents::mcp_app`
  (`lib:src/documents.rs:35,52`).

#### 4.3.6 Subagents

- Cards: "{n} subagents" group header with "2 working · 1 done", auto-expanded when ≤ 3
  or any is live; rows with provider icon and 7 dp status dot, title, "status · elapsed",
  two lines of progress, stop, chevron to the child thread
  (`ot3:ui/qml/T3SubagentCards.qml:17-43`; `ot3:ui/qml/T3SubagentRow.qml:39-57`;
  labels `ot3:ui/js/Subagents.js:8-11`). Tap pushes the child thread, as T3 Mobile does
  (`t3m:src/features/threads/thread-subagent-group.tsx:94-183`).
- Inside a child thread, the subthread bar (4.3.1); for provider-managed subagents the
  composer is replaced by ot3's notice (`ot3:ui/qml/T3Panel.qml:2008-2023`;
  `t3m:src/features/threads/ThreadDetailScreen.tsx:1556-1594`).
- Data: U0 rows and `subagents.buckets` (U13). Interim: hidden.

#### 4.3.7 Plans

- **Plan card:** "Plan" + title + "Copy plan", Markdown capped at 240 dp with "Show
  more" / "Show less" past 900 characters or 20 lines (`ot3:ui/qml/T3PlanWorkflow.qml:30-76`).
  Capture `ui/reference/ot3-plan-card.png`.
- **Ready-plan banner** above the composer: "Implement plan" (primary; becomes "Send plan
  feedback" when the draft has text) and "Implement in new thread"
  (`ot3:ui/qml/T3PlanWorkflow.qml:80-82`; `ot3:ui/qml/T3Panel.qml:1980-1988`).
- Data: `plan_workflow::view` with a Markdown renderer parameter, `follow_up`
  (`lib:src/plan_workflow.rs:325,191`, U15). Interim: the plan shows as an assistant
  message; no banner.

#### 4.3.8 Minimap

ot3 draws one tick per user turn on the left edge, hover previews and click-to-jump
(`ot3:ui/qml/T3TimelineMinimap.qml:19-159`; `ot3:ui/js/TimelineModel.js:3-14`). On phones
the left edge belongs to the system back gesture (rule 1), so **phones have no
minimap**. Tablets draw it in the timeline's left gutter, inside the 26 dp margin: tap
jumps, long press shows the preview card (user text in bold + two reply lines). Data:
`timeline.minimap` (U12).

#### 4.3.9 States

| State | Text | Source |
|---|---|---|
| Opening | "Opening thread…" | `t3m:src/features/threads/ThreadRouteScreen.tsx:139-148` |
| Loading conversation | spinner + "Loading conversation…" | `ot3:ui/qml/T3Panel.qml:1949-1954` |
| Unavailable | "Thread unavailable" + Reconnect environment / Manage environments | `t3m:src/features/threads/ThreadRouteScreen.tsx:163-188,237-256` |
| Load error | "Could not load conversation" + Retry | `t3m:src/features/threads/threadContentPresentation.ts:18-46` |
| Offline with cache | "Offline · showing saved messages", composer disabled | mobile-only ([ui/strings.md](ui/strings.md)) |
| Reconnecting | in the working pill: "Reconnecting to {environment}…" | `t3m:src/features/threads/floating-working-status.ts:43-80` |
| Stale snapshot | today's stale notice | `app/src/main/kotlin/dev/avillagran/ot3android/ui/thread/ThreadScreen.kt:276` |
| Render failure | "The conversation couldn't be displayed" | `t3m:src/features/threads/ThreadDetailScreen.tsx:1308-1317` |

### 4.4 Composer

- **ot3:** `ot3:ui/qml/T3Composer.qml` (frame `:263-271`, editor `:397-430`, completions
  `:138-193`, toolbar `:444-745`), model picker `ot3:ui/qml/T3ModelPicker.qml`, queue
  `ot3:ui/qml/T3Queue.qml`. Chat rows 39–49 (`ot3:docs/audit/CHAT_PARITY.md:57-67`).
  Captures `ui/reference/ot3-sidebar-and-thread.png`, `ot3-model-picker.png`.
- **T3 Mobile:** `t3m:src/features/threads/ThreadComposer.tsx` (collapsed row `:893-1126`,
  card `:1129-1218`), bottom overlay order `t3m:src/features/threads/ThreadDetailScreen.tsx:1410-1667`,
  native Android editor (an `EditText` with span chips;
  `t3m:src/native/T3ComposerEditor.native.tsx:42,110`).

#### 4.4.1 Bottom stack and frame

The bottom stack, top to bottom, merges T3 Mobile's overlay order with ot3's composer
stack (`t3m:src/features/threads/ThreadDetailScreen.tsx:1410-1667`;
`ot3:ui/qml/T3Panel.qml:1956-2036`):

1. **Working pill** (T3 Mobile's floating control): ot3 spinner in `working` + the live
   activity label (up to three live activities joined with " · ", else "Thinking";
   `ot3:ui/qml/T3Backend.qml:1899`) + elapsed time; a "{n} queued" chip that opens the
   Queue sheet; a chevron-down "Jump to latest message" when not at the end
   (`ot3:ui/qml/T3Panel.qml:1934-1940`). Status priority (connection, syncing,
   compacting, working, background work, snoozed or woke) follows
   `t3m:src/features/threads/ThreadDetailScreen.tsx:652-760`; hidden while an approval or
   question is pending. Data: `live_activity` (`ot3:src/lib.rs:532-536`, part of U0).
   Interim: "Working" from today's `ThreadView.working` (`app/src/main/kotlin/dev/avillagran/ot3android/ui/thread/ThreadScreen.kt:266`).
2. Rewind banner (4.5.4), ready-plan banner (4.3.7), queue strip (4.4.6), tasks strip
   ("current task, counts, segmented progress", `ot3:ui/qml/T3Tasks.qml:9-67`).
3. Approval card, then question card (4.5).
4. The composer, **hidden while a question is pending** (both references:
   `ot3:ui/qml/T3Panel.qml:2019-2023`; `t3m:src/features/threads/ThreadDetailScreen.tsx:789-790`).

Frame (ot3): `background` fill, radius 17, 1 dp border fg @ 0.09, accent @ 0.45 when
focused, 12 dp margins (`ot3:ui/qml/T3Composer.qml:263-271`). Layout (T3 Mobile):

- **Collapsed** (not focused, draft fits one line): one row: attach, editor, model chip
  (shrinks to the provider icon once there is text), send
  (`t3m:src/features/threads/ThreadComposer.tsx:795,893-1126`).
- **Card** (focused or wrapping): attachments strip, editor (1–6 lines), error line, then
  the toolbar: attach · model chip ▾ · permission · Plan/Build · options — spacer — draft
  status · send (`t3m:src/features/threads/ThreadComposer.tsx:1129-1218`; ot3 toolbar
  `ot3:ui/qml/T3Composer.qml:444-745`). Tapping the timeline collapses it.
- **Expanded editor:** a maximize icon at three or more lines opens a full-screen editor
  (ot3 `ot3:ui/qml/T3Composer.qml:385-389,701-709`).
- **Placeholder:** "Ask anything, @files/folders, $skills or /commands…"
  (`ot3:ui/qml/T3Composer.qml:407`); while an approval is pending, "Send a message to
  decline and redirect the agent" (mobile-only, from
  `t3m:src/features/threads/ThreadDetailScreen.tsx:1601-1603`).
- **Keys:** the soft keyboard's Enter inserts a newline; a hardware Enter sends and
  Shift+Enter inserts a newline, ot3's compact behaviour (`ot3:ui/qml/T3Composer.qml:218-237`).
  Up at the first line recalls earlier prompts; list continuation on Enter
  (`ot3:ui/qml/T3Composer.qml:194-217`); recall text from `prompt_recall::recall`
  (`lib:src/prompt_recall.rs:151`).
- **Notices inside the frame:** "Editing queued message" + "Cancel edit", worktree setup
  progress, branch mismatch with "Restore branch" / "Use {branch}"
  (`ot3:ui/qml/T3Composer.qml:280-328`); upload or send errors as a `failed` line, for
  example "No confirmation from T3. Retry the same submission; it will not duplicate."
  (`ot3:ui/qml/T3Composer.qml:433-443`), which matches today's outbox semantics.
- Data: drafts per thread (Room, existing), `ThreadComposer` (existing,
  `core/.../thread/ThreadComposer.kt`).

#### 4.4.2 Completions: @ files and folders, $ skills, / commands

- Triggers: `@` or `$` after whitespace, `/` at the start of a line
  (`ot3:ui/qml/T3Composer.qml:138-148`). T3 Mobile's extra `#` pull-request trigger
  (`t3:packages/shared/src/composerTrigger.ts:74-150`) is not in ot3 and waits.
- A panel anchored above the composer, above the keyboard (T3 Mobile re-hosts its
  popover so it scrolls on Android, `t3m:src/features/threads/ComposerPopoverAnchor.android.tsx:11-15`),
  ot3's look: radius 10, rows with icon, label, description (48 dp), max 230 dp,
  "Searching…" / "No results" (`ot3:ui/qml/T3Composer.qml:165-193`).
- Kinds and icons: path (`folder-plus` / `file`), skill (`folder-code`, inserts `$name`),
  command (`square-pen`), and the built-in `/model` "Model options"
  (`lib:src/composer.rs:14-34`). A chosen path becomes an inline chip
  (`ot3:ui/qml/T3Composer.qml:40-81`); sending exactly `/plan` or `/default` switches mode
  instead (`ot3:ui/qml/T3Backend.qml:981`).
- Data: `projects.searchEntries` (typed API) + `composer::paths`, `composer::catalog`
  (`lib:src/composer.rs:12,16`, U8). Interim: no completions.

#### 4.4.3 Model picker

- Chip: provider icon, model short name (provider prefix stripped), chevron, radius 7
  (`ot3:ui/qml/T3ModelPicker.qml:22,41-52`).
- Sheet (0.92), ot3's popup laid out for a phone: a 56 dp provider rail with
  **Favorites** first (each provider with its icon, an accent bar when selected, a
  two-letter badge when two share a driver, "Unavailable" when disabled); search field
  "Search models…" with fuzzy matching; rows with name, "New" badge, provider subline,
  check for the current model, and a star toggle ("Add to favorites" / "Remove from
  favorites") tinted `waiting`; "No favorites yet" / "No results"; "Legacy models"
  expander (`ot3:ui/qml/T3ModelPicker.qml:28-150`). Capture `ui/reference/ot3-model-picker.png`.
- Data: `server.getConfig` + `models::catalog`, `models::preferences`,
  `models::favorite_payload` (`lib:src/models.rs:12,21,37`, U7); the selection is sent
  with the next message. Interim: read-only label from the thread's `modelSelection`
  (`app/src/main/kotlin/dev/avillagran/ot3android/ui/thread/ThreadViewModel.kt:48`).

#### 4.4.4 Options, permissions and Plan / Build

- **Options** (`sliders-horizontal`): a sheet of the model's descriptors, label + select
  or switch (`ot3:ui/qml/T3ModelOptions.qml:13-24`), including reasoning effort; T3
  Mobile puts the same "Options" under its thread settings
  (`t3m:src/features/threads/ThreadSettingsSheet.tsx:838-937`).
- **Permission:** icon button (lock) opening a sheet of Supervised, Auto-accept edits,
  Auto, Full access with descriptions and a check on the current one, filtered by
  provider support (`ot3:ui/qml/T3PermissionPicker.qml:11-66`). Data:
  `models::runtime_mode_command` (`lib:src/models.rs:7`).
- **Plan / Build:** ot3's text toggle "Plan" / "Build" (`ot3:ui/qml/T3Composer.qml:668`),
  shown when the provider supports interaction modes (`ot3:ui/qml/T3Backend.qml:70-79`).
  T3 Mobile has no button, only `/plan` and `/default`
  (`t3m:src/features/threads/use-composer-command-menu.ts:86-99`); ot3 wins (rule 2), and
  both slash commands still work. Data: `plan_workflow::mode` (`lib:src/plan_workflow.rs:21`).
- **Context meter:** ot3's ring (`ot3:ui/qml/T3ContextMeter.qml:9-33`) moves into the
  options sheet header on phones and stays in the toolbar on tablets.

#### 4.4.5 Send, queue, steer, stop

One 40 dp accent circle (failed colour when stopping; `ot3:ui/qml/T3Composer.qml:729-731`),
icons by state (`ot3:ui/qml/T3Composer.qml:721`; T3 states
`t3m:src/features/threads/composerSendPresentation.ts:22-56`):

| State | Icon | Action |
|---|---|---|
| Idle, draft | `arrow-up` | send |
| Run active, empty draft | `stop-filled` | interrupt (today's `run.interrupt`, `app/src/main/kotlin/dev/avillagran/ot3android/ui/thread/ThreadViewModel.kt:162`) |
| Run active, draft | `list-plus` or `corner-up-right` | queue or steer, per the follow-up setting; **always steer while an approval is pending** (`ot3:ui/qml/T3Backend.qml:967-972`; `t3m:src/features/threads/ThreadDetailScreen.tsx:1628-1633`) |
| Editing a queued message | `check` | save the edit |
| Outcome unknown | `refresh-cw` | retry the same command id (today's outbox) |

Long press opens Send options: Queue, Steer the active run, Send and start new thread
(ot3's labels, `ot3:ui/qml/T3Queue.qml:79`, `ot3:ui/qml/T3Composer.qml:710-716`; T3 Mobile's
long-press menu, `t3m:src/features/threads/ThreadComposer.tsx:238-289`). This replaces
ot3's Ctrl modifier. Data: delivery mode and command from the composer builder (request
item 4) through today's `MessageSender`.

#### 4.4.6 Queue

- Strip (ot3 card, radius 10): `list-ordered` icon, "Queued" or "Queued · Paused", count,
  "Resume", chevron; expanded state persists (`ot3:ui/qml/T3Queue.qml:9-34`).
- Tapping it opens the Queue sheet (T3 Mobile, `t3m:src/Stack.tsx:865-873`): rows with
  drag handle, spinner while pending, paperclip, text or "Queued message · content
  unavailable", and actions Edit in composer, Steer the active run, Remove from queue
  (`ot3:ui/qml/T3Queue.qml:37-80`); swipe to remove (T3 Mobile).
- Data: `queue::derive` (items, `held`, `canSteer`, `canReorder`, hidden message ids;
  `lib:src/queue.rs:5`) and `queue::command` (`:31`), U5. Interim: hidden.

#### 4.4.7 Attachments

- Attach opens a sheet: Photos (Android photo picker), Files (Storage Access Framework),
  Camera. T3 Mobile has photos and files only
  (`t3m:src/features/threads/ComposerAttachmentButton.tsx:8-59`); ot3 has files and a
  screenshot button (`ot3:ui/qml/T3Composer.qml:446-458`); a phone camera is the natural
  third.
- Composer shows 56 dp image thumbnails with a round remove, file chips with spinner,
  error, preview and remove (`ot3:ui/qml/T3Composer.qml:343-376`). Paste of images and
  long text works as in T3 Mobile (`t3m:src/features/threads/ThreadComposer.tsx:961-1025`).
- Data: the lib's `upload` by file descriptor (`docs/ARCHITECTURE.md:8-10`); references
  via `composer::attachment_reference` (`lib:src/composer.rs:49`).

#### 4.4.8 Drafts and dictation

- Draft status icon: spinner while saving, check when saved, alert on failure, with
  ot3's tooltips; 1 s quiet period, flush on blur (`ot3:ui/qml/T3Composer.qml:108-109,421,673-700`).
  Drafts already persist per thread in Room.
- Dictation is out of v1: T3 Mobile has no Android transcriber
  (`t3m:src/native/voiceTranscription.ts:3-5`), and ot3's is a separate desktop
  feature (`ot3:docs/DICTATION.md`). The toolbar keeps a slot for a future mic.

### 4.5 Approvals, questions, secrets and rewind

Both references put pending requests **above the composer, not in the timeline**, and
show only the first pending item (`ot3:ui/qml/T3Panel.qml:2000-2007`;
`ot3:ui/qml/T3Backend.qml:352,372`; `t3m:src/features/threads/ThreadDetailScreen.tsx:1513-1553`;
`t3m:src/state/use-selected-thread-requests.ts:108-111`). They replace today's one-line
banner (`app/src/main/kotlin/dev/avillagran/ot3android/ui/thread/ThreadScreen.kt:316`).

#### 4.5.1 Approval card

- ot3 card (radius 10, `card` / `cardBorder`): `circle-alert` in accent; kind label
  "Command approval", "File read approval", "File change approval", "App permission
  approval", "App access approval" or "Approval required"; app name; "1/N"
  (`ot3:ui/qml/T3Approvals.qml:15-27`).
- Detail in mono, max 100 dp, scrolling; "Provider process is gone — interrupt or restart
  the run to respond." when it cannot be answered (`ot3:ui/qml/T3Approvals.qml:29-38`).
- Buttons: the server's accept (accent outline) and decline options, warnings prefixed
  "⚠"; other options under "More approval options" (sheet); "Retry same action";
  status "Sending…" / "Answered" (`ot3:ui/qml/T3Approvals.qml:39-72`). T3 Mobile's
  defaults "Allow once / Allow session / Decline"
  (`t3m:src/features/threads/PendingApprovalCard.tsx:22-72`) are used only when the
  server sends no options, with ot3's wording where ot3 has it.
- Data: `approvals::pending` (rows with kind, capability, options; `lib:src/approvals.rs:11`)
  and `approvals::command` (`:61`) via `Command::approve`; U6 and request item 4.

#### 4.5.2 Question card

- ot3 card: collapse chevron, header (default "Question"), "i/N", "Dismiss question
  without answering" (`ot3:ui/qml/T3Questions.qml:54-60`). Body (max 300 dp, scrolls):
  question Markdown, "Select one or more options." for multi-select, option rows (label
  bold, description, check) (`:61-90`), "Write custom answer" box (`:92-99`). Footer:
  "Previous question", status ("Sending…", "Answered", "Response pending confirmation",
  "This question can no longer be answered."), next, and on the last question a send
  arrow (`:102-108`).
- **Paged, one question at a time** (ot3), not T3 Mobile's single scroll
  (`t3m:src/features/threads/PendingUserInputCard.tsx:224-306`): rule 4. A single-select
  choice advances after 200 ms (`ot3:ui/qml/T3Questions.qml:39,50`); all answers are sent
  together at the end (`:42-49`). Number keys 1–9 work with a hardware keyboard.
- T3 Mobile's collapsed pill ("USER INPUT NEEDED · N questions"; `PendingUserInputCard.tsx:137-178`)
  becomes the card's collapsed state, using ot3's header.
- Data: `questions::pending` (`lib:src/questions.rs:5`, U6) and `questions::command`
  (`:37`) via `Command::answer`.

#### 4.5.3 Secret request

Label, reason, password field "Paste the secret", "Save" / "Decline", footer "Stored
securely, never shown to the agent" (`ot3:ui/qml/T3SecretRequestCard.qml:25-46`). The
field uses `KeyboardType.Password` with no autofill or IME learning. Data:
`secrets.answerRequest` (typed API; the lib redacts the answer in `Debug`).

#### 4.5.4 Edit from here (checkpoint rewind)

From a user message's sheet: "Edit from here?" / "Rewind chat to before this message.
Your prompt and attachments return to the composer." with Cancel, "Revert files too"
(failed colour, needs a worktree) and "Revert and keep changes"
(`ot3:ui/qml/T3CheckpointRevert.qml:173-216`; contract `ot3:docs/CHECKPOINT_REVERT.md:15-23`);
progress and recovery banner above the composer (`ot3:ui/qml/T3Panel.qml:1973-1979`).
Data: `checkpoint_revert::can_preview`, `reference`, `command`, `observation`,
`recovery_message` (`lib:src/checkpoint_revert.rs:208,248,159,7,39`, U24), dispatched
through the outbox.

### 4.6 Changes and diff

- **ot3:** `ot3:ui/qml/T3DiffReview.qml`, `ot3:ui/qml/T3WorkingReview.qml`,
  `ot3:docs/DIFF_REVIEW.md:7-41`. Chat row 27 (`ot3:docs/audit/CHAT_PARITY.md:40`).
  Captures `ui/reference/ot3-changes-review.png`, `ot3-diff-split.png`.
- **T3 Mobile:** `ThreadReview` pushed screen (`t3m:src/Stack.tsx:806-810`),
  `t3m:src/features/review/ReviewSheet.tsx`; header
  `t3m:src/features/review/useReviewHeaderPresentation.android.ts:7-19`.

#### 4.6.1 Layout (phone)

- **Top bar:** title "Changed files", subtitle "{n} · +a −d" (`ot3:ui/qml/T3DiffReview.qml:120-126`).
  Source menu in T3 Mobile's structure, "Changes", "Uncommitted", "Latest turn", "Turn ▸"
  (`t3m:src/features/review/ReviewSheet.tsx:114-149`), with ot3's names for the two
  working-tree sources (`ot3:ui/qml/T3WorkingReview.qml:21-35`) and a base-ref chip for
  them ("Automatic" by default, `ot3:ui/qml/T3BaseRefPicker.qml:22,36`). Overflow: Wrap /
  No wrap, Show / Hide whitespace changes (refetches), Refresh.
- **File bar:** previous file, middle-elided path with +/−, next file; tapping the path
  opens the file list sheet (A / D / R / M letters and counts;
  `ot3:ui/qml/T3DiffReview.qml:127-175`). ot3 itself switches to a file selector and forces
  unified below 650 px (`ot3:ui/qml/T3DiffReview.qml:65-66,178`), which is the phone layout.
- **Body:** unified diff in Fira Code 12 sp: old / new line-number gutter in `dim`,
  additions with a `done` tint at 8 % and "+" in `done`, deletions with a `failed` tint,
  hunk headers in accent, tap a hunk header to fold it
  (`ot3:ui/qml/T3DiffReview.qml:102-103,193-216`). No wrap scrolls horizontally. Notices
  "Patch unavailable for this file.", "Binary file — metadata only"
  (`ot3:ui/qml/T3DiffReview.qml:180-181`), "Patch truncated. Select a file to load its
  changes." and "No changes" (`ot3:ui/qml/T3WorkingReview.qml:21-35`). Pull to refresh.
- **Tablets** (content ≥ 650 dp): Split / Unified toggle, and the file tree moves to the
  inspector pane (2.4).
- Not in v1: syntax highlighting (ot3's native path uses the sandboxed Pierre renderer,
  `ot3:docs/DIFF_REVIEW.md:29-41`; T3 Mobile uses Shiki,
  `t3m:src/features/review/shikiReviewHighlighter.ts:1-11`), line comments (ot3 has none,
  `ot3:docs/DIFF_REVIEW.md:26`), context expansion.

#### 4.6.2 Data

- Thread changes: `orchestration.getTurnDiff(threadId, fromTurnCount, toTurnCount,
  ignoreWhitespace)` and `orchestration.getFullThreadDiff` (typed API), with the ranges
  from `changes::summaries` (`lib:src/changes.rs:10-14`).
- Working tree: `review.getDiffPreview(cwd, baseRef?, ignoreWhitespace?, file?)` and
  `review.getDiffFileContents` (typed API); refs from `vcs.listRefs`.
- Parsing into files, hunks and lines: `diff_review::present` (`lib:src/diff_review.rs:100`,
  U9). Interim: file list without hunks.

#### 4.6.3 Git & changes sheet

ot3's Git & scripts popup (`ot3:ui/qml/T3GitControls.qml`; capture
`ui/reference/ot3-git-controls.png`) in T3 Mobile's `GitOverview` sheet
(`t3m:src/features/threads/git/GitOverviewSheet.tsx:250-312`): path, branch, ahead / behind,
status line, the changed-file rows with +/− and "Review", and "Review changes". Actions
(Commit, Push, Pull, Commit and push, Create PR, Commit, push and create PR) appear only
when the lib's plans are reachable (`git_controls::actions`, `present`, `build_action`;
`lib:src/git_controls.rs:185,203,245`, U22), each behind ot3's confirmation. Scripts run
in a terminal and are out of v1. Data: `vcs.refreshStatus` (typed API).

### 4.7 New thread, project, branch and worktree

- **ot3:** New chat dialog (`ot3:ui/qml/T3SidebarActions.qml:38-94`), worktree
  confirmation (`ot3:ui/qml/T3WorkspaceConfirmation.qml:26-34`), branch picker
  (`ot3:ui/qml/T3BranchPicker.qml`), Add Project wizard (`ot3:ui/qml/T3AddProject.qml`;
  `ot3:docs/PROJECT_PICKER.md`). Audit `ot3:docs/SCREEN_PARITY_AUDIT.md:86`; chat rows 10–15,
  50–51.
- **T3 Mobile:** `NewTask` "Choose project" (`t3m:src/features/threads/NewTaskRouteScreen.tsx:119-143,462-492`)
  → `NewTaskDraft` (`t3m:src/features/threads/NewTaskDraftScreen.tsx:1439-1824`).

#### 4.7.1 Choose project

Top bar "Choose project" with search "Search projects" and Add project; project rows
with chip, name, path and environment (T3 Mobile structure). ot3's search rule applies:
every token must match title, path or environment (`ot3:docs/PROJECT_PICKER.md:23-31`).
T3 Mobile's "Start a task without a project" row is not offered; ot3 always creates a
thread in a project (rule 4).

#### 4.7.2 Draft

- Top bar "New thread" with Cancel (`t3m:src/features/threads/NewTaskDraftScreen.tsx:1822-1824`).
- Context chips (T3 Mobile): environment "on {environment}" (`:1490-1493`), or ot3's
  "Automatic environment" switch when load balancing is on
  (`ot3:ui/qml/T3SidebarActions.qml:61-64`); workspace Current checkout / New worktree
  (`t3m:src/features/threads/new-task-context-presentation.ts:21-26`); branch (4.7.3).
  The first New worktree choice asks ot3's "Enable worktrees by default?" with "Enable
  worktrees" / "Use same folder" (`ot3:ui/qml/T3WorkspaceConfirmation.qml:26-34`).
- Optional "Thread title" (`ot3:ui/qml/T3SidebarActions.qml:65-71`), then the same
  composer as 4.4. Model default order: source thread, project default, a sibling thread,
  the first available model (`ot3:ui/qml/T3SidebarActions.qml:86-87`).
- Send creates the thread, then dispatches the first message, and navigates after
  acceptance, as ot3 does ("metadata-create → durable dispatch",
  `ot3:docs/SCREEN_PARITY_AUDIT.md:27`). Data: `orchestration.launchThread` and
  `message.dispatch` (typed API), with ids persisted in the outbox first.
- "New thread on {branch}" from a row skips 4.7.1 and pre-fills branch and worktree
  (`ot3:ui/qml/T3SidebarActions.qml:21,88-89`; `t3m:src/features/layout/AdaptiveWorkspaceLayout.tsx:488-525`).

#### 4.7.3 Branch picker

Sheet: "Search or create a branch...", "Create branch {name}", "No matching refs.", each
ref tagged default, worktree or remote (`ot3:ui/qml/T3BranchPicker.qml:48-72`); T3 Mobile
adds "Start from remote" (`t3m:src/features/threads/NewTaskContextPickerScreens.tsx:443`).
Data: `vcs.listRefs` (typed API) and `branch_picker::dedupe`, `selection_target`,
`mismatch` (`lib:src/branch_picker.rs:9,41,90`, U20).

#### 4.7.4 Add project

ot3's steps (`ot3:ui/qml/T3AddProject.qml:90-174`): Choose environment → Add project
(search existing projects, sources) → Choose folder (server-side browse) / New project.
v1 offers existing projects, Local folder and New project; Git URL and host sources are
later. States "Loading…", "No projects found", "Environment unavailable", "Setup
required" (`ot3:ui/qml/T3AddProject.qml:105-126`). Data: `filesystem.browse`,
`projects.createNew`, `projects.mutate` (typed API), `projects::folders`
(`lib:src/projects.rs:7`).

### 4.8 Pull requests

- **ot3:** `ot3:ui/qml/T3PullRequests.qml` (list `:48-94`, detail `:99-153`, actions
  `:104-177`). Capture `ui/reference/ot3-pull-requests.png`. Audit
  `ot3:docs/SCREEN_PARITY_AUDIT.md:87`; the ot3 snapshot already has merge, close,
  reopen, ready, draft, comment, review, reviewers and labels
  (`ot3:docs/FEATURE_PARITY.md:38-40`).
- **T3 Mobile:** PR tab and list (`t3m:src/features/pull-requests/PullRequestsScreen.tsx:235-380`),
  detail (`t3m:src/features/pull-requests/PullRequestRouteScreen.tsx:229-722`), bottom
  controls (`t3m:src/features/pull-requests/PullRequestControls.tsx:106-186`).

**List** (PRs tab): top bar "Pull requests" with the environment select
(`ot3:ui/qml/T3PullRequests.qml:54-61`); search "Search pull requests…", 300 ms debounce
(`:64,158`); filter chips Open / Merged / Closed / All and All / Authored / Reviewing
(`:65-66`). Rows (`:79-91`): state-tinted PR icon, title, checks icon (check in `done`,
alert in `failed`); "repo #number · login" in `dim`; Draft / review decision. Newest
first (`:48`). States "Loading…", "No pull requests", "Load more" (`:92-94`), "Connect an
environment" (`t3m:src/features/pull-requests/PullRequestsScreen.tsx:351-380`). Long press:
T3 Mobile's row menu (Open pull request, Request review, Mark ready for review / Convert
to draft, Close / Reopen, Share…, Copy link, Open on {provider};
`t3m:src/features/pull-requests/usePullRequestListActions.ts:150-189`).

**Detail** (push): top bar "#{number}" with the repository as subtitle (T3 Mobile); title
and "head → base +a −d" (`ot3:ui/qml/T3PullRequests.qml:99-100`); tabs **Overview,
Checks, Conversation, Code** (ot3's, `:101`, rather than T3 Mobile's Overview / Guide /
Activity): Markdown body; check lines; comments with "Leave a comment"; the patch with
the diff renderer of 4.6 and "Load more" (`:122-153`). Bottom bar (T3 Mobile): Work on PR
(ot3's open in a thread or worktree, `:112-115`), Review (sheet: comment, request changes,
approve; `:138-147`), Merge (with confirmation). Overflow: Open in browser, Copy link,
Share…, Link to this thread, Watch.

Data: `pullRequests.list`, `detail`, `checks`, `activity`, `comment`, `submitReview`,
`runAction`, `requestReviewers`, `setLabels` (typed API, `lib:README.md` pullRequests
table); payloads through `pull_requests::request` and `payload` (`lib:src/pull_requests.rs:16,268`).
These are unary calls already reachable through `call`, which validates their arguments
before sending. The Rust pieces still missing are the page rules of `pull_requests::payload`
(a comment needs words, only the page's actions; `lib:README.md`, Typed API notes) and
the row badge, both in U19.

### 4.9 Settings

- **ot3:** `ot3:ui/qml/T3Preferences.qml` (pages `:54`, search `:46,75-106`, environment
  scope `:108-127`, states `:130-143`). Audit rows `ot3:docs/SCREEN_PARITY_AUDIT.md:93-107`.
  Captures `ui/reference/ot3-preferences-appearance.png`, `ot3-preferences-source-control.png`.
- **T3 Mobile:** `t3m:src/features/settings/SettingsRouteScreen.tsx:80-220` (sections),
  scope filter `t3m:src/features/settings/components/SettingsEnvironmentFilterHeader.tsx:58-63`.

Root: "Search settings" field, environment scope select, then grouped rows (ot3 cards,
section title with icon); each row pushes its page.

| Group | Page | Content | Data | v1 |
|---|---|---|---|---|
| Connections | Environments | 4.2 | catalog, supervisor | yes |
| | Notifications | permission and, later, the follow service (`docs/ARCHITECTURE.md:27-29`) | — | permission only |
| Interface | Appearance | Theme, Dark theme, Light theme (3.1.3); Language; Show provider usage; Group working, snoozed and settled threads; Math rendering; Animated starfield Auto / Full / Light / Off (`ot3:ui/qml/T3Preferences.qml:246-275`) | DataStore | yes |
| Server | Providers | status per instance ("Signed in", "Not installed", "Update to {version}") read-only, and favourite models with stars (`ot3:ui/qml/T3ProviderInstances.qml:84-124`; `ot3:ui/qml/T3Preferences.qml:283-293`) | `server.getConfig`, `models::preferences` (U7) | yes |
| | General | ot3's server field groups (New threads, Organization, Behavior, Projects & threads, Text generation; `ot3:ui/qml/T3Preferences.qml:58-63`) | `server.getSettings` / `updateSettings`, `native_settings::snapshot` / `patch` (`lib:src/native_settings.rs:107,152`, U23) | read-only, edit after U23 |
| | Source control | `ot3:ui/qml/T3Preferences.qml:64-66` | same | read-only |
| | Projects | per-project scope (`ot3:ui/qml/T3Preferences.qml:40,70`) | `scoped_settings::effective`, `project_patch` (`lib:src/scoped_settings.rs:38,56`, U23) | read-only |
| Threads | Archived threads | 4.12 | | yes |
| App | Usage | 4.10 | | yes |
| | About | version, lib version, Licenses (ot3's list style), machine-translation note | | yes |

Page shell: title, scope sentence "Settings for {project}" or the environment
(`ot3:ui/qml/T3Preferences.qml:122-127`), groups of rows (label in subtitle 15 sp,
description caption `dim`, trailing control). States: "Loading…", error banner with
Retry, partial save, "No settings found" (`ot3:ui/qml/T3Preferences.qml:130-143`).
Scoped writes follow `ot3:docs/SCOPED_SETTINGS.md:5-15`.

### 4.10 Subscription usage

- **Quota strip** (optional under the thread and Home headers): "Provider usage", one
  group per provider with account count, per window a segmented bar with an
  expected-remaining marker (failed colour when under budget), remaining % and reset
  time, "Accounts" expander (`ot3:ui/qml/QuotaStrip.qml:21-119`).
- **Usage page** (Settings → Usage, T3 Mobile's place, `t3m:src/features/settings/SettingsRouteScreen.tsx:218`):
  T3 Mobile's Usage | Limits tabs (`t3m:src/features/usage/UsageRouteScreen.tsx:61-77`)
  holding ot3's content: period Day / Week / Month / Quarter, KPIs Cost, Tokens,
  Sessions, Cache savings, per-day bars switchable Cost / Tokens, per-model list,
  "Refresh prices" (`ot3:ui/qml/T3UsagePage.qml:31-76`); Limits = the quota strip,
  full size. Pull to refresh.
- Rules: automatic refresh at most every 5 minutes per environment; cached readings are
  labelled possibly outdated after a disconnect (`ot3:docs/SUBSCRIPTIONS.md:28-33`).
- Data: `server.getUsageSummary` and `server.refreshUsageRates` (typed API),
  `usage_summary::request` and `aggregate` with the device's IANA time zone
  (`lib:src/usage_summary.rs:23,40`), `quota_sources::limits`, `providers`, `sources`
  (`lib:src/quota_sources.rs:23,75,86`), U14.

### 4.11 Command palette and search

- **ot3:** `ot3:ui/qml/T3CommandPalette.qml`, groups built at `ot3:ui/qml/T3Panel.qml:39-74,916-920`.
- **T3 Mobile:** `t3m:src/features/keyboard/CommandPalette.tsx:136` (tablets, ⌘K).
- Full-screen dialog: field "Search commands, threads and settings..."
  (`ot3:ui/qml/T3CommandPalette.qml:50`); every word must match, title matches above
  detail matches (`:16-22`); "No results." (`:59`). Groups: **Actions** that exist on
  mobile (New thread, Find in current thread, Review changes, Thread details, Open parent
  thread, Next / Previous subagent, Copy thread reference, Add project, Open usage, Open
  settings; from `ot3:ui/qml/T3Panel.qml:42-69`), **Recent threads** (8), **Projects**
  (6; choosing one starts a thread there), **Settings** pages, **Messages** (server
  search, 12, 250 ms debounce; `ot3:ui/qml/T3Panel.qml:70-72,917-919`). ot3's "Memory
  (Obsidian)" group is desktop-only.
- Opened from the Home overflow and Ctrl+K. Data: shell snapshot, `orchestration.searchThreads`.

### 4.12 Archived threads

ot3's archive (`ot3:ui/qml/T3Archive.qml:57-122`; capture `ui/reference/ot3-archive.png`):
rows grouped by project with the chip, "Archived {archived} · Created {created}",
Unarchive; Delete asks 'Delete thread "{title}"?'; states "Loading archived threads",
"Could not load archived threads", "No archived threads", per-row "Check status" and
Retry. T3 Mobile adds search, swipe Unarchive with Delete as the secondary, and pull to
refresh (`t3m:src/features/archive/ArchivedThreadsScreen.tsx:54-60,253-268,451-453`).
Data: `orchestration.getArchivedShellSnapshot` (typed API), unarchive and delete commands
(request item 4).

### 4.13 Out of v1

| Surface | ot3 | T3 Mobile | Why not now |
|---|---|---|---|
| Terminal | header "Open native terminal" (`ot3:ui/qml/T3Panel.qml:1490`), `ot3:docs/NATIVE_TERMINAL.md` | `ThreadTerminal` (`t3m:src/Stack.tsx:781-785`) | phase 3 (`docs/ARCHITECTURE.md`); needs a terminal surface |
| Embedded browser, side panel | `ot3:ui/qml/T3BrowserWorkspace.qml`, `ot3:ui/qml/T3SidePanel.qml:26-29` | `ThreadBrowserPreview` (`t3m:src/Stack.tsx:797-805`) | links open in the system browser |
| Devices | `ot3:ui/qml/T3DevicePanel.qml` | `ThreadDevicePreview` (`t3m:src/Stack.tsx:786-796`) | video decoding and input; the lib has `device_hub` for later |
| QML / HTML / MCP app previews | `ot3:ui/qml/T3QmlBlock.qml`, `T3InlineHtml.qml`, `T3RenderPanel.qml` | `ThreadMcpApp` (`t3m:src/Stack.tsx:837-845`) | sandboxed web content; a card with "Open in browser" instead |
| File browser | — (file chips open viewers) | `ThreadFiles` (`t3m:src/Stack.tsx:824-836`) | ot3 has no browser; file chips get a read-only viewer later |
| Dictation, screenshots | `ot3:ui/qml/T3Composer.qml:451-467` | none on Android (`t3m:src/native/voiceTranscription.ts:3-5`) | later |
| Project drafts / stash | `ot3:ui/qml/T3ProjectDrafts.qml` | — | later |
| Scheduled tasks, agent session import, processes, storage cleanup, Obsidian memory, keybinding editor | `ot3:ui/qml/T3ScheduledTasks.qml`, `T3AgentSessions.qml`, `T3ProcessDiagnostics.qml`, `T3StorageSettings.qml`, `T3JournalSettings.qml`, `T3Keybindings.qml` | Settings pages | administration, desktop first |
| Provider install and sign-in flows | `ot3:ui/qml/T3ProviderInstances.qml:84-124` | Provider accounts | read-only status in v1 |
| Multiselect, drag reorder, bulk actions | chat row 7 | — | later |
| Row auras, FPS meter, bar widget | `ot3:ui/assets/shaders/threadaura.frag`, `T3FpsMeter.qml`, `BarWidget.qml` | — | desktop chrome |
| T3 Connect, live notifications | `ot3:ui/qml/T3ConnectionCloud.qml` | `ConnectOnboarding`, agent awareness | phase 3 |

## 5. Delivery plan

Seven PRs, each mergeable alone, each ending with captures compared side by side with
`docs/ui/reference/`. Common to all:

- **Screenshot tests on the JVM** (Roborazzi with Robolectric native graphics), because
  the emulator on the development machine has been broken since 2026-10-24: every screen
  of the PR is rendered from fixture JSON in tokyo-night and catppuccin-latte, on a
  411 × 891 dp phone and, from UI-7, a 1280 × 800 dp tablet. Goldens are committed;
  CI fails on a diff.
- **Visual acceptance:** a `docs/ui/compare/<pr>/` sheet per PR placing each golden next
  to the matching ot3 capture. Acceptance means same structure, same colour roles
  (sampled pixels equal the token), same proportions within the 7/6 text rule; not pixel
  identity.
- **Unit tests:** JVM tests for every mapping from lib JSON to UI state, with fixtures
  taken from the lib's outputs; Compose UI tests (instrumented) for interactions, run
  when the emulator is back.
- Strings only through the generator; no hard-coded text.

| PR | Scope | Visual acceptance | Tests | Lib needed |
|---|---|---|---|---|
| **UI-1 Visual system and Home** | theme generator and JSON (22 themes), `Ot3Theme`, Fira Code, Lucide vectors, kit (3.9), sky header (AGSL + Canvas), strings generator, typed routes and the three tabs, merged Home with header, search, filter sheet, card and slim rows, shelves, swipe and long-press sheets (Copy and Filter only until builders), Environments under Settings restyled, Component catalog | Home vs `ot3-sidebar-and-thread.png` (left column) and `ot3-timeline-shelves-composer.png` (shelves, slim rows); sky in a dark and a light theme | generator goldens (tokens.md §1); status, time and shelf mapping; sky uniforms; navigation (row → thread, back order) | none blocking; U2 and U21 for full status, order and chips |
| **UI-2 Thread timeline** | top bar, find, user and assistant messages, Markdown renderer, copy, fork, quote, activity blocks and inspector, Thinking rows, changed-files card, dividers, error cards, subagent cards and child threads, plan card, working pill, jump to latest, Load earlier, states (4.3) | thread vs `ot3-sidebar-and-thread.png` (right) and `ot3-timeline-shelves-composer.png` (Markdown) | row mapping per role; Markdown blocks; scroll policy with streaming | U0, U3, U4, U10 (blocking for activity, changes, dividers); U18 for history |
| **UI-3 Composer** | frame, collapsed / card / expanded, completions, model picker, options, permission, Plan / Build, send / queue / steer / stop and Send options, queue strip and sheet, attachments with upload, drafts status, notices (4.4) | composer vs `ot3-sidebar-and-thread.png` (bottom) and `ot3-model-picker.png` | send state machine; queue mapping; IME insets | U5, U7, U8, request item 4 |
| **UI-4 Approvals, questions and plans** | approval and question cards, secret card, ready-plan banner and follow-ups, tasks strip, Edit from here (4.5, 4.3.7) | cards vs ot3 card tokens; plan banner vs `ot3-plan-card.png` | decision gating; multi-question flow; replay rules after `rpc_outcome_unknown` | U6, U15, U24, request item 4 |
| **UI-5 Changes, diff, Git and pull requests** | diff screen and file list, Git & changes sheet (status, Review; actions after U22), PR tab, PR detail and files, PR badge on rows, new thread flow and branch picker, Add project (4.6–4.8) | diff vs `ot3-changes-review.png` and `ot3-diff-split.png`; Git sheet vs `ot3-git-controls.png`; PR list vs `ot3-pull-requests.png` | hunk rendering; source switching; PR filters and actions | U9, U19, U20, U22 |
| **UI-6 Settings, palette, usage, archive** | Settings root and pages, theme picker with previews, Add environment with QR and pairing link, Providers and favourites, Usage and quota strip, Archived threads, command palette, Licenses (4.2, 4.9–4.12) | Settings vs `ot3-preferences-*.png`; archive vs `ot3-archive.png` | theme switching; scope; palette ranking; usage aggregation via the lib | U7, U14, U17, U23 |
| **UI-7 Tablets and foldables** | navigation rail, list / detail / inspector panes with T3 Mobile's breakpoints, hinge alignment, empty detail, inspector content, tablet minimap, hardware keyboard shortcuts and the shortcut helper (2.4, 4.3.8) | split layout at 1280 × 800 dp and 840 × 1200 dp against the phone goldens | breakpoint function at each threshold; back across panes; key handling | U12 |

UI-1 also records the decisions of section 1 in `docs/DECISIONS.md` (the architecture
line on themes already changes with this spec).

## 6. What the lib would have to expose (for `LIB_REQUESTS.md`)

> **Note (2026-10-25, lib v0.3.4).** Every row of the table below is now exposed by the
> lib through `{"op":"domain"}` (108 functions in `describe().domain`; v0.3.3 closed U0 to
> U8, U10, U17, U18 and U21, v0.3.4 the rest), and ot3's string catalog ships in the AAR
> as the asset `lib-t3-rust/i18n.json` (U16). The "Until then" column and the interim
> notes in section 4 are therefore historical: `SidebarModel.kt` and
> `ThreadProjectionShim.kt` no longer exist (the sidebar reads `sidebar.view`, the thread
> `projection.apply` and `timeline.view`). The function names per row, their status and
> the tests that exercise them are in [LIB_REQUESTS.md](LIB_REQUESTS.md), "2026-10-25:
> v0.3.4". Still open there: the shell reducer half of U2 (v0.3.3 finding 1, `shell.apply`).

As of v0.3.2 no presentation module is reachable from Kotlin: the C ABI's operations are
the transport ones plus `call` / `describe` (`lib:src/ffi.rs:30-37`), and `call` only
sends T3 RPCs. `LIB_REQUESTS.md:226-230` already proposes one pure, connection-free
operation (`{"op":"domain","fn":"<module>.<fn>","args":{...}}`) for projection, sidebar,
shell, reconnect, connection catalog, history, composer, approvals, questions, queue and
models. This spec depends on that operation and adds the rows marked **new**; the others
restate an existing item with the screen that needs it. Several derivations exist only in
ot3's own crate, not in the lib, and would move into the lib first.

| # | Request | In Rust today | Screen | Until then |
|---|---|---|---|---|
| U0 | **new** Timeline view: ot3's `conversation_timeline` rows (user, assistant, activity groups, changes, context, plan, secret, subagents, error, each with a `rev`) and `live_activity`, from a projection or the reduced view of item 1 | **ot3 only**: `ot3:src/lib.rs:532-617` | Thread (4.3), working pill (4.4.1) | user and assistant messages from `ThreadProjectionShim` (`core/.../thread/ThreadProjectionShim.kt:54`) |
| U1 | Reduced thread view (existing item 1) | `projection::apply` (`lib:src/projection.rs:8`) | Thread | shim, unchanged |
| U2 | `sidebar.view` + shell reducer (existing item 3), **extended to several environments' shells in one view** and to ot3's ten-state detail and counts | `sidebar::sidebar_status`, `metadata`, `sort_timestamps` (`lib:src/sidebar.rs:6,19,37`); detail, grouping and order only in ot3 (`ot3:ui/js/SidebarModel.js:5-125`, `ot3:ui/js/ProjectModel.js:4-12`) | Home (4.1), count pills | `SidebarModel.kt`, extended to several environments |
| U3 | **new** `activity.describe` | `lib:src/activity.rs:38` | Activity blocks (4.3.3) | blocks hidden |
| U4 | **new** `changes.summaries` | `lib:src/changes.rs:4` | Changed-files card (4.3.4), diff ranges (4.6.2) | card hidden |
| U5 | `queue.derive`, `queue.command` (existing pure-form list) | `lib:src/queue.rs:5,31` | Queue (4.4.6) | queue hidden |
| U6 | `approvals.pending` / `command`, `questions.pending` / `command` (existing items) | `lib:src/approvals.rs:11,61`, `lib:src/questions.rs:5,37` | Cards (4.5) | today's banner |
| U7 | `models.catalog`, `preferences`, `favoritePayload`, `runtimeModeCommand` (existing item 4) | `lib:src/models.rs:7,12,21,37` | Model picker, permissions, Providers (4.4.3, 4.4.4, 4.9) | read-only model label |
| U8 | `composer.paths`, `composer.catalog` (existing pure-form list) | `lib:src/composer.rs:12,16` | Completions (4.4.2) | none |
| U9 | **new** `diffReview.present` | `lib:src/diff_review.rs:100` | Diff (4.6) | file list without hunks |
| U10 | **new** Markdown as renderer-neutral blocks (paragraphs, headings, lists, quotes, tables, code with language and runnable flag, images, links classified GitHub / file / thread / web with ot3's allow-list); the lib says an Android surface "gets its own variant once its renderer is designed" (`lib:README.md:602-612`) | ot3's renderer emits Qt rich text (`ot3:src/lib.rs:119-171`); `code_blocks::parse` already returns fenced blocks as data (`lib:src/code_blocks.rs:12`) | Messages (4.3.2) | display-only CommonMark in Kotlin; only http(s) links tappable |
| U11 | **new** `theme.tokens`: Omarchy `colors.toml` in, ot3 roles out (3.1) | **ot3 only**: `ot3:ui/qml/T3Palette.qml`, `ot3:ui/qml/T3Panel.qml:380-387`, `ot3:ui/js/DocumentTheme.js` | every screen | precomputed by the generator with goldens |
| U12 | **new** `timeline.minimap` | **ot3 only**: `ot3:ui/js/TimelineModel.js:4,16` | Tablet minimap (4.3.8) | no minimap |
| U13 | **new** `subagents.buckets` and labels | **ot3 only**: `ot3:ui/js/Subagents.js:8-54` | Subagent cards (4.3.6) | hidden |
| U14 | **new** `usageSummary.request` / `aggregate`, `quotaSources.limits` / `providers` / `sources` | `lib:src/usage_summary.rs:23,40`, `lib:src/quota_sources.rs:23,75,86` | Usage (4.10) | page hidden |
| U15 | **new** `planWorkflow.view` / `followUp` with a renderer that returns Markdown | `lib:src/plan_workflow.rs:191,325` | Plan card and banner (4.3.7) | plan as a message |
| U16 | **new** ship ot3's string catalog with the lib (or the AAR) | `i18n::translate` has no catalog (`lib:src/i18n.rs:4`); catalog in `ot3:translations/i18n.json` | every screen | generated `strings_ot3.xml` |
| U17 | Pairing-link parser (existing item 6) | `lib:src/connection_catalog.rs:5` | Add environment (4.2) | URL + token only |
| U18 | History paging (existing item 7) | `lib:src/history.rs:3,20` | Load earlier messages (4.3.2) | snapshot window only |
| U19 | **new** `pullRequests.sidebarBadge`, `pullRequests.payload` | `lib:src/pull_requests.rs:82,268` | Row PR badge (3.7), PR comment, review and actions (4.8) | no badge; arguments built from the `describe` schema |
| U20 | **new** `branchPicker.dedupe` / `selectionTarget` / `mismatch` | `lib:src/branch_picker.rs:9,41,90` | Branch picker, branch mismatch notice (4.7.3, 4.4.1) | refs listed as returned |
| U21 | **new** project identity: monogram and colour slot | **ot3 only**: `ot3:src/lib.rs:729-755`, `ot3:ui/qml/T3Palette.qml:36-46` | Project chip everywhere (3.7) | first letter of the title, accent tint |
| U22 | **new** `gitControls.scope` / `actions` / `present` / `buildAction` | `lib:src/git_controls.rs:37,185,203,245` | Git & changes sheet (4.6.3) | status and Review only |
| U23 | **new** `nativeSettings.snapshot` / `patch`, `scopedSettings.effective` / `projectPatch` | `lib:src/native_settings.rs:107,152`, `lib:src/scoped_settings.rs:38,56` | Settings pages (4.9) | read-only |
| U24 | **new** `checkpointRevert.canPreview` / `reference` / `command` / `observation` / `recoveryMessage` | `lib:src/checkpoint_revert.rs:7,39,159,208,248` | Edit from here (4.5.4) | not offered |
| U25 | **new** `toolInspector.scope` / `request` / `parse` | `lib:src/tool_inspector.rs:61,89,236` | Inspector (4.3.3) | not offered |

Order of need: nothing blocks UI-1 (U2 and U21 complete it); U0, U3, U4 and U10 gate
UI-2; U5, U7 and U8 gate UI-3; U6, U15 and U24 gate UI-4; U9, U19, U20 and U22 gate UI-5;
U14, U17 and U23 gate UI-6; U12 gates UI-7. U11 gates nothing because the generator
covers it, but it keeps ot3 and Android from drifting. When this spec is accepted, the **new** rows move into
`LIB_REQUESTS.md` as a dated section.

## 7. References and T3 Mobile's showcase

- ot3 captures: [ui/reference/](ui/reference/README.md), 13 images copied from ot3
  (`docs/images/` and the ot3 captures in `docs/audit/screenshots/`), by the same
  author. They are the visual target of section 5.
- No T3 Code images are copied. T3 Mobile has no gallery of its own; it has two things
  worth copying as practice:
  - **Store screenshots from the real app.** `pnpm screenshots:mobile` runs
    `t3:scripts/mobile-showcase.ts` (`t3:package.json:18`), which drives the real app
    against three disposable seeded servers ("Moonbase Terminal", "Suspense Station",
    "Kernel Cabin"; `t3:scripts/mobile-showcase-environment.ts:133-143`) through the scenes
    threads, thread, terminal, review, environments and agent-activity
    (`t3:scripts/mobile-showcase-environment.ts:14-22`) on iPhone, iPad, an Android phone
    and 7" and 10" Android tablets (`t3:scripts/mobile-showcase.config.ts:79-200`). In the
    app, `EXPO_PUBLIC_SHOWCASE` mounts a coordinator that resets navigation to the
    requested scene (`t3m:src/App.tsx:37-39`;
    `t3m:src/features/showcase/ShowcaseCaptureCoordinator.tsx:47-57,236-273`); Android
    captures use `adb exec-out screencap` (`t3:scripts/mobile-showcase.ts:1435`). The
    output is store-ready PNG folders (`t3:docs/operations/mobile-app-store-screenshots.md:64-100`).
  - **A dev-only component catalog** (`t3m:src/features/development/ComponentCatalogScreen.tsx:61-280`).
- ot3-Android takes both ideas: the Component catalog (3.9) and the screenshot tests of
  section 5 use the same scene list (Home, thread, review, environments) with fixture
  data, so the comparison sheets double as a showcase.

## 8. Summary

1. Structure and navigation from T3 Mobile; look, components, wording and behaviour from ot3; data only from the lib.
2. Three tabs (Chats, PRs, Settings), a merged multi-environment Home, pushed thread, diff and PR screens, sheets for pickers and menus.
3. Tablets keep T3 Mobile's exact breakpoints: rail + list + detail at 720 × 600 dp, an inspector from 960 dp, hinge alignment on foldables.
4. Colours are the 22 Omarchy themes, converted to a JSON of precomputed ot3 roles; one luminance rule; Material 3 restyled, not replaced.
5. Text is ot3's scale × 7/6 (body 14 sp), lengths 1 px = 1 dp, 48 dp touch targets; Fira Code for code; Lucide icons as vectors.
6. The sky header is a port of ot3's starfield shader (AGSL, Canvas below API 33), with comets only while something works and full reduced-motion handling.
7. Every screen names its ot3 QML, its T3 Mobile TSX and the lib function it reads; strings come from ot3's 20-locale catalog.
8. Approvals and questions sit above the composer as ot3 cards; questions stay paged; the composer is hidden while a question waits.
9. Seven PRs: visual system and Home, timeline, composer, approvals, changes/diff/PRs, settings/palette/usage, tablets; each with JVM screenshot goldens compared to ot3 captures.
10. The lib needs 26 exposures (U0–U25), 18 of them new, led by the timeline view, the multi-environment sidebar view, activity, changed files and Markdown blocks.
