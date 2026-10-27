# Notification controls — 2026-10-26

Five persisted alert switches pass preferences directly to Rust awareness.view. Turning an alert back on does not replay an already consumed transition (native JNI test). Full lock-screen content remains default; status-only/hidden also reapply to existing cards and grouped summaries without resetting expiration or replaying sound. Settings refresh promotion availability on resume.

API36.1 screenshots: alert-preferences.png and lockscreen-preferences.png show the real Settings route. These are settings captures, not proof of an actual locked device. JVM app190/core279 passed +5 core opt-in skips; lint and goldens passed. Full app instrumented suite: 32 passed, one live opt-in skipped.

Xiaomi update: main343158e APK sha256 cf44dc505a7dcde2620be33b9a82bfbc114d64ae6f670fc1d0be2f2f2e97cdb9 installed with install -r after WiFi recovered. MainActivity opened the existing thread; Andrés subsequently confirmed sending a message through the new UI. This phone build predates the controls in this increment. No environment reset or token change.

Pre-release: https://github.com/avillagran/ot3-android/releases/tag/apk-2026-10-26-343158e

Historical pending list for this controls tranche: lockscreen/Doze/grouped lifecycle
evidence. Later [system lab](../system-lab/README.md) and
[resilience lab](../resilience/README.md) provide bounded evidence; their remaining
limits, productive NODE/VQ discovery and remaining reader/composer parity are still open.
