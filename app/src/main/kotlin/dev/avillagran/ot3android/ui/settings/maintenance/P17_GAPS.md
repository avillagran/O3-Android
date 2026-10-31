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

## No mobile remote path in the pinned contract

- Desktop idle_release worker configuration/status, automatic compaction engine configuration,
  provider lifetime overrides, token threshold, exclusions, claim state and deadlines. These use
  Desktop view-state/local workers. Existing General `idleCompaction` is a distinct server setting.
  No phone-side scheduler or invented settings patch substitutes for those workers.
- Development vault create/clone/adopt, identity, remote, rollout/repair, instruction installation,
  pull/push and conflicts. Desktop journal_vault uses local Git plus SSH and local state. No mobile
  vault RPC/projection is advertised. The vault route is omitted; Android never claims
  a clean or synced vault and has no conflict observation to present. A real conflict
  presentation still requires a host contract and remains unverified.
- Desktop host_sync preview/select/apply/auth and per-item conflict/backup results. It transfers an
  allowlisted archive over SSH from Desktop. No pinned mobile host-sync operation exists.
- Desktop local build-output cleanup policies/runs/reports and low-disk threshold monitor.
  server.runStorageCleanup is the server worktree/artifact/log cleanup, not Desktop build cleanup.

## Integration and runtime follow-ups

- Schedule creation needs P20/P06's real selected project/model recipe. Interval/fixed-time,
  weekdays, webhook and root/new/existing-worktree editors are included. Existing webhook policy
  and workspace flags are preserved. Model/runtime values come from the caller's real recipe.
- Import of a scanned folder without an existing project invokes the exported
  onImportProjectRequired callback; P20 connects it to P06, then refreshes the scan.
- Unknown outcomes persist and block effects across restarts. This contribution deliberately
  does not clear receipts from report refresh or replay unrepeatable cleanup/run-now actions.
  Automated receipt reconciliation/host-assisted resolution remains follow-up work.
- P20 route registration, real-host behavior, vault conflict acceptance, device layouts,
  accessibility and RTL acceptance have not been exercised. No phone or original Android
  checkout is accessed. This package is partial parity; unavailable surfaces are not completion.
