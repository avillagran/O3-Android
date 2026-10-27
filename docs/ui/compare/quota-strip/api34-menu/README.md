# API34 quota menu regression

PR51's old CI failed QuotaStripDeviceTest Latte at line85, waiting for the strip to
hide after typing, opening overflow and touching the toggle. The artifact contains
Gboard show/hide and IME inset-animation timeouts, without a screenshot, touch bounds
or observed preference value. The old failure's precise cause is **not established**.
The unchanged behavior passed three local API34 graph cases with diagnostic logging;
those logs were removed from production before final validation.

Two separate menu defects were reproduced deterministically on the actual API34
Compose runtime using the real menu and AppearancePreferences with a gated DataStore:

- Before the first DataStore emission, the old menu was enabled and interpreted the
  temporary default `providerUsage=false` as loaded state. A persisted true value
  could therefore be presented and inverted incorrectly.
- An accepted write suspended in DataStore was cancelled when the popup composition
  was removed, because rememberCoroutineScope belonged to that popup.

Both regression tests failed against the old logic. The menu now shows the existing
native Loading label and stays disabled until the setting arrives. Persistence belongs
to AppGraph.appScope, and inversion occurs atomically inside the DataStore transaction,
so a collected snapshot does not overwrite a newer preference. This is local UI
preference handling; Rust quota projections and shared stream ownership are unchanged.

The real graph regression keeps the software keyboard open for the portrait toggles:
it waits for visible IME insets before touching overflow, waits for the loaded native
Show/Hide label and enabled/displayed row, then asserts the keyboard and draft remain
after both toggles. It does not hide the keyboard to bypass the failing path. The
existing landscape stage still dismisses IME before testing the separate strip/limits
route. show_ime_with_hard_keyboard is saved and restored in test setup/teardown.
Settings→Appearance→Back→Settings header Back uses the merged PR52 route.

All captures here use synthetic transport with real native quota projection, actual
WorkspaceFixtureActivity/navigation and Android14/API34 on emulator-5580. No phone,
production environment, external dispatch or OEM acceptance is involved.

## Verification, 2026-10-27

Merged origin/main18f7575 (Settings/theme/provider icons) into this PR branch; icon
registry/notice conflicts retain both provider marks and viewer zoom controls.
Final API34 instrumentation passed **9/9 in 25.952s**: menu2, quota graph3 and image
viewer4. Focused JVM passed **18/18** across AppearancePreferences5,
SharedQuotaSource/ReaderImageZoom9 and merged icon registry4. App/test APK build and
final lintDebug passed. [Verification record](verification.json).

[Latte hidden](thread-before.catppuccin-latte.png) and
[Latte shown](thread-after.catppuccin-latte.png) retain the same draft and visible
software keyboard. Tokyo's interaction/inset assertions passed too; its screenshots
were omitted because Android captured an unpainted keyboard surface during transition.
These are runtime regression artifacts, not new visual-parity acceptance.

Reproduce on an isolated API34 emulator using the built debug/test APKs:

```sh
adb -s emulator-5580 shell am instrument -w \
  -e class dev.avillagran.ot3android.ui.usage.QuotaToggleMenuTest,dev.avillagran.ot3android.ui.usage.QuotaStripDeviceTest,dev.avillagran.ot3android.ui.thread.ReaderImageZoomDeviceTest \
  dev.avillagran.ot3android.test/androidx.test.runner.AndroidJUnitRunner
```

Do not interpret the passing suite as a reproduced diagnosis of the original CI
failure or physical-keyboard/OEM acceptance. No native/protocol or image-viewer
behavior was changed in this follow-up.


[API34 CI readiness follow-up](ci-readiness.md) records PR60's IME/Settings
failures, the unchanged baseline result and the focused test-only verification.
