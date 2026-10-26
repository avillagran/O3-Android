# Remote environment authority — 2026-10-26

## Current evidence

[Read-only preflight](evidence/2026-10-26/preflight/verification.json) confirms:
NODE is enabled in the desktop's sanitized catalog with one saved route. The local
NODE descriptor and its existing portable HTTPS gateway agree on its UUID. Installed
OmaBridge is 0.2.1 and does not expose `catalog-publish`. No grants, services, catalog
entries, sessions, tokens or desktop source were changed by this inspection.

The Android application contains the native catalog client. Its installed build
f90f7c1 reads VQ usage successfully on the Xiaomi; this is the existing VQ
connection, not evidence of automatic NODE discovery or its catalog provenance. No phone identity/token store
was exported. The desktop has concurrent source modifications and is not a deployment
checkout for this task.

Run `python3 -I scripts/bridge-preflight.py --help` for the required explicit host,
executable paths, expected UUID and origins. The tool invokes only `connections-view`,
`--help`, `--version` and public descriptor GETs. No redirects/proxies, credentials or
arbitrary remotely supplied commands. JSON contains only requested-identity metadata;
stderr and raw remote output are never printed. A zero exit means the observation
completed, **not** that discovery or authorization works. Unknown/unavailable is
separate from an authenticated absence. Four focused standard-library tests run via
`python3 -I scripts/test_bridge_preflight.py`.

## Confirmed implementation gap

OmaBridge candidate f7d93a9, `src/enrollment.rs:160-285`, implements
`LocalT3Authority`: a fixed local T3 executable, owned local base directories and a
literal-loopback descriptor. NODE's T3 is on a different host from the desktop.
Deploying this candidate alone therefore cannot issue NODE credentials there.

`EnrollmentAuthority::issue(client, environment, now)` and
`Node::start_reserved_with_authority` are suitable embedding points. Normal
`Client::enroll` is **not** a delegation API: the remote node authenticates the
caller as the desktop bridge and binds the receipt to that identity. Substituting
Android's client id afterward would invalidate that boundary.

## Isolated implementation plan (OB6)

- A desktop composite authority maps environment UUID to a pinned issuer peer.
  Local environments continue using the existing LocalT3Authority.
- A distinct QUIC `DelegatedEnroll { environment_id, client_id }` request requires
  an authenticated upstream bridge AND an explicit host-side delegation policy:
  upstream peer, environment, downstream Android endpoint ids, TTL ceiling and
  exact `orchestration:read` scope. No inferred delegation from admin/t3/catalog.
- The issuer runs LocalT3Authority on NODE. Its binary, base directory and loopback
  descriptor remain NODE-local. Fresh bounded credentials cross the authenticated
  NODE→desktop→Android path; existing desktop tokens never enter it.
- Validate client/environment, descriptor, scopes and expiry at each boundary.
  Recheck grants after awaited issuance. Default denial, bounded concurrency and
  rate limits; no commands, paths, routes or URLs accepted from clients.
- Reuse the running bridge endpoint/control transport; do not spawn a second iroh
  endpoint with the same persistent identity. Android pairs once with the desktop.

Trust limit: the desktop becomes a trusted broker capable of requesting issuance
for explicitly allowed downstream ids. These are bearer sessions, not proof of
possession. Removing delegation prevents future issuance but does not immediately
revoke an already issued T3 bearer. Promise only its bounded natural lifetime
(initially at most 120 seconds) unless a persisted issuance/revocation ledger and
T3 revoke hook are implemented and verified. Do not mislabel local disconnect as
server-side revocation.

Required isolated tests: two bridges plus actual isolated T3; cross-upstream/client/
environment denial, excessive TTL/scope, wrong descriptor, default denial, offline
without withdrawal, policy revocation during issuance, no replay/error-secret leak,
write denial and natural expiry. No productive deployment is part of this plan.

The remaining deployment step requires explicit operator configuration on each issuer
host and the desktop publisher. It must preserve existing listeners, grants and local
Android environments. Preparing code/tests does not authorize replacing the dirty
production checkout or publishing new services.
