# Isolated o3-server Android regression — 2026-11-01

Worktree branch `feat/o3-server`, based on `a56791c`. The original checkout and real
T3 instance were left unchanged. Android 34 emulator `emulator-5570`, server release
`ee306a7` from o3-server main, isolated profile and repository under
`/var/tmp/o3-build/android`, ADB reverse to loopback port 38900; no physical phone or
public routes used. The one-off emulator exception was explicit.

The historical crash log establishes an IllegalArgumentException at AppScreens.kt:55:
the ChatsGraph owner was absent while an outgoing Home screen composed. Home and
its sheets now skip composition when the graph owner is missing or destroyed, keeping
the shared owner for live screens. Startup pairing instrumentation covers replacing
the Chats graph while Home is still composing. Three real-emulator navigation tests
pass. The debug APK built successfully and completed these actual app flows against
real providers, verified with RPC projections and screenshots:

- New Haiku chat + real response + generated summary title.
- New Codex chat + real response (local prompt fallback title expected).
- Supervised Write approved in-app; expected file bytes on disk.
- Blue image selected, uploaded, sent and identified by Codex.
- Terminal echo and stream output.
- Server down: Offline/retrying at 4.22s and 13.45s, port connection refused.
- Server restart: automatic Connected by 35.99s, another completed reply in same chat.

Evidence and resumable helper: o3-server `docs/E2E.md`, `docs/android-e2e/` and
`tools/android-e2e/`. Raw evidence is local at `/var/tmp/o3-build/android/evidence`.
Server HWM max sampled during inference: 16,900 KiB; entire run 17,332 KiB, provider
children excluded. No server bug established. Emulator/server stopped, app test data,
profile and copied credentials deleted.

Validation limitation: core unit suite has 541 tests, 190 skipped, no failures. App
suite has 585 tests, 16 skipped, eight failures in its initial Java21 run: seven in
PullRequestsViewModelTest and one VisualRenderingComposeTest timeout. Reverting only
AppScreens to the unmodified base and rerunning those two classes reproduces all seven
PullRequestsViewModelTest failures (13 tests total); the visual test passes. These are
not attributed to this navigation change. Java17 cannot run SDK36 Robolectric tests;
use Java21 and explicit JAVA_TOOL_OPTIONS=-Djava.io.tmpdir under the disk test root.
