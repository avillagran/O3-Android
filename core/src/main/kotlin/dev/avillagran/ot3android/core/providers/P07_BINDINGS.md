# P07: models and providers

Reference: Desktop `feat/o3-brand`, audit P07 and rows 051–053, 118,
264–266, 335–336, 345, 359–362. Android pin: v0.3.5, source
`d4bf6054b4480efdcd8a436cb66cb7f2e23664b1`.

## Delivered contribution

Composer searches all native model fields and providers, supports multi-word and
subsequence matching, includes legacy matches during search, preserves native
order on ties, and promotes favorites. Favorites remain native server preferences.
Descriptors preserve explicit false, native current defaults and unavailable values;
choices are never rewritten into another option. Loading disables option mutations.
All displayed model/permission selections remain inputs from the owning screen.

`ProvidersRouteContribution.route` and public `ProvidersRoute` are exported for P20.
P20 creates one `ProviderAdministration(environmentId, connectedTransport)` per host,
keys the route by that controller, and supplies back and external URL callbacks.
No navigation, SettingsScreen, AppGraph or ThreadScreen change is included.

Administration calls the pin's typed functions for refresh, update, install,
auth start/respond/logout/cancel, install cancel and first-item auth/install state
reads. The UI provides browser/device-code, credential and terminal interactions,
explicit consent, cancellation, per-provider gates from server setup/auth/versionAdvisory fields and explicit status
refresh. The controller serializes operations, does not retry or optimistically
change provider state, retains only stable failure codes, and blocks mutations
after uncertain completion until an explicit config refresh. Credentials are
non-saveable ephemeral form values. Raw config/settings/environment secrets are
not retained in the provider list. Server-side argument validation stays in Rust.

## Unbound and intentionally omitted

- U29 `models.permissionView` is absent in the pin's domain registry. Missing/empty
  supportedRuntimeModes displays localized Unavailable; it does not synthesize
  Desktop's four-mode fallback or silently substitute the displayed active mode.
- U34 bounded, cancellable registry-icon binary read is absent. Arbitrary icon
  URLs are never fetched. Known driver marks and the bundled ACP fallback remain,
  with fixed dimensions and no custom-byte decoding/network/allocation path.
  The failure test proves fallback only, not a native bounded icon reader.
- Provider configuration projection, driver descriptors, secret-preserving merge,
  revision/conflict and mutation builders are absent from the domain registry.
  Desktop re-exports `t3_protocol::provider_instances::{projection,mutation,
  confirm_target}`; the AAR does not bind them. `server.updateSettings` DOES bind
  a low-level `providerInstanceMutation` (create/upsert/remove), but this is not
  the missing safe projection/mutation contract. Add/edit/remove/enable/disable,
  driver config/env editors, reset/inherit, registry prepare-and-add are omitted.
  No Kotlin validation, reserved-id rules or secret-preserving merge is invented.
- Default model/option preference payload builders and inheritance projection are
  absent. Existing per-thread draft persistence is unchanged. Native descriptor
  currentValue is shown; shared default editing is omitted.
- Desktop provider update discovery/one-click aggregation projection is absent.
  This contribution only presents explicit returned versionAdvisory.canUpdate,
  setup.canInstall/canAuthenticate and auth.canLogout flags. No Kotlin version comparison, install detection,
  cross-host Update all or fabricated capability inference is included.
- Continuous provider setup subscriptions are in the AAR, but T3Transport has no
  generic subscription binding. This contribution uses bound first-item state reads
  on explicit refresh; continuous progress needs P20 transport integration.

## Acceptance boundary

JVM fixtures cover descriptor false/select/unavailable, global provider/slug
search, legacy search, missing-mode safety, static icon failure fallback,
secret allowlisting, read-only scope, duplicate taps and unknown-result suppression.
No phone, emulator, host mutation, actual provider auth/installer or live JNI
provider session was exercised. The route is not reachable until P20 wiring.
Full Desktop parity remains blocked on the contracts above.

## Verified on NODE, 2026-10-31

Java 21 from `mise where java@temurin-21`, `ANDROID_HOME=$HOME/.android-sdk`,
Gradle `--max-workers=2`:

- Core JVM: 193 passed, 170 skipped, 0 failures (363 total).
- App JVM: 378 passed, 16 skipped, 0 failures (394 total).
- 13 new P07 tests passed (6 core, 7 app).
- `:app:verifyRoborazziDebug`: 94 existing goldens unchanged.
- `lint`: 0 errors; core 2 warnings, app 76 warnings and 1 hint.
- `tools/import-ot3-strings --check`: 261 strings × 20 locales.
- Hand-kept provider resources: 18 strings × 20 locales, matching keys/placeholders.
- `:app:assembleDebug -Pot3a.requireBridgeSdk=true`: passed.

Skipped tests retain existing JNI/opt-in constraints; no live provider acceptance
is implied. No phone or emulator was touched.
