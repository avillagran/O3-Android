# Desktop parity status — P20 second wiring pass

Checked against Android `origin/main` 09454b0 (2026-10-31), pinned lib-t3-rust
v0.3.5/d4bf6054, and the read-only Desktop `feat/o3-brand` audit at
`/home/node/.cache/o3-android-artifacts/desktop-parity-audit.md`.
This replaces the audit's historical package planning status, not its 609 source records.
Done means the package's full requested scope is complete; Partial means shipped behavior
with remaining work; Blocked means the package has no delivered reachable module.
A passed fixture is not evidence of a live host mutation or complete Desktop parity.

| Package | Status | Reachable / delivered | Exact blocker repository and required change |
|---|---|---|---|
| P01 Header / branding | Done | O3 glyph variants, motion policy and sidebar style reach Home, including landscape. | None for the delivered header package; see `o3-header-animation.md`. |
| P02 Preferences shell | Partial | Search, appearance/local controls and contributed pages. | **O3-Android**: implement editable Keyboard shortcuts and pre-API-33 locale context wrapping. **lib-t3-rust**: update shared catalog for newer Desktop labels (P19). |
| P03 General / scoped preferences | Partial | Explicit host/project scopes, native descriptors, errors and writes; composer preferences consumed. | **O3-Android**: complete structured descriptor editors and acceptance for all server-advertised schemas; retain native validation, mixed/reset semantics. |
| P04 Sidebar layouts | Partial | Classic/Focus/Projects, chips, clocks, row styles; P05 named groups now feed `SidebarMetadata` rather than unused preferences. | **O3-Android**: pass complete independently filtered native arrays into alternate layouts; consume physical repository/path/checkout grouping and pinned-ready hold; streaming-answer clocks. **lib-t3-rust**: release normalized physical grouping/member identity contract if absent from the pin. See `ui/home/layouts/README.md`. |
| P05 Thread / project menus | Partial | Thread mutations/recovery, same-branch creation, project settings, copy path/branch/ID, persistent environment-qualified thread/project named groups. | **lib-t3-rust**: release project metadata mutation builder, pin-order builder and reference serializer. **O3-Android**: physical grouping requires normalized native identity; t3.json provenance/structured project editors remain. Unsupported rename/remove/reorder controls are hidden. See `ui/settings/projects/README.md`. |
| P06 New chat / composer | Partial | Project picker/draft, branch recipe, editor, uploads, explicit outbox retry/discard and opposite delivery. Shared Git admission now covers dispatcher writes. | **lib-t3-rust**: U33 transformations/shared project draft/history contracts. **O3-Android**: background/send-and-new receipt integration, schedule creation recipe handoff and missing-project import creation callback. No local payload substitutes. |
| P07 Models / providers | Partial | Native model/options/runtime choices and host-scoped provider administration. | **lib-t3-rust**: U29 permissionView, U34 bounded custom icons, provider safe projection/mutation/default inheritance builders. **O3-Android**: generic setup subscription adapter for continuous progress. See `core/providers/P07_BINDINGS.md`. |
| P08 Transcript | Blocked | Existing reader only; PR88 remains separate and was not touched. | **O3-Android PR88**: merge the transcript contribution and its fix before integrating reader actions/history/secret/subagent slots. **lib-t3-rust**: U18/U35 and terminal response eligibility still need the released contracts described by that contribution. |
| P09 Media / visual Markdown | Partial | Binary images/media, gallery, native code highlighting/copy and math; shared rendering settings. | **O3-Android**: provide bounded offline Mermaid engine. **lib-t3-rust / omarchy-t3-code host**: document/QML/HTML runtime and scoped interactive frames; P14/P15 action consumers. See `ui/thread/rendering/P20_INTEGRATION.md`. |
| P10 Tool inspector | Partial | Details/metadata/raw, bounded text, truncation, explicit reload. Reader owner and exact connection reject stale completion. | **lib-t3-rust**: U36 incremental stdout/stderr/progress subscription and scoped event projection; **O3-Android** consumes it once shipped. Static item output is not live output. |
| P11 Git / checkpoints | Partial | Native working/branch review, commit/push/pull/create-PR/composites/scripts, durable journal and shared dispatcher admission; native text-only checkpoint preview/confirm/read-back/recovery, optional acknowledged file restore in a host worktree. | **O3-Android**: atomic attachment-byte/descriptor plus draft recovery transaction/preflight; checkpoint messages with attachments/structured context remain hidden. Add whole-OS-process-kill/live-host recovery acceptance. **lib-t3-rust / omarchy-t3-code host**: execution/terminal exit observer and cross-client status CAS; scripts stay pending until explicit verified reconciliation. Active effects serialize process-wide; unresolved receipts exclude their host. O3-Android must narrow scheduling to verified checkout membership. See `ui/git/P11_INTEGRATION.md`. |
| P12 Pull requests | Partial | Read views plus native comment/review/merge/close/reopen writes; unknown-write receipt persists across route re-entry/process restart. | **lib-t3-rust**: U31 native pagination merge and authenticated PR diff binding. **O3-Android**: stack badge selector, link/unlink/watch/unwatch command/receipt UI, inline review/reviewers/labels and verified unknown-receipt reconciliation. See `core/pullrequests/P12_INTEGRATION.md`. |
| P13 Connections / OmaBridge | Partial | Saved environments, pairing/enrollment and SDK identity. | **avillagran/omabridge / lib-t3-rust**: release per-peer consent, all-host invitation issuance, approvals/publishing/relay/gateway administration and route/share preferences. **O3-Android**: bind only advertised administrative contracts. See `ui/settings/connections/P13-SDK-GAPS.md`. |
| P14 Browser / documents / devices | Blocked | No merged `ui/workspace` page. Integrations/visual-runtime entrypoints hidden. | **omarchy-t3-code host + lib-t3-rust**: authenticated bounded browser/document/device sessions, frames/input and U35 scoped references. **O3-Android**: implement exported workspace/integrations module, then navigation consumers. |
| P15 Terminal | Blocked | P11 can deliver a native script; no terminal reader page. | **lib-t3-rust / omarchy-t3-code host**: authenticated PTY read/write/resize/reconnect contract and exit observation. **O3-Android**: implement terminal page, ANSI/UTF-8/IME and code-run consumer. No phone-local shell. |
| P16 Dictation / screenshots | Blocked | No merged capture page. | **O3-Android**: implement consent/permission/cancel/recovery and attachment callbacks; **omarchy-t3-code host + lib-t3-rust**: expose host transcription/model lifecycle/capture when choosing the remote path. |
| P17 Memory / maintenance | Partial | Explicit-host Storage policy/report/manual cleanup, Archive restore/delete, schedules editing/enable/run/delete, existing-project session import and process/history/signal pages. | **omarchy-t3-code host + lib-t3-rust**: mobile contracts for idle-release/automatic-compaction workers, vault rollout/sync/conflicts, host-sync and local build cleanup/low-disk monitoring. **O3-Android**: real P06 creation recipe for new schedules/missing-project imports and host-assisted receipt reconciliation. Memory/vault/host-sync and absent creation callbacks are hidden. See `ui/settings/maintenance/P17_GAPS.md`. |
| P18 Usage / notifications | Partial | Usage periods/totals, individual quota reports/refresh; four notification modes, batched sound, channel/permission handling already wired. | **lib-t3-rust**: U26 quotaSources.present with exact pools/earliest reset/pace/ambiguity/reset-credit redemption. **O3-Android**: Desktop chime assets and Doze/OEM/DND/live-auth acceptance. See `ui/usage/P18.md`. |
| P19 Translation / licenses / accessibility | Partial | Generated 261×20 catalog plus hand-kept translated package resources. | **lib-t3-rust**: refresh i18n catalog for Desktop additions. **O3-Android**: deliver licenses/About module and complete RTL/TalkBack/text-scale acceptance. Licenses remains hidden. |
| P20 Wiring / acceptance | Partial | This pass integrates merged P10/P12/P17/P18, local named groups, Git admission, native Git writes and text-only checkpoints. | **O3-Android**: finish attachment checkpoint transaction, physical grouping adapter, P12 thread/stack command UI and P06 maintenance creation callbacks. **Other repos**: dependencies above. Emulator fixtures do not close real authenticated multi-host mutation acceptance. See `p20-wiring-2.md` for measured results and exclusions. |

## Hidden entrypoints and causes

- Add project / command palette: no delivered Android page/creation owner.
- Memory, vault, host sync, Desktop compaction/idle-release and build cleanup: no mobile host contract.
- Licenses/About and editable shortcuts: Android modules not delivered.
- Physical repository/path/separate grouping: missing consumed native physical identity/member adapter;
  do not expose controls that only persist unused values. Named groups are available.
- Project rename/remove, pin reordering, copy reference: missing released safe native builders.
- New scheduled task and import requiring project creation: real P06 recipe/callback not wired;
  existing tasks and existing-project imports work.
- Checkpoint source messages containing attachments or structured context: no atomic attachment-aware recovery yet.
  Text-only rollback never claims to recover attachments. New attachment imports are blocked
  while this thread has an unresolved rollback.
- Live tool output, authenticated PR file diff, inline review, PR linked-thread/stack actions:
  contracts/UI blockers in P10/P12. No inert link points to a placeholder file page.
- Mermaid, QML/HTML, browser/device/terminal/code-run/capture: absent runtime or page consumers.
- Composer history/shared drafts/transforms/plan/background/send-and-new: P06/P08 contracts
  or command/receipt integration absent. No automatic replay is introduced.

Unsupported route types remain for serialized compatibility and navigation test fakes;
production menus expose only real modules. Archive now dispatches a real maintenance page.
