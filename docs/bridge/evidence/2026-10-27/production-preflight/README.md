# Desktop catalog deployment preflight

Read-only observation on the real Omarchy desktop, 2026-10-27. This is not an
Android acceptance test and does not establish the selected Android bridge identity.

- The running desktop client's `connections-view` contains enabled `node`, stable
  environment ID `8b55eaad-3149-443c-953f-afd6dd002666`; local primary is enabled.
- NODE has one `SshConnectionTarget`, SSH configuration present, and no URL.
  Copying that desktop route does not provide an Android HTTP origin. No SSH keys,
  desktop tokens or full route configurations were read into this evidence.
- Both running desktop bridge state directories have their control socket, but
  neither has `client-catalog.json`. No `omabridge catalog-publish` process was found.
- The installed desktop binary reports OmaBridge 0.2.1. No claim is made that it
  implements every operation in the newer source-pinned mobile SDK.

This proves missing publication on these two instances and missing URL in the
observed NODE route. It does **not** prove these are the bridge selected by Android,
that its client has a catalog grant, or that publication alone fixes connectivity.
It is not evidence that NODE itself is offline or that a new server API is required.

## Reproduce without credentials in output

```sh
python3 scripts/inspect-bridge-deployment.py \
  --ssh avillagran@192.168.1.31 \
  --desktop-client '~/.local/state/omarchy-t3-code/app/bin/omarchy-t3-client' \
  --state-dir '~/.local/share/omabridge' \
  --state-dir '~/.local/share/omabridge-route'
```

The helper runs only `connections-view`, reads fixed filesystem metadata and counts
publisher processes. SSH uses the existing authenticated configuration. It emits no
credentials, endpoint URLs, SSH configuration, process command lines or peer secrets.
It changes no state and starts no bridge, listener, service or provider.

## Concrete production acceptance still needed

1. Identify Android's selected bridge and client endpoint through its supported UI;
   compare with the intended desktop bridge. Do not infer identity from its label.
2. Verify an existing descriptor-matching route reachable from Android for both
   primary and NODE. The observed SSH route cannot simply be exported as a URL.
   Existing Rust `Client::forward` supports private loopback forwarding, but the
   mobile SDK currently exposes no corresponding route lifetime contract; this
   is an option to evaluate, not a deployed or approved solution. Do not create
   a public gateway or disable TLS to make a test pass.
3. Prepare explicit client-bound catalog grants and authority policy, including
   NODE owner's delegated enrollment consent. Discovery never grants T3 access.
4. Review exact policy files, target service versions and changes before enabling
   publication/issuance. None were created or activated by this preflight.
5. Use the ordinary app `NativeT3TransportFactory` for shell snapshots and stream
   updates; prove NODE/VQ Home rows, route change, offline/recovery and no duplicate
   streams. The isolated `labRequest` proof remains a separate result.
