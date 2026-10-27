# Reproducible public SDK pin

The canonical OmaBridge repository was republished with a neutral initial source
commit. Android's old `f7d93a9` pin belongs to the archived private history, so it
cannot reproduce from that public repository. Pin the public commit
`6b7c04ce2d3d0b9072a6ee97d325adec21c9a3a7`, preserving mobile ABI `omabridge-mobile-1`.
The native builder now requires the consumer's JNI symbol explicitly; the Android
wrapper supplies `Java_dev_avillagran_ot3android_core_bridge_NativeBridge_call`.

```sh
git clone https://github.com/avillagran/omabridge "$HOME/Work/omabridge"
git -C "$HOME/Work/omabridge" checkout --detach 6b7c04ce2d3d0b9072a6ee97d325adec21c9a3a7
# Export JDK21, Android SDK and NDK28.2 as in the project toolchain documentation.
scripts/build-bridge-sdk.sh
./gradlew :core:testDebugUnitTest :app:assembleDebug \
  -Pot3a.requireBridgeSdk=true -Pot3a.requireHostJni=true
```

The explicit linkage test invokes the real JNI symbol with an invalid handle and
checks safe error redaction. It creates no peer, service, session or credential.
Existing optional CI still skips it when SDK binaries are absent; passing that CI
alone does not certify the SDK. A strict required workflow remains separate work.

Before extracting this dependency update from PR40, root rebuilt host/x86_64/arm64
production and host/x86_64 lab flavors from the exact public source. Native tests:
126 passed in23 suites. Android/JNI/QUIC/Room:10 passed against two real isolated
T3 servers, including bounded issuance and revocation. All production binaries
were restored afterward. Android ELF load alignment is at least16KiB; metadata
records the source, ABI, target, flavor and hash. Those results support the source
candidate; they do not prove real NODE discovery or ordinary Android shell streams.

Source-candidate evidence: [native/artifacts](source-verification.json) and
[isolated Android cases](candidate-android-acceptance.json). These cases were run
on the broader PR40 integration, not on this extracted dependency-only branch.
The branch-specific verification is recorded separately.

This dependency update does not enable publication, change grants, deploy a
bridge, or claim productive NODE/VQ acceptance. Broader integration remains PR40.
