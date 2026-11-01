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
- Sixteen hand-kept strings in all 20 locales (320 localized values).

## Verification

SDK rebuilt with `scripts/build-bridge-sdk.sh` for host/x86_64/arm64-v8a,
production flavor, exact pinned source. Initial compile passed. Final results
will be recorded below after all required checks complete.

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
