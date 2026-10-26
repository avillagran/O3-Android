# Bridge catalog synchronization — implementation in progress

Owner requirement, 2026-10-26: pairing Android with the desktop's bridge must discover
and synchronize authorized environments, including NODE, rather than pairing each host
manually. This is a functional acceptance condition, independent of UI6/indicators.

## Proven observations (read-only, 2026-10-26)

- On Omarchy, the installed `omarchy-t3-client connections-view` returns an enabled
  `node`, UUID `8b55eaad-3149-443c-953f-afd6dd002666`. Its only saved route is
  `SshConnectionTarget` to `192.168.1.177`, user `node`, port 22. The command's
  documented sanitized view was used; no desktop credentials were exported.
- Public `/.well-known/t3/environment` on local Node (`127.0.0.1:8643`) and its
  existing public gateway (`stream.villagranquiroz.cl:39670`) both return that UUID
  and label `node`. The gateway is reachable now. `/api/auth/session` on it returns
  `authenticated:false` without credentials. Reachability is not authorization.
- Android `800c33e` saves independently paired hosts in Room and uses their per-host
  Keystore credentials (`EnvironmentRepository.save`, `ConnectionManager`,
  `ConnectionSupervisor`). There is no remote catalog ingestion in this path.
- ot3 reads `~/.t3/userdata/connection-catalog.json` through its own native encrypted
  catalog adapter (`src/connections.rs:30-46`); `connections_management.rs` exposes
  `connection_catalog::view` without credentials. Bridge forwarding is chosen
  separately (`src/omabridge_route.rs`). An SSH entry is not a portable Android URL.
- Installed-development OmaBridge source on Omarchy has newer uncommitted `routes.rs`
  absent from release 0.2.1: service URLs are announced via authenticated Hello, filtered
  by current public-service grant and expire after 600 seconds. `for_peer` exports only
  the receiving owner's gateways. This is useful route discovery, not redistribution of
  ot3's catalog or a T3 grant for other environments. Existing dirty work was not changed.
- Physical evidence from the coordinator labels rows VQ. We have NOT inspected the
  phone's Room/catalog/URL, and cannot prove its actual bridge identity or NODE filter.
  VQ is a display label, not evidence of target UUID. The user retains the phone.

Thus the missing import path is proven in source, and NODE's desktop identity/public
route are proven live. A specific phone-side permission/filter failure is NOT diagnosed.
No servers, services, grants, gateways or production environment configuration changed.

## Concrete implementation

The companion lib branch `feat/bridge-catalog-sync` adds common Rust functions
`connectionCatalog.publishBridge` and `connectionCatalog.reconcileBridge` (see its
`docs/BRIDGE_CATALOG.md`). Export receives only a sanitized desktop view, the
**authenticated client's explicit environment allowlist**, and descriptor-verified
portable endpoints. Import binds bridge/client, rejects loopback/secret-bearing routes,
revision rollback/reuse and expiry, and retains withdrawn identities as route-free
tombstones. Malformed/offline input never becomes a deletion.

Android `core/bridge/BridgeCatalogSynchronizer` calls that Rust contract and owns only
refresh serialization/state. `FileBridgeCatalogCache` atomically persists a bounded,
private cache per bridge/client. Cancellation propagates; failed refresh preserves
cached identity. These classes cannot delete local environments, tokens, drafts or
cursors. Tests check boundary arguments, offline/rejected cache preservation, concurrent
refresh ordering, cancellation, disk recreation/isolation and oversized-write rollback.

**Not yet wired to production pairing or Room.** The pinned AAR v0.3.4 does not expose
these new functions until a companion lib release is integrated. No unsupported call
is made by current production screens. Source interfaces and isolated tests are not a
claim that NODE now appears on the phone.

## OmaBridge transport increment

A separate root-owned clone `omabridge-catalog`, branch `feat/client-catalog`, now adds
an authenticated QUIC `catalog` request and `Client::catalog`. The existing iroh
identity supplies the client id; current T3 service grant AND a separate explicit
environment allowlist are required. The local `catalog::publish_source` API atomically
publishes sanitized metadata/routes/recipient grants. No public endpoint is added.
This is not the desktop native publisher, Android JNI client or T3 enrollment yet.
Its real-loopback QUIC tests are tracked with the companion PR; no productive state
is changed and no native desktop tokens are exported.

## Remaining end-to-end work and acceptance

1. OmaBridge authenticated catalog transport: bind the paired client's identity and
   explicit environment grants. A raw T3 bearer for VQ must not enumerate NODE merely
   because desktop ot3 can reach it. See OB5 in OMABRIDGE_REQUESTS.
2. Desktop publisher obtains its native sanitized view, maps local-primary aliases to
   stable descriptor UUIDs, verifies route descriptors, and publishes changes with a
   monotonic per-recipient revision. Never share the encrypted catalog or tokens.
3. Enrollment/delegation: issue a separate bounded T3 session/capability per authorized
   client/environment, with revocation/lifetime/scope enforced. Desktop private tokens
   remain desktop-side. Discovery alone creates no permission.
4. Release/pin shared lib; authenticated source adapter and lifecycle refresh/watch;
   apply managed rows by stable environment identity/provenance without overwriting
   independent local entries. Preserve drafts/selection, show offline/revoked clearly,
   stop affected connections on proven grant withdrawal (not a mere outage).
5. Isolated VQ+NODE end-to-end test: catalog add/rename/remove/recover, route change,
   denied client/cross-environment authorization, reconnect/cache and host-specific
   thread shells. Then read-only live validation on authorized hosts; physical check
   only when Andrés allows it. Do not equate metadata-only unit tests with thread sync.

The work remains open until this end-to-end path is implemented and verified.

## Reviewable increments and verified checks

- Shared lib [PR11](https://github.com/avillagran/lib-t3-rust/pull/11),
  `027a025`: six focused contract/ABI tests and the complete `cargo test --locked`
  pass; CI native x86/ARM, Kotlin and AAR checks green when checked.
- Native bridge [PR2](https://github.com/avillagran/omabridge/pull/2),
  `82acc55`: two new tests include actual loopback QUIC with separate temporary
  device identities. VQ+NODE restricted catalog, withdrawal, route change, restoration,
  offline/restart/reconnect and revocation pass. Full `cargo test --locked --all-targets`,
  Clippy with warnings denied and format check pass. An initial pre-linger response
  was truncated; that result is discarded and the corrected full suite is green.
- Android [PR24](https://github.com/avillagran/ot3-android/pull/24): six focal JVM tests
  and `:core:lintDebug` pass. Those tests verify the host adapter/cache, not live JNI
  calls into the new unreleased contract. No AAR/host binary shared with UI2 changed.

The prior OmaBridge project thread link could not be read by this T3 server
(`thread_not_found`); coordination is through these concrete companion PRs and Dev Vault,
not a claim that an unavailable remote thread received a message. No new top-level
thread was created. UI2 continues independently with the same gpt-6.1-sol delegate.
