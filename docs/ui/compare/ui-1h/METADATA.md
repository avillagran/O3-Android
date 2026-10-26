# UI-1h metadata provenance

Read-only audit, 2026-10-25. Local sources: `/home/node/Work/omarchy-t3-code-ref`
and `/home/node/Work/lib-t3-rust`. No changes to either repository.

| Visible value | Source and mapping |
|---|---|
| Environment / HOST label | `sidebar.view` row `environmentName`: `src/sidebar/view.rs:136` assigns the caller's environment name. Android `HomeStateBuilder.view` supplies `HomeEnvironment.label` from its saved environment catalog. Shown for one or several environments; never guessed from a project or branch. |
| Branch | `src/sidebar/view.rs:144`: the server shell thread's `branch`, mapped by `SidebarView` → `HomeRow` → real Home card/slim row. No git calls or branch inference. |
| Status and time | The existing `sidebar.view` `detail/statusLabel/statusRole/time`, translated at the presentation boundary. |
| PR | `src/sidebar.rs:62` metadata includes `pullRequestBadge`, derived by Rust `src/pull_requests.rs:82-251`. Android passes its `state/text/kind` to `PrBadgeModel`. No links, counts or state are inferred in Kotlin. Null stays absent. |

Desktop reference: `ui/qml/T3ThreadRow.qml:237` shows project/environment;
`:317-318` branch/worktree; `:333` exposes project, machine/environment and branch
in metadata. Android deliberately keeps environment and branch visible on slim
rows too, per Andrés's requirement. It does not fabricate desktop `machineIcon`:
`sidebar.view` does not expose an authoritative host OS/machine icon (see
`LIB_REQUESTS.md`, UI-1h). The label identifies the saved environment, not an
OS claim. Saved display labels can be renamed by the user.

Evidence uses a debug-only, explicitly offline fixture. It runs the real navigation
graph, HomeScreen, ThreadScreen, HomeViewModel, ThreadViewModel, Room and Rust domain
views with `FakeT3Transport`; it contains no credentials or external network calls.
It is separate from normal app data and does not claim live-server QA.
