# Catalog clock SDK candidate

Rust libPR20 is merged into the catalog branch. OmaBridgePR6 source60bc03e consumes8c80c4d: fixed30s discovery issue-time allowance, original absolute expiry and <=10min declared window; credentials have no grace. Android source integrates main60529bf and PR40.

Validation:84 native tests across20 suites, final4 HTTP deadline tests, workspace/all-target Clippy and SDK lab-tls Clippy with warnings denied, fmt. JNIproduction host/x86_64/arm64 built with source guard; hashes, sole JNI export and Android16KiB LOAD alignment verified. Kotlin preserves the fixed safe diagnostics and redacts arbitrary error bodies.639 JVM tests passed,6 opt-ins omitted; app/core lint and assembleDebug passed. Host JNI is actually invoked by NativeBridgeTest, using an invalid handle without opening a network client or identity.

A first host-smoke assertion expected invalid_input; inspecting the SDK showed invalid_handle is intentionally redacted to bridge_operation_failed. Only that test expectation changed; final full suite passes. This is separate from the independently reproduced native1ms catalog regression.

Device acceptance is still pending. In particular, the prior real-T3 test reads shells through the SDK's labRequest helper with a private lab CA; this does not prove the ordinary application's shell streams through NativeT3TransportFactory. Productive NODE/VQ, Home captures and physical acceptance remain open. No production services/grants changed or phone update performed.

The regular Android CI workflow currently builds the pinned T3 AAR and treats the bridge SDK as optional. Its green checks must not be reported as bridge runtime coverage; the strict native/JVM results above are local. Mandatory SDK pipeline coverage remains pending.

See verification.json for candidate binary identity. All credentials and lab state remain outside the repository; no raw request/response bodies are included.

## Subsequent Android acceptance: source dd628443

The isolated API36.1 run now passes all10 instrumented cases, with zero failures or skips. See android-acceptance.json for exact tests, image, T3 version and all five packaged SDK identities. Three production artifacts were restored after the run. The earlier verification.json remains the historical60bc checkpoint.

The real-T3 test proves delegated identity through a loopback-only administrative witness: auth/session deliberately has no subject field. The witness matches exactly one administrative client row by expected subject, scope and absolute expiry, and returns only a boolean. A different host's token must fail that witness. Android retains orchestration:read; no administrative credential is delivered to it. Source: T3 v0.1.3-nightly.20261027.3754 contracts/auth.ts AuthSessionState and AuthClientSession.

Two harness failures were isolated before the successful run: a readonly emulator overlay exhausted /tmp, and a long temporary directory produced a109-byte Unix control-socket path. The runner now uses a short directory, guards sockaddr_un length and checks the actual socket before proceeding. Native examples compile before the readiness deadline; runtime enrollment diagnostics retain only fixed safe error codes and elapsed time. Original AVD configuration and six images were hash-identical after testing; the private clone was removed.

Scope remains JNI/QUIC/Room and real isolated T3 HTTP shell reads via SDK labRequest. This does not prove the ordinary application's NativeT3TransportFactory stream, productive NODE/VQ discovery, Home rendering or Xiaomi acceptance. Mandatory bridge SDK CI coverage also remains open.
