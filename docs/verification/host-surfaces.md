# Desktop host broker consumer

Source contracts: Desktop `docs/REMOTE_SURFACES.md`; OmaBridge
`docs/mobile-administration.md` at `1097f0e7cd4e91e58dce94744f13606a26756601`.

## Delivered

- Settings Memory, Development vault and Host entries; Storage → Desktop host.
  Choose an authenticated environment, verify and select its paired host explicitly.
  The selected environment credential is sent only after an explicit session read.
- `service-request-v1` / `desktop-snapshots` with GET/POST, transient bearer and
  base64 JSON. SDK capability, remote service grant and session capability gating.
  Missing support/consent/grant displays host setup steps instead of surface actions.
  Host consent disabled, not-sent and unknown are presented distinctly.
- Document index/snapshots rendered as selectable source text; static HTML gallery
  decodes bounded PNG only. No WebView, JavaScript, URI loader, file or network API.
- Memory, vault, host-sync, compaction, idle-release and build-cleanup projections,
  including full raw report and persisted/live labels supplied by the host.
- Explicit policy editors, vault rollout and cleanup prepare/confirm. Exact prepared
  request and complete report displayed without the confirmation token. Token is
  single-use, in memory, with conservative monotonic 60-second deadline starting
  before prepare dispatch; leaving/changing binding discards it. No automatic retry.
- Unknown host or transport outcome displayed and preserved across reads within
  the controller. Confirmation is consumed before send, also on failure/cancellation.
  A new page cannot replay an old token; the host also durably refuses exact-report replay.
- Coroutine cancellation signals cancelServiceRequest on another IO worker.
  Native errors never appear in UI, logs, URLs or diagnostics. No credential/body logging.
- Eighteen hand-kept strings in all 20 locales (360 localized values).

## Verification

SDK rebuilt with `scripts/build-bridge-sdk.sh` for host/x86_64/arm64-v8a,
production flavor, exact pinned source. Final source is `e948b43` (later changes
are verification documentation only). Java 21 from `mise where java@temurin-21`,
`ANDROID_HOME=$HOME/.android-sdk`, `--max-workers=2`, `ActiveProcessorCount=2`,
one Gradle run at a time.

| Check | Verified result |
| --- | --- |
| Core debug and release unit tests | Each 521 passed, 5 skipped; 526 XML cases |
| App debug unit tests | 540 passed, 1 skipped; 541 XML cases |
| verifyRoborazziDebug | 144 unchanged goldens, zero additions/changes |
| :app:lint and :core:lint | Zero errors; app 109 warnings + 1 hint, core 2 warnings |
| tools/import-ot3-strings --check | 261 imported keys × 20 locales, pass |
| Hand-kept copy and license checks | 18 keys × 20 locales/placeholder parity; 125 notices, pass |
| :app:assembleDebug -Pot3a.requireBridgeSdk=true | Pass, production SDK source/ABI/checksum checks |
| Full :app:connectedDebugAndroidTest | Initial full pass at `f734717`: 120 passed, 9 skipped; 129 XML cases |
| Final-source navigation regression, fresh runner | 1 passed, zero skipped/failures |

**The final-source full instrumented suite remains unverified.** Three subsequent
unfiltered attempts were stopped after several minutes without progress in
unchanged tests: `PullRequestsReturnDeviceTest.laterPageAndPixelAnchorSurviveDetailBackRecreateAndRotation`,
`PullRequestsDeviceTest.latteReader`, and `ReaderImagesDeviceTest.tokyoNight`.
The first used emulator 37.2; the latter two used 36.6.11 with Vulkan disabled,
the last with a fresh `--no-daemon` Gradle process. No assertion/deadline was weakened
and those test files are unchanged. Their cause is not demonstrated; changing the
emulator or runner did not establish a fix. A focused navigation run after cancellation
also lost the runner result despite AndroidJUnit reporting zero failures; a clean
single-use runner then passed that case. Do not present the initial complete pass
as a complete acceptance run of the final revision.

Dedicated API 34 AVD `ot3a-host-surfaces`, serial `emulator-5584`, 2 cores/2 GiB,
was stopped after verification. No physical device was addressed. Logs, APK and
SHA256SUMS are retained in `~/.cache/o3-android-artifacts/host-surfaces/`.
APK SHA-256: `4c0aa1804767607131cc480ba02fb6b383ab6bee2215b4880ae2fe3c7598aaea`.

Core fixtures cover disabled consent, denied/revoked credentials, expired confirmation,
stale report, notSent, transport/host unknown, cancellation and a changed prepared
request. App fixtures cover bounded PNG decode and rejection of HTML/script/file/network
references, excessive dimensions and excessive bytes.

## Unverified / remaining

No physical phone, deployed host, production service configuration, live paired
round trip or credentials were accessed. Emulator fixtures are not physical or
multi-host acceptance. No live browser/device session, interactive HTML runtime,
manual compact/release or host-sync apply is exposed. Owner publication remains
manual. SDK HTTP response bound is 512 KiB; larger snapshot/gallery JSON responses
fail closed even though the Desktop publication store allows larger content.
Native worker state is persisted, not live control. Accessibility/RTL/large-font
acceptance of this new screen and production host acceptance remain unverified.


Host setup (owner only): enable Remote documents on this device in Preferences →
Connections, then run `omarchy-t3-client remote-surfaces-serve http://127.0.0.1:<T3-port> <broker-port>`.
Configure an OmaBridge service named `desktop-snapshots` targeting that loopback
broker port and grant it to the intended phone. Also enable Allow remote maintenance
on this device for mutations; cleanup roots stay owner-local. Do not publish the
broker port directly. Android cannot grant itself any of these permissions.
