# Sidebar presentation contract (P04)

`HomeScreen` observes P02's persisted appearance settings. Existing row intents and swipe,
project, PR and action callbacks retain their signatures. No navigation integration is needed
for the layout selector. The Home-only composition local leaves other kit consumers unchanged.

Desktop sources: `T3Panel.qml:469–484,665–716,1838–1840`, `SidebarModel.js` reply comparison,
`ProjectTree.js`, `T3ThreadBoard.qml`, `T3MarqueeText.qml`, `HostStyle.js`, and
`T3HostsPopup.qml:106–138` on `feat/o3-brand`.

Phone adaptations:

- Classic retains native independently bounded Snoozed/Settled shelves. Grouped inbox adds
  Pinned and Waiting for input. Ready pinned threads leave Pinned as on Desktop.
- Focus stacks the four task buckets vertically. Task board (`board`) presents Pending, Working, Waiting and Done in horizontally scrollable columns with independent lazy lists. It consumes native filtered `boardRows` before list shelf folds, excludes subagents, persists the mode and column folds, and keeps the existing thread/project menus. Horizontal card gestures scroll columns; thread swipe mutations remain in the list layouts. The base board opens no extra thread subscriptions; Live view watches only visible cards.
- Projects sorts project/state trees
  alphabetically. Both use sober style. Touch targets remain at least 48dp.
- Section folds and five-row limits persist separately from native shelf membership.
  Show more removes the limit; selected rows bypass the limit. Stable lazy keys and list state
  preserve scroll during shell replacement. Title marquee runs only on the selected row with
  motion enabled; long press continues to open P05's actions.
- Host styles persist per host; icon/color/name controls affect only local presentation. The
  eight swatches and seven host kinds follow Desktop. Linux, Mini PC and Workstation use
  the existing terminal/server/monitor glyphs until platform assets are provided. Project colors can be disabled independently.
- Reply clocks observe existing shell supervisors, without creating connections/subscriptions.
  Use normalized completion timestamps, then updated/created; active rows include activity.
  Authoritative injected metadata takes precedence. Missing clocks preserve native order.

Integration limits (do not count as verified Desktop parity):

- `SidebarMetadata.threadGroups` / `projectGroups` accept authoritative named assignments.
  Their shared persistence/mutations are P05's prerequisite; no local shadow group policy is
  invented. Without that metadata, only project chips render. Repository/path grouping and
  checkout headers need the normalized native group/member contract (P05/P20).
- With active search or native filters, Focus/Projects use the native visible rows and honor
  folded native shelves. The current HomeUiState omits the complete filtered section arrays;
  Task board uses the new `boardRows` projection to remain independent of Classic folds while respecting native filters.
- Shell completion order is available. Individual streaming answer timestamps and the
  Desktop pinned-ready selection hold require richer authoritative metadata from P20.
- Host platform defaults are unavailable in HomeRow; an unset host uses server. Explicit host
  customization works. Desktop host-style synchronization is outside the local preference store.
- No phone, emulator, TalkBack, live host or real touch acceptance was performed here.

Performance (2026-11-02):

- Main and shelved trees index descendant/working counts in one linear pass, rather than copying the remaining rows for every item. Entries and shelf projections are remembered across fold-only changes; ancestor fold checks use exact keys.
- Native Home rows already mapped for visible sections are reused in the all-thread action lookup. The board membership comes from those same rows; filtered-out action targets cannot reappear as board cards.
- Reply clocks are calculated on Dispatchers.Default, and invisible filtered-out elapsed clocks do not force one-second Home projections.
- Regression coverage includes native filter membership, collapsed shelves, persisted mode, column folds, empty narrow screens, thread actions and horizontal gestures. Board goldens cover phone, landscape and large text in both themes.

Live view (2026-11-02):

- The persisted global Live view switch and per-card expand button show the latest eight user/assistant messages. Expanded previews grow from 160dp to 260dp; Write message opens the existing full conversation and composer.
- Readers use ThreadSession on the selected host's existing authenticated connection. Only the intersection of visible columns and visible card rows can watch, capped at 24 readers. Folded/offscreen cards, leaving the board and background lifecycle close their streams; resuming the same retained card keeps its cursor.
- Authorization changes replace the remembered session and its preview composition. Board readers do not take the main conversation's disk cache lease and never write a transcript cache.
- Follow-to-tail stops when the user scrolls upward; text updates resume following when at the bottom. Previews bound displayed text and retain the existing reconnect and repaint behavior.
