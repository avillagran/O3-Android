# P17 route contributions

Register `MaintenanceRoutes.pages` plus `StorageSettingsRoute` and
`ArchiveSettingsRoute` in P20. `MemorySettingsRoute.available` is false: omit memory, vault
and host-sync destinations until their host contracts exist. No unavailable placeholder route
is contributed. Titles are the hand-kept `maintenance_*` resources.
No navigation, AppGraph or Gradle files are changed here.

Construct one `MaintenanceController` for each immutable `(environment, canonical origin,
authenticated viewer, page)` binding. Resolve its transport explicitly, never via a mutable
selected-host fallback. Dispose the destination before disconnecting/rebinding that transport.
`MaintenanceScreen` closes its controller on disposal; create a fresh one on re-entry.
Create `FileMaintenanceReceipts(File(context.filesDir, "maintenance-receipts"))` once and share
it with every maintenance controller and `StoragePolicyController`. Keep that directory private.
Never remove an unresolved receipt to enable a retry: inspect the authoritative host first.
No automatic recovery/replay is provided. Receipt files contain the exact operation input, never session credentials.

Storage has two composables: `StoragePolicyScreen` (native environment or explicit-project descriptors through
`ScopedSettings`) and `StorageSettingsRoute.Content` (last report/manual run). Both use the same
receipt namespace so an uncertain policy or cleanup blocks further storage effects. Supply projectId to StoragePolicyController for a project-specific policy. Recreate or
refresh both when switching tabs. P20 can put them in separate destinations or tabs.

Schedules list/edit/enable/run/delete are remote. Supply `newTaskRecipe` to `MaintenanceScreen`
from P06's selected project/model/workspace recipe to expose creation. Required fields are
`projectId`, `modelSelection`, `workspaceStrategy`, `runtimeMode`, `interactionMode`, `enabled`,
`schedule`, `title`, `prompt`. New IDs are minted once per submission and persisted before send;
existing edits use `id` plus `requireExisting`. Rust validates the typed payload. Interval minutes, fixed time, weekdays, schedule type, workspace strategy/base/path
are editable. Existing webhook policy and additional workspace flags are retained.
Provide onImportProjectRequired(path) to connect missing-project imports to P06; after creation,
refresh the scan and let the person confirm import into the now-existing project. No Android
alarm, worker, compaction engine or timer is registered.

Archive commands re-read the archived snapshot before sending, reject changed rows and require
post-dispatch archive/main-shell convergence before clearing their receipt. A delayed snapshot is
an unknown outcome, never success. Delete does not request worktree removal. Diagnostics use
`pid` plus `startTimeMs` for SIGINT/SIGKILL; each action requires explicit confirmation.

All reads/writes are registry- and auth-scope-gated; server errors leave a stale report and hide
mutation controls. The SDK performs typed argument validation; native scoped settings additionally
validate storage capabilities. Retain the visible selected-host identity in the enclosing page.
