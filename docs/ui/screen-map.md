# Screen map: ot3 audit → Android

Companion to [UI_SPEC.md](../UI_SPEC.md) sections 4 and 5. It traces every route row of
ot3's screen audit (`ot3:docs/SCREEN_PARITY_AUDIT.md:80-109`, 28 native route files) and
the chat rows of `ot3:docs/audit/CHAT_PARITY.md` to the Android screen that covers it and
the PR (section 5) that builds it. Audit statuses are ot3's own and describe the desktop.

Caveat from the research: the line numbers *inside* the audit matrices refer to an older
ot3 working tree (`ot3:docs/audit/CHAT_PARITY.md:3`) and do not match the pinned
snapshot; UI_SPEC cites the code directly instead.

## Route rows

| Audit row | Native route | ot3 status | Android | PR |
|---|---|---|---|---|
| `:82` | `__root` (layout) | Adapted/partial | App shell: `Ot3Theme`, navigation suite, panes (2) | UI-1, UI-7 |
| `:83` | `/_chat/$environmentId/$threadId` | Partial | Thread (4.3), composer (4.4) | UI-2, UI-3 |
| `:84` | `/_chat/draft/$draftId` | Adapted/partial | Drafts per thread (Room, existing) and new-thread draft (4.7) | UI-3, UI-5 |
| `:85` | `/_chat/` | Adapted/partial | Home (4.1); empty detail pane on tablets (2.4) | UI-1, UI-7 |
| `:86` | `/_chat/new` | Partial | New thread flow (4.7) | UI-5 |
| `:87` | `/_chat/pull-requests` | Partial | Pull requests tab and detail (4.8) | UI-5 |
| `:88` | `/_chat` | Adapted/partial | Navigation suite + list/detail (2) | UI-1, UI-7 |
| `:89` | `/connect-agent` | Missing | Out of v1 (4.13) | — |
| `:90` | `/connect` | Adapted | T3 Connect: phase 3 (`docs/ARCHITECTURE.md`) | — |
| `:91` | `/pair` | Adapted/partial | Add environment (4.2) | UI-6 |
| `:92` | `/projects/$projectKey` | Partial | Project actions sheet (4.1.5) and Settings → Projects (4.9) | UI-1, UI-6 |
| `:93` | `/settings/appearance` | Adapted/partial | Settings → Appearance (4.9) | UI-6 |
| `:94` | `/settings/archived` | Implemented core | Settings → Archived threads (4.12) | UI-6 |
| `:95` | `/settings/connections` | Partial | Settings → Environments (4.2), routes and route editing (4.2.1), OmaBridge page (4.9.1, blocked) | UI-6; OmaBridge page UI-OB |
| `:96` | `/settings/diagnostics` | Missing | Out of v1 | — |
| `:97` | `/settings/general` | Partial | Settings → General, read-only first (4.9) | UI-6 |
| `:98` | `/settings/integrations` | Partial | Out of v1 (browser and devices) | — |
| `:99` | `/settings/keybindings` | Partial | Tablet hardware shortcuts, fixed map (2.3); no editor in v1 | UI-7 |
| `:100` | `/settings/open-source-licenses` | Implemented/adapted | Settings → About → Licenses (4.9) | UI-6 |
| `:101` | `/settings/projects` | Partial | Settings → Projects (4.9) | UI-6 |
| `:102` | `/settings/providers` | Partial | Settings → Providers: status and favourite models (4.9) | UI-6 |
| `:103` | `/settings/scheduled-tasks` | Missing | Out of v1 | — |
| `:104` | `/settings/snap-shot` | Missing/conditional | Out of v1 | — |
| `:105` | `/settings/source-control` | Partial | Settings → Source control, read-only first (4.9) | UI-6 |
| `:106` | `/settings/storage` | Missing UI | Out of v1 | — |
| `:107` | `/settings` | Partial | Settings tab (4.9) | UI-6 |
| `:108` | `/usage` | Partial | Settings → Usage with Usage and Limits tabs (4.10.3); compact quota strip in Home (4.10.2) | UI-1 (strip), UI-6 (page) |
| `:109` | `/welcome` | Partial | First run = Add environment (4.2) | UI-6 |

## Chat, sidebar and workspace rows (`ot3:docs/audit/CHAT_PARITY.md`)

| Row | Area | Android | PR |
|---|---|---|---|
| 1 (`:9`) | Thread / draft / new routes | Typed routes, deep links `ot3a://thread/…` (2.1) | UI-1 |
| 2 (`:10`) | Shelves, order, collapse | Home shelves (4.1.4) | UI-1 |
| 3 (`:11`) | Row status, project/branch, pin, PR badge | Thread rows (4.1.3) | UI-1, UI-5 (PR badge) |
| 4 (`:12`) | Title search | Home search (4.1.2) | UI-1 |
| 5 (`:13`) | Project scope and status filters | Filter sheet (4.1.2) | UI-1 |
| 6 (`:14`) | Quick actions and context menu | Swipe actions + long-press sheet (4.1.5) | UI-1 |
| 7 (`:15`) | Multiselect, drag, bulk | Out of v1 | — |
| 8 (`:16`) | Project actions and defaults | Project actions sheet (4.1.5) | UI-1 |
| 9 (`:17`) | Footer, environment, density | Connection status in the header; environments in Settings (4.1.1, 4.2) | UI-1 |
| 10 (`:18`) | New thread: project, title, model, workspace | New thread flow (4.7) | UI-5 |
| 11–15 (`:19-23`) | Add project: environment, folder, new, clone, progress | Add project flow, existing-project lookup first (4.7) | UI-5 |
| 16 (`:24`) | Command palette and search | Command palette (4.11) | UI-6 |
| 17 (`:30`) | Chat header and title actions | Thread top bar (4.3.1) | UI-2 |
| 18 (`:31`) | Find and older history | Find in conversation, Load earlier messages (4.3.1) | UI-2 |
| 19 (`:32`) | Timeline layout, bubble, metadata | Messages (4.3.2) | UI-2 |
| 20–23 (`:33-36`) | Markdown prose, code, tables, Mermaid/math | Markdown renderer (4.3.2); Mermaid and math as code in v1 | UI-2 |
| 24 (`:37`) | User message copy, prompt link, checkpoint revert | Copy in UI-2; "Edit from here" in UI-4 (4.5.4) | UI-2, UI-4 |
| 25 (`:38`) | Assistant copy, fork | Copy, fork (4.3.2) | UI-2 |
| 26 (`:39`) | Activity, work log, inspector | Activity blocks + inspector sheet (4.3.3) | UI-2 |
| 27 (`:40`) | Changed files and per-file review | Changed-files card (4.3.4), diff (4.6) | UI-2, UI-5 |
| 28–30 (`:41-43`) | Images, Markdown images, other files | Message images and file chips (4.3.2) | UI-2 |
| 31 (`:44`) | Inline HTML render | Card with "Open in browser" only (4.13) | UI-2 |
| 32 (`:45`) | Context divider, compaction, handoff | Context divider (4.3.5) | UI-2 |
| 33 (`:46`) | Plan, secret request, lifecycle cards | Plan card (4.3.7), secret card (4.5.3), error cards (4.3.5) | UI-2, UI-4 |
| 34 (`:47`) | Approvals | Approval card (4.5.1) | UI-4 |
| 35 (`:48`) | Questions | Question card (4.5.2) | UI-4 |
| 36 (`:49`) | Queue | Queue strip and sheet (4.4.6) | UI-3 |
| 37 (`:50`) | Tasks | Tasks strip (4.4.1 overlay order) | UI-4 |
| 38 (`:51`) | Provider subagent notice | Subthread bar and subagent cards (4.3.6) | UI-2 |
| 39 (`:57`) | Composer send / stop / queue / new thread | Composer (4.4.1, 4.4.5) | UI-3 |
| 40 (`:58`) | Fullscreen editor | Expanded composer (4.4.1) | UI-3 |
| 41 (`:59`) | Project drafts / stash | Out of v1 | — |
| 42 (`:60`) | History, list continuation, undo | Prompt recall and list continuation (4.4.1) | UI-3 |
| 43 (`:61`) | Upload and in-editor context | Attachments (4.4.7) | UI-3 |
| 44 (`:62`) | @ files, $ skills, / commands | Completions (4.4.2) | UI-3 |
| 45 (`:63`) | Provider, model, favourites, options | Model picker and options sheets (4.4.3, 4.4.4) | UI-3 |
| 46 (`:64`) | Runtime permissions | Permission sheet (4.4.4) | UI-3 |
| 47 (`:65`) | Plan/Build and follow-up | Plan toggle (4.4.4), ready-plan banner (4.3.7) | UI-3, UI-4 |
| 48 (`:66`) | Terminal context, citations | Out of v1 | — |
| 49 (`:67`) | Compact state, usage and status banners | Working pill and banners (4.4.1) | UI-3 |
| 50–51 (`:73-74`) | Worktree confirmation, machine / branch / worktree picker | New thread flow (4.7) | UI-5 |
| 52 (`:75`) | Git actions and project scripts | Git & changes sheet: status and Review first (4.6.3) | UI-5 |
| 53 (`:76`) | Thread details, open-in | Thread details sheet, read-only (4.3.1) | UI-2 |
| 54 (`:77`) | Relationships: parent, fork, subagent | Subthread bar (4.3.6) | UI-2 |
| 55 (`:78`) | Thread automations | Out of v1 | — |
| 56–57 (`:79-80`) | Terminal | Out of v1 | — |

## Surfaces outside the audit (added 2026-10-25)

ot3 components that the audit matrices do not list as routes, mapped for the motion,
usage and OmaBridge requirements (UI_SPEC 3.4, 4.2.1–4.2.2, 4.9.1, 4.10).

| ot3 surface | Source | Android | PR |
|---|---|---|---|
| `QuotaStrip` under the thread header, toggled by Show provider usage | `ot3:ui/qml/QuotaStrip.qml:8-126`; `ot3:ui/qml/T3Panel.qml:1153-1158,1520-1537` | Compact quota strip as Home's list header and under the thread top bar (4.10.2) | UI-1 (Home), UI-2 (thread) |
| Usage page (popup "Usage", Ctrl+U) | `ot3:ui/qml/T3UsagePage.qml:8-78`; `ot3:ui/qml/T3Panel.qml:67,317,892` | Settings → Usage, **Usage** tab (4.10.3) | UI-6 |
| Provider limits (strip at full size) | `ot3:ui/qml/QuotaStrip.qml:21-120` | Settings → Usage, **Limits** tab (4.10.3) | UI-6 |
| Routes of an environment (Preferred route, Add route, Remove route) | `ot3:ui/qml/T3Connections.qml:68,71-79` | Environments → environment → routes (4.2.1) | UI-6 |
| "Connected through OmaBridge · {peer}" / bar mark `omabridge` | `ot3:ui/qml/T3ConnectionOmaBridge.qml:38-44`; `ot3:ui/qml/BarWidget.qml:64-82`; `ot3:ui/qml/T3OmaBridgeIndicator.qml:26-53` | Route-in-use icon in the Home brand row, thread top bar, Environments rows and Thread details (4.2.1) | UI-1 |
| Per-environment OmaBridge routing (Automatic, Saved route only, Only through OmaBridge) | `ot3:ui/qml/T3ConnectionOmaBridge.qml:61-116` | Plans on OmaBridge peer routes (4.2.2), after OB1 | UI-OB (blocked) |
| OmaBridge device page (this device, publish, invite / pair, approvals, paired devices, gateways) | `ot3:ui/qml/T3OmaBridgeManage.qml:547-1424`; `ot3:ui/js/OmaBridgeModel.js`; `ot3:ui/js/OmaBridgeText.js` | Settings → Connections → OmaBridge (4.9.1) | UI-OB (blocked on OMABRIDGE_REQUESTS OB1–OB3) |
| OmaBridge quick popup | `ot3:ui/qml/T3OmaBridgeQuick.qml:14-26` | Approvals dot on the Settings tab and the OmaBridge row (4.9.1) | UI-OB (blocked) |
| Preferences → Animations | `ot3:ui/qml/T3Preferences.qml:258-275` | Settings → Appearance → Animations (3.4.4) | UI-6 (UI-1 reads the defaults) |
| `T3Reveal` show / hide, `T3RowFlight`, row motion | `ot3:ui/qml/T3Reveal.qml`; `ot3:ui/qml/T3RowFlight.qml`; `ot3:ui/qml/T3Panel.qml:575-669` | `Ot3Reveal`, row flights and `animateItem` specs (3.4.3; tokens.md 5) | UI-1 |
