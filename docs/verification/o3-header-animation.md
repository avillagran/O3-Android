# O3 header animation verification — 2026-10-31

Checkout: `feat/o3-header`, with `origin/main` merged (main `d48d502`).
Device: scripts/emulator.sh, API 34 x86_64, emulator-5580. No physical device used.

## Defect and correction

The brand's perpetual coroutine used ordinary `withFrameNanos` and ignored the
system animator setting and lifecycle. It could run with animations disabled and
continue requesting frames while the app was paused. Decorative infinite frames
also bypassed Compose's infinite-animation policy.

The loop now uses `withInfiniteAnimationFrameNanos`, runs only inside
`repeatOnLifecycle(RESUMED)`, checks `ValueAnimator.areAnimatorsEnabled()`, and
observes the existing system motion policy as well as explicit reduced motion.
Resume resets the frame origin, excluding time spent in the background. The
Desktop scene durations, holds, pace, drawing and tap replay are preserved.
There is no production test flag.

## Emulator evidence

`HomeBrandIdleTest` uses the v2 Compose rule and a 10-second idle bound. It sets
and restores the real system animator scale, verifies ValueAnimator's observed
state, composes Home, crosses the initial 2400 ms hold, and checks idle. With
animators disabled, it also checks every brand pixel remains unchanged. A second
case keeps animators enabled and checks idle past the initial hold.

Before the correction, the disabled-animator pixel assertion failed: the brand
changed despite `ValueAnimator.areAnimatorsEnabled() == false`. Afterward, both
cases pass. The original Home/navigation focal cases completed locally even
before this correction: the CI 40-minute timeout itself was not reproduced.
Consequently this verifies the animation defect, not a definitive causal trace
of the CI cancellation. CI attempt 2 advanced near the end of the suite and then
stopped reporting progress before cancellation.

- Affected tests: HomeBrandIdleTest, HomeScreenTest, Ot3NavHostTest,
  SystemReducedMotionTest: 7 passed, 0 failures; Gradle wall time 18.301 s.
- Full `:app:connectedDebugAndroidTest --max-workers=2`: 91 passed, 9 skipped,
  0 failures; 100 total from XML. Wall time **231.275 s (3 min 51.275 s)**;
  instrumentation time 223.286 s. System animator scale normally enabled; the
  regression cases explicitly exercise both settings and restore the original.
- AGP's console double-counts skipped cases; counts above come from XML.

Java: Temurin 21 via `mise where java@temurin-21`.
SDK: `ANDROID_HOME=$HOME/.android-sdk`.

## JVM, screenshots and lint

With Java 21 and `--max-workers=2`, the final combined run of
`:core:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug
:core:lintDebug :app:lintDebug` passed (57 s).

- Core: 227 passed, 176 skipped; app: 430 passed, 16 skipped; zero failures.
- Host `libt3_jni.so` is absent; JNI-dependent and opt-in tests remain skipped.
- Roborazzi: 126 golden images. The 16 SidebarScreenshotTest references newly
  inherited from main used the old T3 header; inspected and re-recorded with this
  branch's O3 header. All references verify; scene renderer goldens preserved.
- Lint: zero errors; core 2 warnings, app 90 warnings.
- API 24–25 retain the observed system-scale fallback; the ValueAnimator call is
  guarded for API 26+. Device regression uses API 26+.
