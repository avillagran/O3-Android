# Show and hide styles

Appearance now persists Nebula, Hyperspace (`warp`), Fold, Spring and Instant,
propagated through the application theme to both horizontal and vertical reveals.
Default and unknown stored values select Nebula. All six labels and their 20
localizations come from lib-t3-rust 0.3.5's AAR through `tools/import-ot3-strings`.

Reference: ot3 `7b5b1de6db5e24c6b2d77bc0497b361380fead81`,
`ui/qml/T3Reveal.qml`, `T3Motion.qml`, and the existing `reveal.frag` port.

| Style | Normal duration | Qt easing | Layout share |
| --- | ---: | --- | --- |
| Nebula | 560 ms | InOutCubic | min(1, extent / 0.6) |
| Hyperspace / Warp | 460 ms | InOutQuart | extent |
| Fold | 500 ms | InOutSine | extent |
| Spring | 520 ms | OutBack, overshoot 1.3 | extent |
| Instant | none | direct target | target |

Slow (1.6), Normal (1), and Fast (0.6) multiply only reveal durations. Children
retain their natural dimensions; Spring slides and stretches along the attached
axis. Each reveal has a remembered random seed. Nebula/Warp/Fold select shader
branches 0/1/2 while moving; settled content has no active effect. Clipping applies
only to a partial layout share or movement. The artistic AGSL code is unchanged.

API <33, non-accelerated views, or shader/effect construction failure select
Spring, except Instant remains Instant. Spring/Instant do not create a shader.
Off and Android reduced motion take a direct target branch, including changes
mid-animation with an unchanged target. Power saver still caps the shared policy
at Light. Other animations, workspace composition and drafts remain independent
of the show/hide style and pace.

## RenderEffect correction

Visual verification exposed a pre-existing defect: the cached RenderEffect was
created before any uniforms were set. Android's native effect copies the Skia
shader builder, whose uniform storage uses copy-on-write. Updating the original
RuntimeShader therefore did not update the cached effect. The moving regions
were empty and appeared only when the settled layout removed the effect.
See [Android's JNI factory](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/libs/hwui/jni/RenderEffect.cpp),
[Skia's builder copy](https://android.googlesource.com/platform/external/skia/+/refs/tags/android-14.0.0_r1/src/effects/imagefilters/SkRuntimeImageFilter.cpp),
and [uniform copy-on-write](https://android.googlesource.com/platform/external/skia/+/refs/tags/android-14.0.0_r1/include/effects/SkRuntimeEffect.h).

Both axes now create the attached RenderEffect after setting the current frame's
uniforms. RuntimeShader and the capability probe remain cached. Settled rendering
is unchanged. This is separate from the sky/aura ShaderBrush paths.

The actual GPU regression failed on cached-effect APK `f56e91bb…`: Nebula at
progress `0.5416444` had **zero** colored marker samples. After correction:

| Style | Intermediate progress | Horizontal samples | Vertical samples |
| --- | ---: | ---: | ---: |
| Nebula | 0.5416444 | 62174 | 38418 |
| Warp | 0.84362316 | 71756 | 44196 |
| Fold | 0.6182495 | 24551 | 24856 |

The assertion samples actual PixelCopy output during motion, so a compiled shader
or correct easing alone cannot pass it. Logs are in
`/tmp/reveal-style-gpu-baseline.log` and `/tmp/reveal-style-final-gpu-logcat.txt`.

## Accepted instrumented clips

- [Warp — 6.606 s](warp-frame-clock-api34.mp4): RGB streaks and axial smearing.
- [Fold — 4.376 s](fold-frame-clock-api34.mp4): alternating panels and lit creases.

Both show closing and opening along both axes, on an API34 software GPU with
hardware-accelerated Android views. The recordings were inspected at intermediate
frames. They are **instrumented frame-clock scenarios**, sampled at 32 ms with a
separate recording cadence. Playback does not establish wall-clock duration,
native frame pacing, physical-GPU or desktop aesthetic parity. These are real
screen recordings; only irrelevant tails were trimmed by stream copy. No speed
change, timestamp retiming, or PNG-to-video substitution was used.

At capture, the owned ComponentActivity was resumed and had window focus;
UiAutomation's active package was `dev.avillagran.ot3android`. All three animation
scales read `1.0`. Full metadata, hashes and APK identities are in
[verification.json](verification.json).

## Verification

- Core `AppearancePreferencesTest`: **7 passed**, including default, unknown,
  every style round trip and disk reopen.
- Focal app JVM tests: **11 passed**, including 90 combinations of style × pace ×
  shader availability × Full/Light/Off, source easing samples, and platform limits.
  API28 Compose exercises both axes between animation endpoints and snaps Instant
  mid-show and mid-hide. Existing workspace/draft and application-theme pace tests
  pass. The strengthened API28 endpoint test was checked again separately.
- API34 real MainActivity selection/recreation, both-axis style/Instant/Off and
  settled-pixel checks, plus the real workspace Slow/Fast pace test: **3/3 passed**
  in 76.572 s before the RenderEffect correction; selection/layout code is unchanged
  by that correction.
- Final API34 colored-marker regression plus both opt-in recording scenarios:
  **3/3 passed** in 49.93 s after correction.
- Final app/test APK builds and lint passed with bridge SDK and host JNI required.
  Final lint: **0 errors**; existing warnings remain. String importer check and
  `git diff --check` passed. No full suite was repeated or golden snapshots changed.

## Device setup and cleanup

Only the owned read-only API34 clone at `emulator-5580` was used. Initial startup
attempts failed before QEMU because `/tmp` is a separate tmpfs with less than
2 GiB free. Moving the same clone to the root filesystem resolved that check.
An early capture under a 6 GiB cgroup ceiling was killed at that exact maximum;
kernel logs confirm `CONSTRAINT_MEMCG` for the owned unit, not a global host OOM.

A separately authorized, bounded resource probe used the same unit/software GPU,
one core, Max8GiB, no MemoryHigh and no swap allowance. Immediately before boot,
MemAvailable was 12,922,144 KiB (guard: at least 12 GiB) and root free space was
2,167,021,568 bytes. The first visible captures exposed the uniform defect above.
After its demonstrated correction, the final focal run and captures completed
under that same running guest. The final peak was 7,254,208,512 bytes, with zero
cgroup high/max/OOM events. No unrelated process, OOM protection, or public AVD
was modified to make the capture work. Rejected earlier clips were not promoted.

The fresh guest's initial values were animator `null` (default 1), window `1.0`,
transition `1.0`; they were temporarily 0 for the CI-like settings test. Final
cleanup restored and read back `null/1.0/1.0`. Interactive verification of the
Remove animations toggle was not completed: Jev reported `no_key`. The loaded
Android control skill (`~/.hermes/skills/autonomous-ai-agents/jev-android-control/SKILL.md`)
says “Load and follow jev-computer-use”; that skill
(`~/.codex/skills/jev-computer-use/SKILL.md`) says “If the driver, the key or the
target is unavailable: stop and say what is missing. Do not improvise another
way to control the screen.” Interactive GUI control stopped. No alternate
Settings route or key setup was attempted; the existing instrumented scenarios
continued within their independent authorized scope. No GUI toggle state is claimed.

Explicit stop released the emulator (`MainPID=0`, no ADB transport) and Device
panel. The emulator aborted **during shutdown**, after completed tests:
`Result=core-dump`, `ExecMainCode=3`, `ExecMainStatus=6/ABRT`. The failed unit and
journal were preserved. All seven original AVD configuration/disk hashes matched
before and after. Owned clone/symlink and temporary overlays whose exact backing
paths identified this clone were removed; other artifacts were untouched.
Terminal and cleanup evidence lives under `/tmp/reveal-style-final-*.txt/json`.
