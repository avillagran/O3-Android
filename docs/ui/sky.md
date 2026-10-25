# The sky header

Companion to [UI_SPEC.md](../UI_SPEC.md) section 3.5. Code in
`app/src/main/kotlin/dev/avillagran/ot3android/ui/sky/`; ot3 sources at `7b5b1de`.

## Pieces

| File | Role | ot3 counterpart |
|---|---|---|
| `StarfieldShader.kt` | `STARFIELD_AGSL`, the AGSL port, and `SkyUniforms` (one frame's uniforms by name) | `ui/assets/shaders/starfield.frag` |
| `SkyMath.kt` | the shader's math in Kotlin (hashes, layers, twinkle, comets, colours, fade) and `shade()`, a CPU reference of `main()` | `starfield.frag`; `ui/qml/T3Starfield.qml:55-92`; `ui/qml/T3Panel.qml:1121-1128` |
| `SkyClock.kt` | clock, drift, eased energy, tick pace, Auto's slow-tick counter | `ui/qml/T3Starfield.qml:14-117`; `ui/qml/T3Ticker.qml` |
| `SkyCanvasPlan.kt` | what the Canvas fallback draws: stars and comets from the same grid hash, no nebula | none (ot3 always has a GPU) |
| `SkyHeader.kt` | the composable: frame loop, AGSL or Canvas drawing | the header `T3Starfield` in `T3Panel.qml:1121-1128` |
| `SkyBrandRow.kt` | wordmark row with logo and trailing slots | `ui/qml/T3Panel.qml:1131-1160` |
| `SkyPalette.kt` | `SkyPalette`, `SkyMotion` and the platform limits | theme `sky` object (`omarchy-themes.md`); `T3Preferences.qml:260` |

Public API: `SkyHeader(palette, motion, energy, modifier, brandHeight, onAutoDegrade, content)`,
`SkyPalette` (+ `SkyPalette.derive(background, foreground, accent)`), `SkyMotion`
(`Full`, `Light`, `Off`, `limitedBy(animatorScale, powerSave)`), `SkyBrandRow(foreground,
dim, modifier, logo, trailing)`, `SkyLogoPlaceholder`, `SkyDefaults.BrandHeight`.

## Uniforms

Every uniform of `starfield.frag` except `qt_Matrix`, in the same order, plus one.
All lengths are dp (ot3 px = dp, UI_SPEC 3.3).

| AGSL uniform | Type | Value on Android | ot3 source |
|---|---|---|---|
| `pixelDensity` | float | `Density.density`; `q = fragCoord / pixelDensity` | Android only (Qt's texture coordinates are already logical) |
| `opacity` | float | 1 (Compose applies alpha in layers) | `qt_Opacity` |
| `drift` | float | accumulated by `SkyClock`: `dt · (5 + 37 · energy)` dp | `T3Starfield.qml:108` |
| `clock` | float | seconds while the loop runs, steps capped at 0.25 s | `T3Starfield.qml:107`; `T3Ticker.qml:52` |
| `meteors` | float | `min(1, meteorRate) · energy` in Full, and in Light because this is a header sky; 0 in Off | `T3Starfield.qml:47-48` |
| `brightness` | float | `SkyPalette.brightness`: 0.9 light, 1.25 dark | `T3Panel.qml:1126` |
| `nebula` | float | `SkyPalette.nebula`: 0.55 | `T3Panel.qml:1126` |
| `scroll` | float | 0 (no scroll parallax on the header) | `T3Starfield.qml:40-46` |
| `fade` | float | `1 − brandHeight · 0.85 / height`, brand row 62 dp | `T3Panel.qml:1127` |
| `size` | float2 | header size in dp | `T3Starfield.qml:53` |
| `origin` | float2 | (0, 0): one sky, nothing to line up with | `T3Panel.qml:1124` |
| `star` | float4 | foreground | `T3Starfield.qml:59` |
| `accent` | float4 | accent | `T3Starfield.qml:60` |
| `base` | float4 | light: background tinted 6 % to foreground; dark: background ÷ 2.4 (`Qt.darker`) | `T3Panel.qml:1125` |
| `meteor` | float4 | foreground on light skies, white on dark | `T3Starfield.qml:62` |
| `ink` | float | `SkyPalette.ink`: 1.7 light, 1 dark | `T3Starfield.qml:63` |

Colours are plain `float4` set with `setFloatUniform`, encoded sRGB as Qt passes them;
the shader returns premultiplied colour like ot3's.

## Shader, line by line

| `starfield.frag` | AGSL | Note |
|---|---|---|
| 7-24 uniform block | globals | `qt_Opacity` → `opacity` |
| 26-36 `hash12`, `hash22` | same | `SkyMath.hash12/hash22`; `hash12` equals ot3's JS copy in `T3Starfield.qml:77-82` |
| 39-53 `layer()` | same | |
| 56-79 `meteor()` | `meteorAt()` | renamed: in AGSL the `meteor` colour uniform is a global; local `fade` → `fadeIn` so it does not shadow the `fade` uniform |
| 82 `q = qt_TexCoord0 * size` | `q = fragCoord / pixelDensity`, `uv = q / size` | density |
| 87-90 three layers | same, third layer written `* 1.0` | constants checked against `SkyMath.LAYERS` by `StarfieldShaderSourceTest` |
| 94-95 comets only above 0.001 | same | the guard is what makes energy 0 mean no comets: `hash12(0, 0)` is exactly 0 |
| 97-116 edge, ink, nebula, compositing | same | |

## Frame loop

`withFrameNanos` in a `LaunchedEffect` that runs only while the header has a non-empty
window bound and the lifecycle is `RESUMED` (`repeatOnLifecycle`). Each frame asks
`SkyClock.onFrame`; it ticks when its interval has passed (4 ms tolerance) and asks to
`delay` until 12 ms before the next, so the loop does not wake on every vsync. A tick
bumps a snapshot counter read only in `onDrawBehind`: no recomposition, just a redraw.

Interval, ot3's "saver" smoothness (`T3Ticker.qml:22-27`) with its paces
(`T3Starfield.qml:98-99`):

| State | Interval |
|---|---|
| Auto (Full + `onAutoDegrade`) or Light, nothing works, no comet | 125 ms |
| something works, or explicit Full, or a comet on screen (looked up 0.13 s ahead) | 33 ms |
| Light, no comet on screen | at least 40 ms |

## Reduced motion

- **Off**: one static frame; the clock is frozen where it was, no comet.
- **Light**: ot3 keeps comets only in header skies (`T3Starfield.qml:47-48`); the sidebar
  header is the only sky on Android, so Light shows the same picture as Full at ≤ 25 fps.
- **Auto**: `SkyHeader(motion = Full, onAutoDegrade = { … })`. While something works and
  no comet is on screen, a tick more than twice its interval late adds 1, a timely one
  removes 0.5; above 90 the header switches to Light for its lifetime and calls
  `onAutoDegrade` once (`T3Starfield.qml:112-116`).
- **Platform**: animator duration scale 0 → Off; battery saver → Light at most
  (`SkyMotion.limitedBy`, read live by `SkyHeader`). Applying it twice is harmless.

## Canvas fallback (API 24–32)

`SkyCanvasPlan.build(uniforms)` walks the visible cells of each layer with
`SkyMath.star`, giving each star a core disc (radius `0.6 r + 0.45`, where the
shader's core smoothstep is halfway), a radial halo sampled from `0.3 exp(−d²/7r²)` and the
colour of the shader's tint at its centre. Comets are a tapered quad (half widths
0.85 → 0.3 dp) with `tail²` gradient stops and a head glow from `exp(−d²/10)`. The base
fade is a vertical gradient sampled from the shader's smoothstep. No nebula.

## Verification without a device

- JVM: `SkyMathTest`, `SkyUniformsTest`, `SkyClockTest`, `SkyCanvasPlanTest`,
  `StarfieldShaderSourceTest` (uniform names and arity match what Kotlin sets; layer and
  comet constants match `SkyMath`; no GLSL left; every call is defined or a builtin).
- Instrumented (compiles; runs when the emulator is back): `StarfieldShaderCompileTest`
  builds the `RuntimeShader` and sets every uniform on API 33+.
- Skia: `scripts/check-starfield-agsl.py` compiles the AGSL with skia-python's
  `RuntimeEffect` (the same SkSL front end as Android) and renders a frame; against
  `SkyMath.shade` at the same clock the mean difference is under 0.01 / 255.
  Images in [compare/ui-1c](compare/ui-1c/README.md).
