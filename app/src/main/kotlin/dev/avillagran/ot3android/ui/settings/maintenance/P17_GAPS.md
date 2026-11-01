# P17 contract coverage and remaining gaps

Reference: desktop-parity-audit.md P17; inventory 114–120, 178–182, 229–245,
T3AgentMemorySettings, T3CompactionSettings, T3JournalSettings, T3StorageSettings,
T3Archive, T3AgentSessions, T3ProcessDiagnostics, T3ScheduledTasks and T3HostSync.
Desktop reference branch feat/o3-brand; mobile AAR v0.3.5 / d4bf6054b4480efdcd8a436cb66cb7f2e23664b1.

## Available remote paths implemented

- Storage environment/project policy descriptors/validation via native scopedSettings and
  server.getSettings/updateSettings; retention, keep conditions and worktree location.
- diagnostics.storageCleanupReport and server.runStorageCleanup; last counts, bytes, timestamp,
  bounded per-entry errors, manual confirmation. No host filesystem traversal on Android.
- orchestration.getArchivedShellSnapshot and typed orchestration.dispatchCommand with
  thread.unarchive/thread.delete; host/viewer-bound receipts and row revalidation.
- agentSessions.scan/import; import into an existing project with expectedWorkspaceRoot.
- server.getProcessDiagnostics/signalProcess and getResourceTelemetryHistory; process tree,
  CPU/memory history and PID/start-time-safe interrupt/kill.
- scheduledTasks.list/upsert/setEnabled/delete/runNow; host scheduling only.

## Desktop broker consumer (2026-11-01)

Memory, vault, host-sync and Storage → Desktop host now use `service-request-v1`
with the separately granted `desktop-snapshots` service. This is not a T3 RPC.
Read reports cover local memory policy, persisted worker status, configured vault,
host-sync and exact build-cleanup candidates. Policy changes, configured-vault
instruction rollout and build cleanup require the host-prepared full report and
exact request followed by a single-use 60-second token. Unknown outcomes are never
automatically retried. The host owner enables both consents locally.

Still absent: immediate compact/release, provider-specific policy editors,
cross-host vault rollout/create/clone/pull/push/conflicts, host-sync apply/login and
low-disk monitoring. Existing server storage cleanup is distinct from Desktop
build cleanup. Native defaults remain null in reports; users explicitly enter
policy values before preparing a change. No phone-side worker substitutes for a
host worker. See `docs/verification/host-surfaces.md`.

## Integration and runtime follow-ups

- Schedule creation needs P20/P06's real selected project/model recipe. Interval/fixed-time,
  weekdays, webhook and root/new/existing-worktree editors are included. Existing webhook policy
  and workspace flags are preserved. Model/runtime values come from the caller's real recipe.
- Import of a scanned folder without an existing project invokes the exported
  onImportProjectRequired callback; P20 connects it to P06, then refreshes the scan.
- Unknown outcomes persist and block effects across restarts. This contribution deliberately
  does not clear receipts from report refresh or replay unrepeatable cleanup/run-now actions.
  Automated receipt reconciliation/host-assisted resolution remains follow-up work.
- Real-host behavior, vault conflict acceptance, device layouts,
  accessibility and RTL acceptance have not been exercised. No phone or original Android
  checkout is accessed. This package is partial parity; unavailable surfaces are not completion.
