# Bridge authority and unified Android lab

The production node still denies enrollment unless the operator explicitly supplies
`omabridge serve --enrollment-config /absolute/private/config.json`.
A configuration grants local issuance authority; do not point it at a directory you
do not administer. A remote computer must have its own issuer, never a copied desktop
bearer. Example (paths/UUID are placeholders, no credentials):

```json
{
  "t3Binary": "/absolute/path/to/t3",
  "ttlSeconds": 120,
  "environments": {
    "configured-environment-uuid": {
      "baseDir": "/absolute/owned/t3-data",
      "descriptorUrl": "http://127.0.0.1:8643/"
    }
  }
}
```

The authority verifies the local descriptor's UUID, invokes the existing
`auth session issue --scope orchestration:read --ttl ... --subject omabridge:<peer>`
CLI without a shell, and validates its receipt. The authenticated bridge peer and
its environment permission are checked before and after issuance. The mobile SDK
checks the selected HTTPS descriptor before sending the session token, then checks
`/api/auth/session` scopes and actual expiration. It persists no bearer in its cache.
The session is individually revocable through T3's existing Connections/client
revocation API; the tests revoke it at T3, independently of the bridge's catalog gate.
Bridge revocation immediately gates Android and prevents further issuance; an issued
bearer held outside Android remains subject to its T3 revocation and bounded TTL.

A real isolated T3 probe established that `auth pairing create --ttl 2m` limits the
bootstrap link, while its OAuth exchange returns a session of about 30 days. That
mechanism therefore cannot satisfy a five-minute session contract. It remains
supported only for authorities whose server actually bounds the exchanged session;
the SDK rejects excessive TTL. The new authority uses freshly issued scoped sessions,
not desktop credentials, and defaults to 120 seconds (explicit range 1–240 seconds).

## Reproduce

1. Keep T3 AAR and host JNI coherent with `libs/lib-t3-rust.lock` (currently 0.3.5);
   the bridge library has its own source lock and never changes that pair.
2. Start the dedicated emulator using `scripts/emulator.sh start --headless` and
   await that command's successful completion (not just an ADB transport), then
   use T3 `device_list` / `device_open` for `emulator-5580`. No physical device.
3. Run:

```sh
scripts/verify-bridge-lab.py --t3-binary /absolute/path/to/t3 --full-ui
```

The script requires a clean bridge checkout at `libs/omabridge-native.lock`, builds
host, x86_64 and arm64 candidates from that exact source, and starts fresh private
lab directories. Two actual T3 processes have separate base directories and isolated
runtime homes, projects and metadata-only threads (no provider runs). TLS terminators
listen only on host loopback. The emulator uses `10.0.2.2`; certificate verification
remains enabled and the selected route's descriptor identity remains mandatory.

`OMABRIDGE_LAB_TLS=1` compiles the SDK's `lab-tls` feature. Its additional trust anchor
and HTTP observation helper exist only in that build. The HTTP helper allows the
instrumented test to read real shells with the test CA; this does **not** demonstrate
that the app's production T3 transport trusts that private CA. Production uses normal
public trust and the unchanged baseline T3 transport. Orchestration write denial is
also tested against real T3's existing RPC using the very session issued for Android;
the loopback control helper carries it in memory, never in URLs or logs.

Gradle validates source, ABI, target and SHA-256 of both packaged ABIs. Lab artifacts
require `-Pot3a.bridgeLab=true` in debug and are forbidden in release. For a normal
candidate, rerun `scripts/build-bridge-sdk.sh` without the lab environment variable.
`-Pot3a.requireBridgeSdk=true` makes omission a failure; without it an SDK-free build
keeps manual T3 pairing and displays an explicit bridge-unavailable explanation.

The core run verifies a single Android controller/JNI/QUIC/real-T3 chain: scoped
issuance, two distinct shells, host separation, cross-client invitation denial,
write denial, natural expiration and T3 revocation, then bridge revocation. It also
runs the separate catalog fixture and Room concurrency/persistence tests. The app
suite runs sequentially after core so two instrumentation runners never share the
emulator. The UI fixture separately verifies QR URI framing, pending/approved,
secret clearing and the SDK-free fallback; it is not a live desktop acceptance test.

The script fails if the unified test is skipped, stops/reaps its labs, and removes
private logs/state. Stop the emulator with `scripts/emulator.sh stop` afterwards.
No GitHub workflow was added or claimed: the current PAT cannot write workflows.
Normal CI that omits the lab/SDK is still not bridge integration evidence.


## Delegated issuer acceptance

The delegated lab must expose a distinct `issuerBridgeId` and the NODE
`delegatedEnvironmentId` in its private loopback `/invitation` response. Android
asserts that the issuer differs from its paired desktop bridge, that the resulting
T3 session subject ends with the Android client id, and that the native delegated
handler's `/delegation-status` counter records completed issuance. Neither receipt
nor token is logged. These checks are additional to real shell identity, write
rejection, natural expiry and T3 revocation.

Both core and app Gradle invocations explicitly select `emulator-5580`, including
`android.injected.device.serial`; a connected physical phone is not a test target.
Do not interpret normal CI's opt-in skip as a passing delegated lab. The final
candidate source must be committed and pinned before running this acceptance test.
