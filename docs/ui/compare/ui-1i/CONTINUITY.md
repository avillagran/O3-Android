# UI2 timeline and UI3 composer: bounded continuation

UI1i fixes the production Home and chat chrome. **Chat visual parity is not complete.**
The current `ThreadContent` still consumes only `ThreadView.messages`; it discards no
Rust data, because `ThreadView.timeline` already retains the full `timeline.view` object.
The next implementation starts there, not with another Home audit or a new protocol.

## UI2, next PR: render the existing timeline view

Contract: map `ThreadView.timeline.timeline` rows by the lib's `id`, `rev` and `role`.
Keep the projection/session and one stream in `ThreadSession`; do not derive grouping,
terminal assistant messages, activity state, rewind/fork eligibility or history in Kotlin.
Use the lib's fields. Missing contracts go to `LIB_REQUESTS.md`.

First vertical slice: user/assistant rows with sender/time, prose Markdown and code,
then the activity block between them. Preserve the newest-bottom anchor, reading
position, selection and draft through rotation, sidebar collapse and recreation.
Unknown roles must have an explicit safe presentation, not silently disappear.

Acceptance before calling the timeline visually complete:

- Render user, assistant commentary/final, activity/tool, changes, plan, subagents,
  context and error roles using the full view. Use stable id/rev keys.
- User surface and assistant unboxed prose follow `T3Panel.qml:1655-1753`; sender/time,
  actions and copy follow `1893-1905`; activity follows `T3ActivityBlock.qml`;
  changes/plan/subagents use their corresponding components (`1866-1887`).
- Markdown has actual headings, links, lists, fenced code in Fira Code, horizontal code
  scrolling, and selection. No HTML execution or Kotlin reconstruction of protocol data.
- Streaming grows without jumping a reader who scrolled away; explicit jump-to-latest;
  history paging consumes `historyCursor`/`hasMoreHistory` and retains the visible anchor.
- Approvals/questions use the lib's rows and command builders, with disabled/unavailable
  actions when the lib does not expose them; no optimistic success or fake controls.
- Same-content/theme vertical comparison against
  `../../reference/ot3-timeline-shelves-composer.png` and
  `../../reference/ot3-sidebar-and-thread.png`, plus streaming/history/rotation video.
  Fixture evidence and live evidence are reported separately.

## UI3, following PR: replace the minimal composer

Contract: consume the lib's composer/model/queue/suggestions/permission views and typed
command builders; preserve `DraftRepository`, outbox IDs and uncertain-send handling.
UI1i's multiline editor, focused border and send control are chrome, not that feature set.

Acceptance:

- Source composition: `T3Composer.qml`, especially editor, attachment chips, model and
  option row, permission picker, send/queue/steer and completion popup; UI_SPEC §4.4.
- Model/provider, effort, permission, attachments, mentions/skills and queue state come
  from the lib; keyboard editing never invents a wire tag or eligibility rule.
- Attachments use descriptors and the existing Rust upload path. Token/secret text is
  never logged, persisted in screenshots or copied into fixtures.
- Editor grows to the agreed six-line ceiling and scrolls internally; the control row
  stays reachable at font scale 1.5, portrait/landscape and with the real IME visible.
- Send, queue and steer reflect their distinct outcomes; interrupt, retry and unknown
  outcome keep the stable command ID. A failed send never erases the draft.
- Contract tests cover an actual view fixture and transport command/output boundary;
  emulator evidence includes typing, attachment/model sheets, queue, failed send,
  rotation and IME. Live send only in an explicitly authorized test thread.

## Remaining visual scope beyond this correction

Selectable warp/fold reveal styles and the pace/effect preference UI are not
implemented by UI1i. Navigation uses Navigation Compose defaults with predictive back. Sidebar reveal ports
the default nebula shader (560 ms InOutCubic); spring (520 ms OutBack 1.3) is only the
older-API/software/compile-failure fallback. Natural width is fixed and the edge clips.
Departure drift and cross-shelf flight arcs are also pending. The full shader variants remain part of the motion inventory/spec work, not a claim
that all desktop effects or the complete chat have shipped. UI1i does ship the actual
Home sky, row comet/glints/change shader, pressed feedback and reduced-motion limits.

## UI2a continuation (2026-10-26)

The next bounded slice is implemented on `feat/ui2-reader-sidebar`; see
[UI2a](../ui-2a/README.md) for comparison, evidence and outstanding work. Production
workspace now owns its sidebar control in both orientations. TimelineReader consumes all
Rust rows and supplied Markdown blocks; scoped activity/reasoning details use the existing
inspector contract. Composer persistence/outbox stay in ThreadComposer. Full UI2/UI3
acceptance above is still the target, not a claim that every item has shipped.
