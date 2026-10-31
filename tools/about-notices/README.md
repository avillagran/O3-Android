# APK notices

The checked-in `index.json` and texts in `app/src/main/assets/licenses` are the offline
source for Settings → About / Licenses. Upstream legal text is preserved, not translated.
UI text is hand-kept in `values*/strings_about.xml` (20 locales).

Rebuild after a dependency update, with Java 21 and the Android SDK configured:

```sh
./gradlew -I tools/about-notices/artifacts.init.gradle :app:exportLicenseArtifacts --no-configuration-cache --max-workers=2
python3 tools/about-notices/collect.py
python3 tools/about-notices/check.py
```

The exporter reads the resolved **release** runtime module graph, without changing app
Gradle configuration. The collector uses matching versions in Gradle's local cache;
it never guesses a version from the newest cached artifact. Metadata-only components
without a jar/AAR are skipped. It extracts embedded notices, including `classes.jar`
inside AARs. When none exist it follows the cached Maven POM parent chain and includes
the declared Apache 2.0 license plus its complete terms. Unknown/missing licenses fail
the collection. `provenance.json` records coordinates, archive checksums and the method.
`Apache-2.0.txt` is the unmodified terms embedded in AndroidX core 1.17.0.

`custom.json` lists application, asset and native SDK notices. These have separate
provenance: `docs/LICENSES.md`, `libs/lib-t3-rust.lock` (AAR META-INF/LICENSE), and
`libs/omabridge-native.lock` (upstream LICENSE at the pinned source). Keep this list
and the copied texts current when those pins change. The two native SDKs supply their
own project licenses; their internal native transitive dependency inventory is not
exported by the Maven graph. This collector does not certify that inventory.

The graph covers release dependencies; debug-only tooling and test dependencies are
not advertised as production APK components. The dependency snapshot and copied
notices must be reviewed and committed together after upgrades.
