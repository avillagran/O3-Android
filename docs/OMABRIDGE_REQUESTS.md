# Requests to OmaBridge

This file collects what ot3-Android needs from
[OmaBridge](https://github.com/avillagran/omabridge) to treat it as a connection route
and to manage it from the phone (UI_SPEC 4.2.2 and 4.9.1). The format follows
[LIB_REQUESTS.md](LIB_REQUESTS.md): what, why the app needs it, evidence, and a proposed
interface. Requests are closed by an OmaBridge release, not by workarounds here.

## 2026-10-25: 0.2.1 (`364b288`)

Checked by reading the repository at `364b288` ("OmaBridge 0.2.1"): `README.md`, `docs/`,
`src/` and `tests/`, and ot3's host-only integration at `7b5b1de`
(`src/omabridge_*.rs`, `ui/qml/T3ConnectionOmaBridge.qml`, `T3OmaBridgeManage.qml`,
`T3OmaBridgeQuick.qml`, `T3OmaBridgeIndicator.qml`, `ui/js/OmaBridgeModel.js`,
`OmaBridgeText.js`, `docs/CONNECTION_OMABRIDGE.md`). Nothing was run.

### What a remote device can call today

| Listener | Bind, protocol | What it offers | Auth | Source |
|---|---|---|---|---|
| Node (`serve`, `t3`, `egress`) | QUIC (iroh) on `--bind`, default `0.0.0.0:0`, direct or through a relay; ALPN `omabridge/service/1` | one framed JSON request per stream (u32 length + JSON, ≤ 64 KiB): `pair {token, name}`, `open {service, public?}` (then a raw byte pipe), `hello`, `manage {command}` | the iroh key of every peer; `pair` needs the invitation token; `open` a paired peer with the grant; `manage` an **admin** peer, limited to `REMOTE_ACTIONS` | `src/network.rs:23-24,149-198,918-951,1118-1234`; `src/main.rs:25-26`; `src/manage.rs:60-73` |
| Gateway | TLS over TCP on `0.0.0.0:<port>` (random 20000–60999 or chosen), SNI-only certificates | bytes piped, uninterpreted, into the `t3` service of one paired device; URL `https://{host}:{port}/`; 2 min idle | none of its own: T3's pairing and sessions | `src/gateway.rs:5-7,30-46,59-61,99-114,149-316`; `README.md:157-165` |
| Hosted relay / `omabridge relay` | iroh-relay over HTTPS (3341 suggested; 3340 on loopback by default) | relaying for paired ids | paired ids / `--allow-peer` | `src/hosting.rs:17,312-315`; `src/relay.rs:20-31,64-125`; `src/main.rs:43-44` |
| `omabridge mcp serve` | HTTP/1.1 on `127.0.0.1:8787` | MCP with OAuth 2.1 | Bearer | `src/mcp/server.rs:684-802`; `src/main.rs:175` |

Local only: `control.sock` (Unix, 0600; `src/network.rs:421-612`), `omabridge manage`
(one JSON request on stdin; `src/main.rs:296-324`, `src/manage.rs:322-446`) and loopback
forwards (`src/main.rs:586-627`). There is **no HTTP, WebSocket or gRPC management
API**; the only remote management path is QUIC `manage` from an admin peer, which needs
an OmaBridge client. Peers are found only through invitations (`src/network.rs:918`;
`README.md:21`). "There is no Android/iOS client and no phone QR scanner"
(`README.md:205`; `docs/verification.md:153`). The SDK proposal names "T3 Code mobile
connecting to desktop/headless installations" as its primary consumer
(`docs/research/sdk-contract-proposal.md:7`).

What works for the phone without any of the requests below: a **gateway address** as a
plain T3 URL with a `t3 pair` code (`README.md:157,162`), which UI_SPEC 4.2.2 adopts as
the v1 OmaBridge route.

How ot3 performs each operation (all through `omabridge manage` on the host,
`ot3:src/omabridge_device.rs:27-57`) and whether a remote call exists:

| Operation | ot3 request | Remote today |
|---|---|---|
| Status, peers, pending, published, relay, gateways | `{"action":"read"}` (`ot3:ui/js/OmaBridgeModel.js:82-124,178-180`) | admin QUIC `manage read` |
| Approve, revoke | `approve {peer, expires}`, `revoke {peer}` (`ot3:ui/qml/T3OmaBridgeManage.qml:784-787,863-865`) | admin QUIC (`revoke` never against an admin, `src/manage.rs:1540-1546`) |
| Publish, unpublish | `publish {t3, ssh}`, `unpublish` (`ot3:ui/js/OmaBridgeModel.js:265-270`) | admin QUIC |
| Open / close a gateway | `grant-set {peer, services, public}` here, then `remote {peer, command: gateway-set}` (`ot3:src/omabridge_gateway.rs:61-83`) | `gateway-set` admin QUIC; `grant-set` **no** |
| Gateway check | `gateway-check {url}` → `{reachable, sameEnvironment}` (`ot3:src/omabridge_gateway.rs:85-90`; `src/manage.rs:1252-1293`) | **no** |
| Invite | `invite {services, qr, manage?}` (`src/manage.rs:587-646`) | **no** |
| Pair as a joiner | `pair {path, expect}`, `pair-retry`, `pair-discard` (`src/manage.rs:675-816`) | the QUIC `pair` op itself, for a device that runs OmaBridge |
| Name sync, identity, admin grants | `sync`, `identity-create`, `admin-set` | **no** (by design for identity and admin) |

### OB1. An Android device client (blocking for the OmaBridge page and direct routes)

- What: the phone as an OmaBridge device: its own identity, pairing from an
  `omabridge://pair/<base64url>` QR (`src/main.rs:511-548`; `src/manage.rs:237`),
  opening the host's `t3` service, and sending `manage` to devices that let it manage
  them.
- Why: without it the phone can only use public gateway addresses; direct, NAT-traversing
  QUIC routes, ot3's per-environment plans ("OmaBridge first, then the saved route",
  "Only through OmaBridge"; `ot3:docs/CONNECTION_OMABRIDGE.md:17-26`) and the whole
  OmaBridge page (UI_SPEC 4.9.1) need a client.
- Evidence: `README.md:205`; `docs/verification.md:153`; the protocol is only spoken by
  the `omabridge` binary (`src/network.rs:934-951`).
- Proposed: a Rust crate (`omabridge-client`) built for `aarch64-linux-android` and
  `x86_64-linux-android`, packaged inside lib-t3-rust's AAR or as its own AAR with the
  same JSON-over-JNI convention (`{"op": …}` in, JSON out), state in an app-private
  directory:

  | Operation | Request | Response |
  |---|---|---|
  | `identity` | `{"op":"identity","stateDir":"…","create":true,"name":"Pixel"}` | `{"id":"…","name":"Pixel","created":false}` |
  | `pair` | `{"op":"pair","invitation":"omabridge://pair/…","name":"Pixel"}` | `{"status":"pending"\|"approved","peer":{"id","name"},"services":["t3"],"expires":"…"}` |
  | `pairRetry` / `pairDiscard` | `{"op":"pairRetry","peer":"…"}` | as `pair` / `{}` |
  | `peers` | `{"op":"peers"}` | `{"peers":[{"id","name","services","remoteServices","admin","managed"}]}` |
  | `bridge` | `{"op":"bridge","peer":"…","service":"t3","relay?":"…"}` | `{"listen":"127.0.0.1:41234","bridgeId":"…"}` (loopback only, closed with `closeBridge`) |
  | `manage` | `{"op":"manage","peer":"…","command":{"action":"read"}}` | the daemon's `managed.outcome` |
  | `events` | `{"op":"events"}` (stream) | `{"type":"peerChanged"\|"pending"\|"bridgeClosed",…}` |

  Auth is OmaBridge's own (the phone's key, the inviter's approval); T3's bearer still
  authenticates every T3 session over a bridge (`README.md:77`). Foreground-only use is
  enough for v1 (the SDK proposal's lifecycle gate G5, `docs/research/sdk-contract-proposal.md:213`).

### OB2. Remote actions the phone's page needs beyond `REMOTE_ACTIONS` (blocking for gateways and invitations on the page)

- What: from a phone that manages a host: check a gateway, consent to public exposure
  of the host's own `t3` (`grant-set` with `public`), create an invitation for another
  device, and sync names.
- Why: the page's "Public access to T3 Code" flow is `grant-set` + `gateway-set` +
  `gateway-check` (`ot3:src/omabridge_gateway.rs:61-90`); with `REMOTE_ACTIONS` only
  `gateway-set` is reachable (`src/manage.rs:60-73`), so the phone could open an address
  the host never consented to expose and could not verify it.
- Proposed: add to `REMOTE_ACTIONS`, each still gated by the admin check and the audit:

  | Action | Request (`manage.command`) | Response (`outcome`) |
  |---|---|---|
  | `gateway-check` | `{"action":"gateway-check","url":"https://laptop.vpn.example.net:443/"}` | `{"gatewayCheck":{"reachable":true,"sameEnvironment":true}}` |
  | `grant-set` (own services only, `public` only for `t3`) | `{"action":"grant-set","peer":"<gateway id>","services":["t3"],"public":true,"confirmed":true}` | snapshot |
  | `invite` (no `manage` grant) | `{"action":"invite","services":["t3"],"qr":true}` | `{"created":{"expires","services","uri":"omabridge://pair/…"}}` (the URI instead of a file path) |
  | `sync` | `{"action":"sync"}` | snapshot |

  Identity, admin grants and pairing *from* the host stay local, as the comment at
  `src/manage.rs:60-61` intends.

### OB3. A change feed for pending approvals (nice to have)

- What: the phone shows a dot when a device waits for approval (ot3's bar badge,
  `ot3:ui/qml/BarWidget.qml:78`).
- Why: ot3 polls `read` every 60 s (`ot3:ui/qml/T3OmaBridgeIndicator.qml:29`); a phone
  polling a host over QUIC wastes battery and misses requests while backgrounded.
- Proposed: a QUIC request `watch {}` for admin peers that returns a stream of
  `{"type":"pending","peer":{"id","name"},"services":[…],"expires":"…"}` and
  `{"type":"peers"}` / `{"type":"published"}` change notices, ending when the peer loses
  admin rights.

### OB4. Tell a T3 client that an address is an OmaBridge gateway (nice to have)

- What: the phone labels a route "Connected through OmaBridge · {gateway}" (UI_SPEC
  4.2.1).
- Why: a gateway pipes uninterpreted bytes (`src/gateway.rs:5-7`), so nothing on the wire
  says it is one, and ot3 offers gateway URLs as ordinary pairing endpoints
  (`ot3:ui/qml/T3ConnectionAccess.qml:29-30,148-151`).
- Proposed: a shared link convention, produced by OmaBridge's `gateway-set` result and by
  ot3's pairing QR for a gateway endpoint: the T3 pairing link plus
  `route=omabridge&gateway=<gateway name>`, e.g.
  `https://laptop.vpn.example.net:443/pair?route=omabridge&gateway=laptop#token=…`.
  `gateway-set` would return `{"gateway":{"url","dns","link":"…?route=omabridge&gateway=laptop"}}`.
  lib-t3-rust's `connectionCatalog.pairingTarget` returns it as `routeHint` (LIB_REQUESTS
  U27).
