# Missing host diagnosis — 2026-10-27

The dedicated reporting method ran on the known Xiaomi with a plain Application,
without AppGraph/migrations/reconnection. It reported one independent environment
labelled VQ, UUID 8b55eaad-3149-443c-953f-afd6dd002666, zero bridge-managed rows and
no selected native bridge. This UUID matches NODE's live public descriptor at
127.0.0.1:8643. The test package was installed; the target APK/data were retained.

Two running bridges share the display name NODE. The runtime-tracked instance
uses omabridge-route and publishes T3+SSH to its approved peers. Its fixed 3773
argument is a fallback; --t3-runtime reads the current runtime port, so a failed
3773 probe is not a routing diagnosis. The older UI-test instance publishes SSH
only and knows a peer labelled mbp-m2. Labels do not establish endpoint identity.

Neither observation proves the native catalog publisher or downstream T3 authority
is configured for the phone. Pairing/host publication requires choosing the actual
bridge identity, a compatible catalog service, explicit per-client environment
allowlists and bounded per-host T3 authorization. Existing peer visibility does not
supply those grants. Do not merge the two stores, copy tokens or replace listeners
merely because the labels match. The current bounded enrollment policy is read-only;
write access for creating chats on newly managed hosts needs separate explicit support.

No productive bridge, grant, session, listener or publisher was changed. The app now
exposes its existing bridge-pairing entry point when no selected bridge is loaded;
that is onboarding clarity, not completed multi-host synchronization.
