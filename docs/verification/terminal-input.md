# Terminal raw input and compact chrome — 2026-11-01

Base: `fix/outbox-collapse-composer` (PR105). No merge or physical-device access.

## Cause and changes

The old dummy InputConnection kept composition in a separate String while Android's
selection, composing-region replacement and surrounding-text queries used an unrelated
editable. OEM IME recomposition therefore had no consistent editor state. The editor
also advertised ordinary text instead of a raw/password variation. The Xiaomi evidence
`/tmp/o3rel/t.png` shows a suggestions strip, `Echo o3-o3-terminal-` and the resulting
command-not-found error.

The terminal now advertises visible-password/no-suggestions text, no caps mode, and no
extract/fullscreen editor. A real editable owns composition and selection; the IME can
query and replace it. Only commits and finished composition cross the PTY boundary,
after clearing that buffer and its composing spans. Repeated finish calls cannot resend
text. Surrounding deletions edit pending composition first; remaining deletes map to
DEL/forward-delete, with separate UTF-16 and code-point handling. CRLF/LF commits map to
terminal CR. IME and physical-key Enter finish pending composition before sending CR.
Hardware control/alt/navigation keys and character sequences remain supported.

The header resolves the saved host label instead of exposing environment/thread IDs.
Host and cwd share one ellipsized line with four accessible 48dp icon actions. Existing
14 terminal keys and their translations in all 20 hand-kept locale files are reused.

A renamed FiraCode Nerd Font Mono 3.4.0 subset provides Powerline, Seti, Devicons,
Codicons, Font Awesome, Linux and Octicons prompt glyphs. It is 706,960 bytes, contains no
Latin glyphs, and is selected only for private-use cells; system monospace still determines
terminal cell dimensions. `tools/terminal-font-subset.py` records its ranges and rebuild
procedure. Fira Code/OFL, Font Awesome and Nerd Fonts notices ship offline.

Viewport dimensions continue to come from measured cell width and the actual Android
view bounds. TerminalSession retains the latest viewport and applies it after attach,
including keyboard/layout changes that occur while attach is in flight. Observer sessions
continue to retain host dimensions without sending resize mutations.

## Verification

Java: `mise where java@temurin-21`; Android SDK: `$HOME/.android-sdk`.
All Gradle invocations used `--max-workers=2`, sequentially.

- `:core:test`: debug and release each **529 pass, 5 skip**, no failures.
- `:app:testDebugUnitTest`: **570 pass, 1 skip**, no failures.
- `verifyRoborazziDebug`: **168 unchanged** after updating four license goldens
  (two sizes × two themes); reviewed changes are solely the notice count 127 → 128.
- `lint`: **0 errors**; app 110 warnings, core 2 warnings.
- `tools/import-ot3-strings --check`: **261 × 20**, pass.
- Hand-kept terminal strings: **14 × 20**, key parity pass; no new untranslated UI text.
- `tools/about-notices/check.py`: **128 notices**, pass.
- `:app:assembleDebug -Pot3a.requireBridgeSdk=true`: pass; existing native artifacts
  match the lock pins and passed mandatory bridge verification.
- `:app:connectedDebugAndroidTest` filtered to `TerminalScreenDeviceTest`:
  **5 pass, 0 skip/fail**, API34 emulator `emulator-5586`, explicitly selected through
  `ANDROID_SERIAL`. Includes real shell `input text echo%so3-terminal-ok` plus Enter,
  asserting the exact `echo o3-terminal-ok\r` stream. Emulator stopped afterwards.
- Eight new JVM regressions cover raw EditorInfo, compose/recompose/commit, local and
  remote deletes, Unicode code-point deletion, pasted newlines, adb-style key events,
  hardware Enter, glyph coverage and resizing during attach/keyboard geometry changes.
  One new device regression exercises real Android input injection.

APK: `/home/node/.cache/o3-android-artifacts/terminal-input/app-debug.apk`.
SHA256: `4d45ee27c9db0131e983c00a8a4134e6443f9371b39a470b1c47ff15ed7f4106`.
Validation/device logs are stored alongside the APK.

The Xiaomi and `ot3-android-all-hosts` checkout were not changed. SwiftKey acceptance
on the physical Xiaomi remains a follow-up; emulator tests do not establish OEM behavior.
