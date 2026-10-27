# Credential ownership of saved thread projections

PR56 follow-up, 2026-10-27. The previous cache key used server identity and saved
origin only: replacing a credential at the same URL could restore another credential's
projection. `ThreadCache.remove(lease)` also omitted the ownership check already used
by read/write/close, letting an older reader delete a replacement's entry.

EnvironmentRepository now resolves one local cache authority under its existing
`mutations` mutex: local environment ID, saved route and credential are
hashed together with SHA-256 and a versioned domain separator. The high-entropy bearer
credential is used only transiently in memory; only the digest leaves the method and
is stored in the cache record. No token is added to Room, logs, filenames or fixtures
containing real credentials. This digest is local isolation metadata, not an
authorization decision or a replacement for encrypted credential storage.

The method reuses `tokenLocked`, the exact managed-status/expiry gate used by token().
A missing environment, absent/blank credential or unavailable credential storage
returns null. A read cannot observe half a route/credential mutation. Hashes remain
stable across repository/cache instances and metadata-only label/markConnected updates.
The advertised server UUID is deliberately metadata: ConnectionSupervisor publishes
the connection before onConnected learns a previously unknown UUID. The local saved
ID separates entries, the saved route binds the endpoint, and the credential binds
access. A same-route/same-credential backend replacement with a different advertised
UUID is therefore the same local cache authority; independent UUID isolation is not
claimed. Saved projections still require native stream/snapshot revalidation.
Legacy origin-only cache records fail the new authority comparison and are discarded
when read; no database migration or draft/outbox reset is required.

The open session observes authority changes independently of connection changes via
existing Room environment/managed-gate observations and a local credential mutation
revision. The revision also covers identical-row writes that a StateFlow fake would
coalesce. Distinct authority values prevent metadata-only stream restarts. This
subscription opens no transport or network stream and does not observe saved entries
that have no open session.

A change or withdrawal clears the held projection/cursor/history even when connection
is already null or native sidebar membership prevented a cache lease. The old observed
Connection identity is blocked until replaced, so its payload cannot be saved under
the new credential's digest. Cache restore checks ownership again after disk I/O and
before publication; persistence rechecks before writing. An old lease cannot delete,
write or close a replacement's record. Immediate close→start waits for the old job's
final flush/close, and restore retains its own lease reference if publication invalidates
shared fields. Drafts, outbox and reading-position preferences remain separate.

## Limits

There is no new expiry timer, polling loop or protocol/authorization policy in Kotlin.
Expiry is checked when the existing gate is read (restore, input/connection changes,
publication and persistence); Room/bridge signals invalidate a withdrawn gate. Mere
passage of the clock with no input/read is **not** a tested immediate invalidation.
Remote revocation without a locally observed bridge/connection signal is not separately
accepted. This is not secure erasure of every unused cache file on the device; an
unavailable authority cannot restore it, and mismatching records are removed on read.
U18 historical merging and server cache policy remain unchanged.

## Verification

Core focused suite: 42/42 passed, zero skipped, using host JNI for native timelines.
It covers same-URL credential replacement with a newly constructed repository/cache,
metadata stability, coherent mutex reads, managed gate withdrawal/expiry reads,
unavailable storage, first-connection null→known UUID enrichment without blocking,
null→null offline invalidation, old Connection blocking, no-lease
memory invalidation, stale-lease deletion and serialized session restart. Existing
ThreadSession, cached-resume/fallback and native sidebar eligibility cases also pass.
App CachedThreadActionsTest passed 1/1; debug app/test builds and lint passed.

The device regression uses WorkspaceFixture synthetic transport and actual JNI on
API34 emulator-5580. It exercises offline reopening, row/offset within 1px, retained
draft, zero extra snapshot reads, reconnect resume, rotation/recreation and a fresh
fixture VM/cache/Room wrapper with network disabled. Its fixture uses a synthetic
cache authority; credential mutation is covered by the core integration tests, not
claimed as device credential-pairing acceptance. No physical device, production token,
server mutation or Android process-death experiment is involved.


The session cannot attest an arbitrary previously unobserved standalone Connection's
credential. Production handoff paths already retire the old supervisor: ConnectViewModel
awaits the injected connectionManager.forget before publishing savedEnvironmentId;
pairing stops before save and forgets at handover; bridge enrollment stops before its
credential write. Direct repository mutation followed by injection of an old Connection
into a newly created session is outside that lifecycle invariant. No new connection
protocol or binding field is introduced in this increment.


[API34 offline screenshot](authority-device/offline-api34.png) and
[verification record](authority-device/verification.json) belong to the successful
1/1 device run (7.51s) on31e0250. The final UUID-enrichment key refinement is covered
by the final42 core cases; the device fixture's constant authority did not change.
No extra device rerun is presented as additional credential coverage.

Reproduce the device fixture with the installed debug/test APKs:

```sh
adb -s emulator-5580 shell am instrument -w -e cacheEvidence true \
  -e class dev.avillagran.ot3android.ui.thread.ThreadCacheDeviceTest \
  dev.avillagran.ot3android.test/androidx.test.runner.AndroidJUnitRunner
```
