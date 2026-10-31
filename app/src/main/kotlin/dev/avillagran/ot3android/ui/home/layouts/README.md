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
- Focus stacks the four task board buckets vertically. Projects sorts project/state trees
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
  P20 should expose these to make the alternate layout trees independent of Classic folds.
- Shell completion order is available. Individual streaming answer timestamps and the
  Desktop pinned-ready selection hold require richer authoritative metadata from P20.
- Host platform defaults are unavailable in HomeRow; an unset host uses server. Explicit host
  customization works. Desktop host-style synchronization is outside the local preference store.
- No phone, emulator, TalkBack, live host or real touch acceptance was performed here.
