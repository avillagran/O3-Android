package dev.avillagran.ot3android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * ot3's colour roles (docs/ui/tokens.md section 1), every one opaque. Components read these
 * for anything ot3-specific (`Ot3Theme.colors.working`) and Material roles only inside
 * Material components.
 */
@Immutable
data class Ot3Colors(
    /** The Omarchy theme id, or [MATERIAL_YOU_ID]. */
    val themeId: String,
    val isLight: Boolean,
    val background: Color,
    val foreground: Color,
    val accent: Color,
    val muted: Color,
    /** Secondary text, fg @ 0.57. */
    val dim: Color,
    val divider: Color,
    /** Bubble and code block, fg @ 0.045. */
    val surface: Color,
    val card: Color,
    val cardBorder: Color,
    val changesCard: Color,
    val rowSelected: Color,
    /** ot3's hover. */
    val rowPressed: Color,
    val rowDraft: Color,
    val field: Color,
    /** Unfocused; focused is [accent]. */
    val fieldBorder: Color,
    /** Off track; on is [accent]. */
    val switchOff: Color,
    val inlineCode: Color,
    /** Focused composer border, accent @ 0.45; unfocused is [divider]. */
    val composerFocus: Color,
    /** accent @ 0.14: M3 primary and secondary containers, navigation indicator. */
    val accentContainer: Color,
    /** failed @ 0.14: M3 error container. */
    val failedContainer: Color,
    val working: Color,
    val done: Color,
    val waiting: Color,
    val failed: Color,
    val warning: Color,
    val input: Color,
    /** Text and icons on [accent]. */
    val onAccent: Color,
    /** Project slot colours in ot3's order, duplicates removed. */
    val projectPalette: List<Color>,
    val sky: Ot3Sky,
) {
    /** Unfocused composer border, fg @ 0.09. */
    val composerBorder: Color get() = divider

    /** Sheets and dialogs sit on black @ 0.5 (`ot3:ui/qml/T3Panel.qml:386`). */
    val scrim: Color get() = Scrim

    companion object {
        const val MATERIAL_YOU_ID = "material-you"
        val Scrim = Color(0f, 0f, 0f, 0.5f)

        /** The importer's values, unchanged. */
        fun fromTheme(theme: OmarchyTheme): Ot3Colors {
            fun base(key: String) = rgb(theme.base(key))
            fun role(key: String) = rgb(theme.role(key))
            return Ot3Colors(
                themeId = theme.id,
                isLight = theme.isLight,
                background = base("background"),
                foreground = base("foreground"),
                accent = base("accent"),
                muted = base("muted"),
                dim = role("dim"),
                divider = role("divider"),
                surface = role("surface"),
                card = role("card"),
                cardBorder = role("cardBorder"),
                changesCard = role("changesCard"),
                rowSelected = role("rowSelected"),
                rowPressed = role("rowPressed"),
                rowDraft = role("rowDraft"),
                field = role("field"),
                fieldBorder = role("fieldBorder"),
                switchOff = role("switchOff"),
                inlineCode = role("inlineCode"),
                composerFocus = role("composerFocus"),
                accentContainer = role("accentContainer"),
                failedContainer = role("failedContainer"),
                working = role("working"),
                done = role("done"),
                waiting = role("waiting"),
                failed = role("failed"),
                warning = role("warning"),
                input = role("input"),
                onAccent = role("onAccent"),
                projectPalette = theme.projectPalette.map(::rgb),
                sky = theme.sky,
            )
        }

        /**
         * The roles derived at runtime from a base palette, by the importer's formulas: used
         * for Material You, whose colours are only known on the device. Status colours,
         * project palette and sky come from [statusFrom], a theme of the same appearance, so
         * "Failed" stays red.
         */
        fun derive(
            themeId: String,
            background: Color,
            foreground: Color,
            accent: Color,
            muted: Color,
            statusFrom: Ot3Colors,
        ): Ot3Colors {
            val light = isLightBackground(background)
            fun fg(alpha: Int) = Ot3Blend.over(foreground, background, alpha)
            fun ac(alpha: Int) = Ot3Blend.over(accent, background, alpha)
            return Ot3Colors(
                themeId = themeId,
                isLight = light,
                background = background,
                foreground = foreground,
                accent = accent,
                muted = muted,
                dim = fg(570),
                divider = fg(90),
                surface = fg(45),
                card = fg(35),
                cardBorder = fg(120),
                changesCard = fg(25),
                rowSelected = fg(75),
                rowPressed = fg(45),
                rowDraft = ac(60),
                field = fg(40),
                fieldBorder = fg(140),
                switchOff = fg(150),
                inlineCode = fg(100),
                composerFocus = ac(450),
                accentContainer = ac(140),
                failedContainer = Ot3Blend.over(statusFrom.failed, background, 140),
                working = statusFrom.working,
                done = statusFrom.done,
                waiting = statusFrom.waiting,
                failed = statusFrom.failed,
                warning = statusFrom.warning,
                input = statusFrom.input,
                onAccent = onAccent(accent, background, foreground),
                projectPalette = statusFrom.projectPalette,
                sky = statusFrom.sky.copy(lightSky = light),
            )
        }

        /** ot3's sky rule: Rec.709 weights on the encoded channels, > 0.5 is light. */
        fun isLightBackground(background: Color): Boolean =
            0.2126f * background.red + 0.7152f * background.green + 0.0722f * background.blue > 0.5f

        /** The background, unless it is under 4.5:1 on the accent and the foreground does better. */
        fun onAccent(accent: Color, background: Color, foreground: Color): Color {
            val onBackground = contrast(accent, background)
            return if (onBackground < 4.5 && contrast(accent, foreground) > onBackground) foreground else background
        }

        /** WCAG 2 contrast ratio. */
        fun contrast(a: Color, b: Color): Double {
            val la = relativeLuminance(a)
            val lb = relativeLuminance(b)
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }

        private fun relativeLuminance(color: Color): Double {
            fun linear(channel: Float): Double {
                val c = channel.toDouble()
                return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
        }

        internal fun rgb(value: Int): Color = Color(0xFF000000.toInt() or value)
    }
}

/** Opaque straight-alpha compositing, the importer's formula (round half to even on exact ties). */
object Ot3Blend {
    /** [top] at [alphaThousandths] / 1000 over [bottom]. */
    fun over(top: Color, bottom: Color, alphaThousandths: Int): Color {
        val t = top.toArgb8()
        val b = bottom.toArgb8()
        val channels = IntArray(3) { i ->
            val shift = 16 - 8 * i
            val tc = (t shr shift) and 0xFF
            val bc = (b shr shift) and 0xFF
            roundHalfEven(bc * 1000 + (tc - bc) * alphaThousandths, 1000)
        }
        return Ot3Colors.rgb((channels[0] shl 16) or (channels[1] shl 8) or channels[2])
    }

    private fun roundHalfEven(numerator: Int, denominator: Int): Int {
        val quotient = Math.floorDiv(numerator, denominator)
        val twice = 2 * (numerator - quotient * denominator)
        return when {
            twice > denominator -> quotient + 1
            twice < denominator -> quotient
            else -> quotient + (quotient and 1)
        }
    }

    private fun Color.toArgb8(): Int {
        fun c(v: Float) = Math.round(v * 255f).coerceIn(0, 255)
        return (c(red) shl 16) or (c(green) shl 8) or c(blue)
    }
}

val LocalOt3Colors = staticCompositionLocalOf<Ot3Colors> { error("No Ot3Colors: wrap the content in Ot3Theme") }
