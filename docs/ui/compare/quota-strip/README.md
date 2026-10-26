# Provider quota strip — 2026-10-26

The production Home list now has a stable first item for provider usage. It scrolls
with the threads. The real thread reader hosts the same component immediately below
its toolbar, capped at 200 dp. Overflow on both screens and Appearance control the
same persisted preference, off by default. Tapping a group opens Usage on Limits;
the thread passes its environment. Home observes all saved environments.

## Data and ownership

`AppGraph.quotaSource` shares one native `QuotaReader` reducer/server-config stream
per observed environment across Home, thread and the real Limits view model. Each
collector acquires a reference. The last release cancels and joins that entry before
another acquisition may replace it; no zero-consumer entry or background stream is
retained. Connection replacement/disconnect cancels the old read; stream close/error
clears old observations and retries with bounded backoff. The application-owned
ProviderRefreshGate remains independent of screen/entry lifetime, with failed probes
counting toward its five-minute interval. Shell subscriptions and reader view models
are unchanged. The strip slot is never used as a key around reader/composer content.

All quota folding/projections remain Rust `quotaSources.apply`. Android renders
sanitized windows only. lib-t3 0.3.5 source `d4bf6054b4480efdcd8a436cb66cb7f2e23664b1`,
AAR SHA256 `b72a5207eaf7c075c5a24e097e150214d36e0cbf4450087b6adf9d1ab2ff4f09`.
Bridge SDK source `f7d93a9ea8600991a229cadb9d189306965d4195`, production flavor, both
ABIs verified by the existing build task. No PR40 SDK or shared-library edits.

## Evidence provenance

Goldens in `app/src/test/snapshots/QuotaStripScreenshotTest` show the same empty
Home/thread content with the strip off/on, in Tokyo Night and Catppuccin Latte,
plus empty/offline and reduced-motion-hidden cases. Inputs are the **genuine frozen
native sanitized output** `debug/assets/usage/limits.view.json` inherited from PR41;
these screenshots do not run JNI in Robolectric. Test time zone is explicitly
America/Santiago and verification runs under host TZ=UTC. Separate core JNI tests
exercise the common reducer pipeline.

Device evidence uses WorkspaceFixtureActivity: real navigation, production Home /
thread / Usage components, real view models/connections and JNI reducer, **synthetic
transport events and conversation**, not a live provider, NODE, bridge, or Xiaomi.
Before/after captures preserve the same content and palette. Instrumentation covers
persist/recreation, Appearance/overflow, thread draft preservation, landscape,
Limits routing, background/resume, empty observations and disconnect/reconnect.

## Exact desktop differences / continuity

- U26 `quotaSources.present` is absent in 0.3.5. There is one group per native
  provider/account observation, no account pooling or deduplication across hosts.
- Bars show **used** percent, explicitly labelled, with static accent fill; there is
  no remaining calculation, pace color, expected marker, relative reset, or notice
  synthesis in Kotlin. Reset timestamps use the Android platform absolute formatter
  retained from f240491; narrow rows ellipsize the visible reset text.
- Home shows at most two native windows per observation; thread shows all windows
  in a 200 dp scroll area. Native window order is preserved. Account details and
  additional Home windows remain on Limits via the group tap, rather than inline
  account expansion. No new refresh control is added to the compact group; Limits
  has the existing Refresh, with the same five-minute gate.
- Loading/offline/read-failure state is shown per environment. A connected observation
  with no windows is hidden. No desktop tooltip/external-link notices are invented.
- Reveal uses the existing vertical Ot3Reveal implementation and reduced-motion
  policy. Bars/numbers never animate. The existing Usage/Limits **160 ms tab fade
  remains pending**, separate from this strip/lifecycle increment.
- Physical-device and productive provider/network evidence remain separate work.
