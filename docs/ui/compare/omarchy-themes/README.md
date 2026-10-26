# Omarchy theme integration — 2026-10-26

The 22 bundled palettes now have previews drawn from Rust theme.tokens colors. Choosing one applies it immediately to this phone, selects the corresponding dark/light appearance, and leaves desktop-follow mode. Previously it only changed a fallback, so selecting a palette could appear to do nothing. Turning follow back on restores the connected environment theme. The desktop is never modified.

A Refresh desktop theme button exposes the existing refresh operation. Visible foreground polling still runs every three minutes, but reads both small theme files using the cached location; editing colors.toml without renaming theme.name is now detected and cached. Background polling remains stopped. Theme parsing/roles remain in Rust.

Reference: ot3 ui/qml/T3Preferences.qml:246 (Omarchy appearance) and the existing theme.tokens catalog. The Android palette gallery is a native selection affordance, not a claim that desktop provides the same picker.

Evidence: gallery.png; retro-82.png; catppuccin-latte.png; tokyo-night.png; palette-selection-final.mp4. Real Android Settings route on API36.1, without connected environments. Instrumented selection test covers immediate mode/follow changes, persistence across Activity recreation and re-enabling desktop follow. JVM app190/core280 passed, five core opt-in skips; lint/goldens passed. Native-source poll scheduling and same-name edits are tested with fake transport.

Read-only observation of the existing Omarchy desktop: current theme retro-82. This does not prove desktop→Android live synchronization: that still needs a reachable authorized environment with filesystem:read. No productive desktop file, route, grant or theme was changed. Xiaomi still has main343158e, installed successfully and confirmed by Andrés sending from the new UI; this gallery increment is not installed there yet.

## Real desktop → Android proof

Follow-up on the same date: the real Omarchy T3 at version0.0.46-nightly.20261013.3042 was read through a private loopback SSH forward with a temporary10-minute session scoped only orchestration:read and filesystem:read. LiveOmarchyThemeDeviceTest saved an independent QA environment in the emulator, used the actual AppGraph/ConnectionManager/LiveThemeSource and native JNI/theme.tokens, and observed Retro82 with fromCache=false. Home displayed real desktop shells; Appearance showed Retro82 · Omarchy · theme QA. See live-desktop-home.png, live-desktop-appearance.png and live-desktop-verification.json. The opt-in instrumented test passed.

The native JVM reader also passed against the real desktop (live-desktop-jvm-report.txt). Theme files were never changed. Session revoked, private credential file removed, loopback forward/reverse ports closed, QA environment/cache removed, preferences restored and emulator stopped.

Reproduction: scripts/live-theme-harness.py under a bounded user unit (RuntimeMaxSec=10min, MemoryMax=512M), with the intended SSH host/CLI/base-dir/remote port, a free loopback forward port and a fresh state directory outside the repo. Reverse only its printed HTTP port to the isolated emulator; run LiveOmarchyThemeDeviceTest with themeLabPort and explicit emulator serial. Capture when /status reports ready, POST /release, then /finish. Never print /config or session.json. The test refuses physical devices.

This supersedes the earlier lack of a desktop→emulator live proof. Productive catalog discovery and desktop→Xiaomi theme following remain unverified.
