# Read-only installed-catalog probe

`InstalledCatalogReadOnlyTest` reports a bounded, read-only summary of the catalog
metadata that an installed client already has on disk. It exists to answer one
narrow question — whether the `environments` and `bridge_managed` tables are
present and which environments are bridge-managed — without starting the application graph, opening a
transport, or reading any credential, message or route URL.

## Why a separate runner

AppGraph initialization performs migrations and reconnection work, so it cannot
be part of a "read only" claim. The instrumentation argument alone is therefore
not sufficient: the test asserts that the instrumentation instance is
`InstalledCatalogReadOnlyRunner`, which overrides `newApplication` to instantiate
a plain `Application` instead of the app's own class. Without that runner the
test fails rather than degrading into a weaker probe.

Opt-in is enforced by `assumeTrue` on the instrumentation argument
`ot3a.installedCatalogReadOnly=true`; the default runner skips the case. The
Gradle property `ot3a.installedCatalogReadOnlyRunner` selects the runner name
written into the test APK manifest:

```kotlin
val installedCatalogReadOnlyRunner =
    providers.gradleProperty("ot3a.installedCatalogReadOnlyRunner").orElse("false")
// defaultConfig.testInstrumentationRunner = ... InstalledCatalogReadOnlyRunner when "true"
```

## Fields read

The report is JSON sent as instrumentation status `ot3a.catalog.preflight`.
It contains `readOnly=true`, `nativeSdkAvailable` (from `NativeBridge.isAvailable()`),
`selectedBridgeOnDisk` (existence of `omabridge/mobile-selected-bridge.json` in
`noBackupFilesDir`, the same file boot guard AppGraph uses — the file is never
decoded), and `databasePresent`. When the database file exists it is opened with
`OPEN_READONLY or NO_LOCALIZED_COLLATORS`, the test asserts `isReadOnly`, and only
these columns are queried:

| Table | Columns |
| --- | --- |
| `environments` | `id`, `label`, `environmentId`, ordered by `sortOrder` |
| `bridge_managed` | `id` |

Per environment the report emits `label`, `environmentId` and a boolean
`bridgeManaged`. Table presence is reported as `environmentsTablePresent` and
`bridgeManagedTablePresent`, plus `bridgeManagedCount`. The test adds
`applicationGraphStarted=false`. No database copy, SDK state, URL, token or
message is emitted, and the file is opened without writing.

## Reproducing

Use a checkout matching the installed target application and a compatible signing
key. Build the test APK; its manifest contains the runner name. Installing this
test package is a setup mutation. Do not replace the target APK merely to inspect
its existing catalog. Run from the repository root:

```sh
./gradlew :app:assembleDebugAndroidTest \
  -Pot3a.installedCatalogReadOnlyRunner=true

adb devices            # identify the intended device explicitly
probe_serial="REPLACE_WITH_OBSERVED_SERIAL"
adb -s "$probe_serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

adb -s "$probe_serial" shell am instrument -w \
  -e ot3a.installedCatalogReadOnly true \
  -e class dev.avillagran.ot3android.diagnostics.InstalledCatalogReadOnlyTest#reportSavedCatalogWithoutStartingTheApplicationGraph \
  dev.avillagran.ot3android.test/dev.avillagran.ot3android.diagnostics.InstalledCatalogReadOnlyRunner
```

Always pass an explicit `-s <SERIAL>`; never rely on a default device. On a
physical phone, select only
`reportSavedCatalogWithoutStartingTheApplicationGraph`. The second case,
`opaqueFixtureCredentialsAndMessagesAreNeverReadOrChanged`, writes a synthetic
database under `cacheDir` (`catalog-probe-<nanotime>`) with marker values and
deletes it in `finally`. That write is why the full suite belongs on an isolated
emulator, not on a personal phone, and why the phone invocation above must stay
scoped to the single method.

Instrumentation and installation are not performed as part of maintaining this
document; the commands are recorded so that a reader can run them deliberately.

## Historical evidence

Target application base: `c4a63af`. The diagnostic source was uncommitted when
these measurements were recorded; these JSON files do not identify an immutable
probe revision.

[`verification.json`](verification.json) — API34 emulator, private copy-on-write clone, not a
productive phone, custom runner: 2 tests, 0 failures, 0 skipped. The fixture
database SHA-256 was unchanged and the private canaries were absent from the
report. The report was `readOnly=true`, `nativeSdkAvailable=true`,
`selectedBridgeOnDisk=false`, `databasePresent=false`,
`applicationGraphStarted=false`. The normal runner on the same build gave
1 passed and 1 skipped, which is the expected `assumeTrue` opt-in behavior.
Seven source AVD files were unchanged.

[`physical-verification.json`](physical-verification.json) — Xiaomi 23090RA98G, Android 16, single method
`reportSavedCatalogWithoutStartingTheApplicationGraph`, 1 test, 0 failures,
0 skipped. The report was `readOnly=true`, `nativeSdkAvailable=true`,
`selectedBridgeOnDisk=false`, `databasePresent=true`,
`environmentsTablePresent=true`, `bridgeManagedTablePresent=true`,
`bridgeManagedCount=0`, one environment labelled `VQ` with
`bridgeManaged=false`, and `applicationGraphStarted=false`. The production
configuration was not modified and no live transport test ran.

## Limits

These measurements describe installed metadata and nothing else.

- No live connectivity was exercised. The probe opens no peer, service, session,
  credential or socket, so the evidence says nothing about reachability,
  pairing or transport health.
- Absence of the canaries from the report, together with the unchanged fixture
  digest, shows only that the report excluded the marker strings and that the
  fixture file was not modified. It is not an exhaustive proof that no other
  byte was ever read, and it does not cover the installed database, which was
  opened read-only but not compared by hash.
- `applicationGraphStarted=false` is a fact about this runner's application
  instance, not a statement about what a normal launch would do.
- `nativeSdkAvailable=true` reflects library availability at probe time and says
  nothing about whether a NODE is configured or reachable.
- The fixture case uses a synthetic schema, not a migrated production schema, so
  it does not validate migrations. Migration coverage stays with the regular
  test suites.
- The evidence is historical and tied to `c4a63af` and the two listed devices.
  It should be re-established, not assumed, for other builds, devices or Android
  versions.
