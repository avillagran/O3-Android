package dev.avillagran.ot3android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The Material 3 scheme of docs/ui/tokens.md section 2, so Material components keep their
 * behaviour but wear ot3's colours. Roles the table does not list (tertiary container,
 * the fixed roles) take the nearest ot3 role, so no Material baseline colour leaks in.
 * [ColorScheme.surfaceTint] is transparent: ot3 has no tonal elevation.
 */
fun Ot3Colors.toColorScheme(): ColorScheme {
    return ColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentContainer,
        onPrimaryContainer = accent,
        inversePrimary = accent,
        secondary = input,
        onSecondary = background,
        secondaryContainer = accentContainer,
        onSecondaryContainer = foreground,
        tertiary = waiting,
        onTertiary = background,
        tertiaryContainer = accentContainer,
        onTertiaryContainer = foreground,
        background = background,
        onBackground = foreground,
        surface = background,
        onSurface = foreground,
        surfaceVariant = surface,
        onSurfaceVariant = dim,
        surfaceTint = Color.Transparent,
        inverseSurface = foreground,
        inverseOnSurface = background,
        error = failed,
        onError = background,
        errorContainer = failedContainer,
        onErrorContainer = failed,
        outline = cardBorder,
        outlineVariant = divider,
        scrim = Color.Black,
        surfaceBright = rowSelected,
        surfaceContainer = surface,
        surfaceContainerHigh = rowSelected,
        surfaceContainerHighest = cardBorder,
        surfaceContainerLow = changesCard,
        surfaceContainerLowest = background,
        surfaceDim = background,
        primaryFixed = accentContainer,
        primaryFixedDim = accentContainer,
        onPrimaryFixed = accent,
        onPrimaryFixedVariant = accent,
        secondaryFixed = accentContainer,
        secondaryFixedDim = accentContainer,
        onSecondaryFixed = foreground,
        onSecondaryFixedVariant = foreground,
        tertiaryFixed = accentContainer,
        tertiaryFixedDim = accentContainer,
        onTertiaryFixed = foreground,
        onTertiaryFixedVariant = foreground,
    )
}
