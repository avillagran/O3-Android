# P13 Connections contribution and SDK requirements

## Current integration (2026-10-31)

The current pinned SDK exposes `mobile-admin-v1`, `peers` and `manage`.
Connections settings now opens the same OmaBridge administration dialog as
Environments. The 20 localized explanations describe this available entry.
Peer rows show full IDs, peer-advertised `remote_services` drafts grants and
`managed` status. Only a valid full ID advertising `drafts` offers draft selection;
only a peer advertising management offers administration. Names never establish
identity or permissions. Advertisements guide the UI; the authority still checks
every operation. No raw command fallback or grant escalation is added.

The following is the original P13 audit against older bindings, retained as
historical evidence; its blanket SDK limitation no longer describes the current UI.


Checked 2026-10-31 against Android `origin/main`, lib-t3-rust v0.3.5
`d4bf6054b4480efdcd8a436cb66cb7f2e23664b1` and OmaBridge mobile SDK
`f7eef5c73ede57c0e54929b1dadaf99525d69e54` (`omabridge-mobile-1`).
No SDK upgrade, raw RPC, shell command, SSH execution or host installation is added.

## P20 integration

Register `ConnectionsRouteContribution.route` (`settings/connections`) and call
`ConnectionsRouteContribution.Content(onBack, onEnvironments, onPair)`.
The callbacks lead to existing environment management and invitation import.
Collect `BridgeCatalogController.identity` and pass it as the optional `identity`
argument to display the actual SDK device ID and selected bridge ID. Selection
rejects abbreviated peer IDs before invoking the SDK.
Navigation and AppGraph are deliberately untouched. This contribution is not yet
reachable from the main settings graph; P20 owns that integration.

Existing environment controls retain edit/remove and manual URL/token pairing.
The pairing screen now reviews the full peer ID before any OmaBridge network
request. Consent starts unchecked for each review, including a repeat invitation
from the same peer. The SDK invitation has no visible allHosts/services declaration:
its token is opaque. Consequently the screen warns that access may include all
hosts and asks the user to verify the scope with the issuer. It never infers scope
from a full-access row or the peer name. Management invitations remain unsupported.
One-use invitation input is cleared at dispatch and on leaving the screen. Pending
approval stays on the authorized host; the user reimports after approval rather
than silently polling or issuing an approval from Android.

## Binding evidence

OmaBridge `mobile-sdk/src/lib.rs` exposes production operations `open`, `close`,
`pair`, `selectBridge`, `refresh`, `cached`, `projection`, `enroll`, `hostPair`.
`open` returns client identity and selected bridge identity. `pair` returns
approved/allHosts; this result is consumption, not an issuer or consent-set API.
The lab-only request helper is not an administration API and must never be used.
`src/manage.rs` and Desktop QML commands are not exported through this mobile ABI.

The pinned T3 `src/api/mod.rs` registry and all registered `src/api/*.rs` modules
contain no Connections access/cloud/network/preferences administration functions.
`T3Client.connectRelay` is a transport using an already issued JWT, not T3 Connect
account authorization or lifecycle administration. Generic rpc/request methods do
not establish typed, supported administration contracts and are not used to
bypass this gap. U27 route presentation does not create route persistence APIs.

## Explicit SDK/host changes required (controls omitted)

| Audit rows | Missing mobile contract and required guarantees |
|---|---|
| 100, 112, 248–250, 305 | Read/mutate preferred and saved routes, route plan automatic/saved-only/bridge-only, defaultRouting and automaticSharing; selected environment identity, revision preconditions, authoritative receipt and unknown-result reconciliation. Preserve working routes on failure. |
| 101, 246–247, 304, 526 | Read/write device-scoped load balancing with weights 100/50/25/0 and per-host GitHub permissions off/read/read-write; canonical environment IDs, catalog revision, availability, reset and inherit semantics. Do not copy credentials into Android preferences. |
| 102–104, 299–302 | Typed selected-host access listing, advertised scope catalog, one-use pairing issue/discard, exact client-ID revoke with delegated session withdrawal; host capabilities and scopes. Network bind/Tailscale/port changes need host-owned confirmation, operation ID, restart/cancel and status recovery. Cloud authorization needs bounded device-code lifecycle, browser URL, expiry/cancel and revoke/tunnel-stop receipt. |
| 106–109, 533–538, 340, 342 | Mobile admin capability discovery; exact peer-ID consent-set with default off, allHosts warning, grant/withdraw receipt and delegated-access revocation. Invitation issuer supports services T3/SSH, manage/allHosts, TTL, one-use secret, QR and discard/cancel. Scope must be available before acceptance, not inferred from opaque token. Late issue results after close must be discarded/revoked by request ID. Pending approvals include authority ID; all-host approval must remain on the authorized host. |
| 110, 543 | Identity/status and services/peers admin projection; publish/unpublish, rename, forget and revoke with exact IDs and authoritative status. No Android service publication until the SDK implements its own supported lifecycle. |
| 111, 539–544 | Gateway/relay read and mutation, public/custom/none relay policy, URL validation and exposure consent, enable/use-everywhere/disable, open/recheck/close/revoke; selected-host execution, operation IDs and safe unknown outcomes. |
| 113, 339 | Host installer profiles bridge-only/headless/Omarchy-with-O3/T3-with-bridge; selected remote target, advertised capabilities, structured prompts/progress/cancel and secret references. Return an authorized-host handoff; never run a Linux installer on Android. No fabricated installer URL or raw shell callback is exposed. |

Thus P13 administration parity is **blocked by the pinned bindings**, not complete.
The code delivered here hardens supported invitation consumption and expiry
presentation and exports a truthful integration contribution. SDK rows above
remain open; no unsupported switches or inert admin action buttons are shown.

## Safety fixture map

- Default-off consent and exact peer-ID match: `BridgePairingReviewTest` and
  `BridgePairViewModelTest`; ID prefix, display name, case and another peer rejected.
- allHosts warning/default-off touch action: `BridgePairScreenTest` checks warning,
  full ID and disabled Pair until consent. This is a consumer warning; issuer
  checkbox/consent withdrawal remain blocked, not simulated as working APIs.
- Revoke removes delegated access: existing `AllHostsBridgeTest` withdrawal fixtures
  verify session stop, credential deletion, deletion retry and ownership races;
  `BridgeCredentialGateTest` denies revoked credentials even when deletion fails.
  Administrator-triggered revocation needs the missing SDK contract above.
- No invitation after close: review close invalidates tickets, ViewModel dismissal
  cancels queued/in-flight requests and suppresses late completion; invitation
  issuance itself is absent and has no local fallback.
- Expiry and offline renewal: review expiry is checked before dispatch;
  `BridgeCredentialGateTest` uses session expiry independently of refresh;
  `AllHostsBridgeTest` tests failed renewal preserves only the still-valid session,
  expired sessions remain denied, later renewal restores access without downgrade.
  Environment row status now recomputes each second while observed, even offline.
- Remote approval remains authorized-host only: pending ViewModel fixture makes
  exactly one pair call, clears the secret and exposes no approval command.

Device execution depends on an available emulator. JVM fixtures do not establish
real remote-host administration, JNI protocol, RTL or accessibility acceptance.
