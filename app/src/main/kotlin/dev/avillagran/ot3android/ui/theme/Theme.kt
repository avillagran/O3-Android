package dev.avillagran.ot3android.ui.theme

import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import dev.avillagran.ot3android.core.data.prefs.AppearanceMode
import dev.avillagran.ot3android.core.data.prefs.AppearanceSettings
import dev.avillagran.ot3android.core.theme.ThemeTokens
import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException

/**
 * ot3's look (UI_SPEC 3.1-3.4): [colors] as [LocalOt3Colors] and as the Material scheme of
 * docs/ui/tokens.md section 2, ot3's type scale, radii and motion. With no arguments it uses
 * the default theme of the system appearance, which is what previews and tests want; the
 * app goes through [Ot3AppTheme].
 */
@Composable
fun Ot3Theme(
    colors: Ot3Colors = defaultOt3Colors(isSystemInDarkTheme()),
    typography: Ot3Typography = Ot3Typography.Default,
    motion: Ot3MotionPolicy = LocalOt3Motion.current,
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(colors) { colors.toColorScheme() }
    CompositionLocalProvider(
        LocalOt3Colors provides colors,
        LocalOt3Typography provides typography,
        LocalOt3Motion provides motion,
        // ot3 has no tonal elevation; surfaces separate by tint and hairline.
        LocalTonalElevationEnabled provides false,
    ) {
        ProvideOt3Ticker(motion) {
            MaterialTheme(colorScheme = colorScheme, typography = typography.material, shapes = Ot3Shapes, content = content)
        }
    }
}

/** Reads the current ot3 tokens: `Ot3Theme.colors.working`, `Ot3Theme.typography.mono`. */
object Ot3Theme {
    val colors: Ot3Colors
        @Composable @ReadOnlyComposable get() = LocalOt3Colors.current

    val typography: Ot3Typography
        @Composable @ReadOnlyComposable get() = LocalOt3Typography.current

    val motion: Ot3MotionPolicy
        @Composable @ReadOnlyComposable get() = LocalOt3Motion.current

    val spacing get() = Ot3Spacing
    val radii get() = Ot3Radii
    val sizes get() = Ot3Sizes
}

/**
 * The app's root theme: [settings] picks the appearance and the theme of each appearance
 * (an unknown id, or Material You below Android 12, falls back to the default), the motion
 * level follows the preference and the platform, and the system bar icons follow the
 * appearance on transparent, edge-to-edge bars.
 */
@Composable
fun Ot3AppTheme(
    catalog: OmarchyThemeCatalog,
    settings: AppearanceSettings,
    liveTheme: dev.avillagran.ot3android.core.theme.ThemeSelection.Live? = null,
    content: @Composable () -> Unit,
) {
    val dark = liveTheme?.let { !it.isLight } ?: when (settings.mode) {
        AppearanceMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        AppearanceMode.DARK -> true
        AppearanceMode.LIGHT -> false
    }
    val themeId = if (dark) settings.darkThemeId else settings.lightThemeId
    val context = LocalContext.current
    val materialYou = liveTheme == null && themeId == AppearanceSettings.MATERIAL_YOU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val bundled = remember(catalog, themeId, dark, materialYou, liveTheme) {
        liveTheme?.let { Ot3Colors.fromTheme(ThemeTokens.parse(it.tokens)) }
            ?: if (materialYou) null else catalog[themeId]?.takeIf { it.isLight != dark }?.let(Ot3Colors::fromTheme) ?: catalog.defaultColors(dark)
    }
    // Material You goes through theme.tokens off the main thread; until it answers, the colours
    // already shown stay (nothing is drawn before the first ones, so no state is lost).
    var wallpaper by remember { mutableStateOf<Pair<Boolean, Ot3Colors>?>(null) }
    if (materialYou) {
        LaunchedEffect(catalog, dark, context) {
            val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            wallpaper = dark to try {
                materialYouColors(scheme, catalog.defaultTheme(dark))
            } catch (error: CancellationException) {
                throw error
            } catch (_: T3Exception) {
                catalog.defaultColors(dark)
            }
        }
    }
    var shown by remember { mutableStateOf<Ot3Colors?>(null) }
    val colors = bundled ?: wallpaper?.takeIf { it.first == dark }?.second ?: shown ?: return
    SideEffect { shown = colors }
    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(activity, colors.isLight) {
        val style = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { !colors.isLight }
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { }
    }
    Ot3Theme(colors = colors, motion = rememberOt3Motion(settings.animation), content = content)
}

/**
 * Material You (UI_SPEC 3.1.3): the wallpaper scheme as a `colors.toml` (background =
 * surface, foreground = onSurface, accent = primary, muted = outline) with the colours of
 * [defaults], the default theme of the same appearance, for status, project slots and sky,
 * so "Failed" stays red; the lib's `theme.tokens` makes every role from it.
 */
suspend fun materialYouColors(scheme: ColorScheme, defaults: OmarchyTheme, domain: DomainCalls = DomainEngine.shared): Ot3Colors {
    fun hex(rgb: Int) = "#%06x".format(rgb and 0xFFFFFF)
    val toml = buildString {
        listOf("background" to scheme.surface, "foreground" to scheme.onSurface, "accent" to scheme.primary, "muted" to scheme.outline)
            .forEach { (key, color) -> append("$key = \"${hex(color.toArgb())}\"\n") }
        THEME_COLORS.forEach { (key, base) -> defaults.base[base]?.let { append("$key = \"${hex(it)}\"\n") } }
    }
    return Ot3Colors.fromTheme(ThemeTokens.fromColorsToml(toml, Ot3Colors.MATERIAL_YOU_ID, domain))
}

/** `colors.toml` keys of a theme's own colours and their `theme.tokens` `base` names. */
private val THEME_COLORS = listOf(
    "red" to "red", "orange" to "orange", "yellow" to "yellow", "green" to "green", "cyan" to "cyan", "blue" to "blue",
    "magenta" to "magenta", "bright_blue" to "brightBlue", "bright_magenta" to "brightMagenta",
)

/** The default Omarchy theme of an appearance (AppearanceSettings.DEFAULT_THEMES). */
fun OmarchyThemeCatalog.defaultTheme(dark: Boolean): OmarchyTheme {
    val id = if (dark) AppearanceSettings.DEFAULT_THEMES.dark else AppearanceSettings.DEFAULT_THEMES.light
    return this[id] ?: error("omarchy-themes.json has no default theme $id")
}

/** [defaultTheme]'s colours. */
fun OmarchyThemeCatalog.defaultColors(dark: Boolean): Ot3Colors = Ot3Colors.fromTheme(defaultTheme(dark))

@Composable
private fun defaultOt3Colors(dark: Boolean): Ot3Colors {
    val context = LocalContext.current
    return remember(dark) { OmarchyThemeCatalog.loadBlocking(context).defaultColors(dark) }
}
