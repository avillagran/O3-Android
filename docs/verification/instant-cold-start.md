# Cached cold start

## Findings

HomeStateBuilder already accepts a shell while Connecting/Disconnected. ConnectionManager
already reads the sidebar with a locally derived authority before starting the transport;
ThreadSession already marks restored projections stale. Connected-state gating in Home
was not the cause of an empty cached list.

There were several independently reproducible gaps:

- AppGraph owned sidebar persistence, after a successful native sidebar.view. A failed
  presentation skipped the write, and adding/replacing a host cancelled and recreated
  every observer via collectLatest. Persistence now belongs to each supervisor's manager
  lifecycle and does not depend on native presentation or Home subscribers.
- Pinned transcript entries outranked the sidebar during eviction. Enough transcripts
  removed the startup index even while useful cached chats remained. Startup indexes
  now precede pinned transcripts within the existing shared limits.
- Home combined its list with a delayed header flow that emitted nothing for the first
  800 ms when starting in Reconnecting. The header now immediately emits Hidden, keeping
  its existing 800 ms text debounce without holding up the list.
- Reader cache restoration waited for both upstream watchers to emit. Those watchers now
  start with a local null value. Restoration was also marked initialized before its
  suspending presentation finished: collectLatest cancellation could prevent publication
  without retrying it. Initialization now completes after restoration.

The new regression tests fail against the previous implementation. They cover two-host
persistence/restart with descriptors blocked, eviction under pinned transcript pressure,
header startup, stale rows with an incorrectly retained writable action context, a reader
whose watchers have not emitted, and interruption during cached reader presentation.

## Safety and bounds

No cache schema or payload fields added: the same shell projects, at most 2,000 shell
threads, and existing bounded transcript snapshots are stored. Defaults remain 200 MiB,
128 total entries, 8 MiB per entry and seven days. Index priority can evict transcripts
so the total budget stays unchanged. Credentials are not stored in the cache; authority
checks and isolation on route/token replacement remain in place. A manager writer is
bound to its original authority and stopped/joined when that supervisor is forgotten.
Only connected shells refresh disk age. Cached reader content remains stale until a
validated live update; existing canSend/interrupt guards require a live, non-stale reader.
Home additionally refuses offline swipes even with a retained writable action context,
and management refuses a supervisor that is not Connected.

## Connection delay boundary

The Kotlin initial attempt launches independently for each environment. It performs
fetchDescriptor, obtains the local token, calls connectBearer, obtains the initial shell,
then publishes Connected and opens the shell stream. Cache restoration does not populate
the live held shell/cursor, so a cached list never substitutes for authentication or live
reconciliation. There is no initial 16 s/50 s timer or cross-host sequential network wait
in this path. The 800 ms header gate above was an avoidable local display delay.

Descriptor and shell I/O delegate to the pinned lib-t3-rust JNI adapter, whose network
implementation is outside this repository. The measured Xiaomi delays cannot be assigned
to descriptor, shell, route discovery, native transport or host processing from the
available evidence. No physical phone was accessed, and no production connection was
probed. The changes prove cache-first ordering, not a new real-device connection-time
benchmark. Native/host phase timing remains necessary to explain the complete 16 s/50 s.

## Verification

Use Java 21 and the local SDK, with --max-workers=2 and -Pot3a.requireBridgeSdk=true.
See the PR for the final command results. No emulator is needed for the deterministic
ordering, authority, staleness, JNI projection and screenshot checks. OEM force-stop
latency acceptance remains a device test.

Validated on NODE, 2026-11-01:

- `git fetch origin` and branch creation from `origin/main`: exit 0.
- Five new regression cases against the previous production files: exit 1, all five fail
  as expected. The additional interrupted-restore case also fails against the previous
  ThreadSession: exit 1. Production files were restored after each comparison.
- Targeted cache/connection/header/Home/reader suite after the fixes: exit 0.
- `:core:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug
  :core:lintDebug :app:lintDebug :app:assembleDebug`: exit 0 (1m36s).
  Core: 534 passed, 5 skipped. App: 566 passed, 1 skipped. All 220 screenshot records
  unchanged. Lint: 0 errors, existing warnings (core 2, app 111).
- Final full core rerun after restoring the interrupted-restore baseline comparison:
  exit 0 (matching successful build-cache result). The final source matches the
  successful full build above.

All Gradle commands used `JAVA_HOME=$(mise where java@temurin-21)` and
`ANDROID_HOME=$HOME/.android-sdk`, `--max-workers=2 -Pot3a.requireBridgeSdk=true`.
No incremental cache error occurred, so disabling Kotlin incremental compilation was
unnecessary. No terminal sources, other checkout, emulator or physical phone were used.
