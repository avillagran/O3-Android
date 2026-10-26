package dev.avillagran.ot3android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * ot3's colour roles (docs/ui/tokens.md section 1), every one opaque and every one the lib's
 * `theme.tokens` value except the two Material containers. Components read these for
 * anything ot3-specific (`Ot3Theme.colors.working`) and Material roles only inside Material
 * components.
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
    /** A row status with nothing to say, fg @ 0.6 (`statusMuted`). */
    val statusMuted: Color,
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
    /** Unfocused composer border, fg @ 0.09. */
    val composerBorder: Color,
    /** Focused composer border, accent @ 0.45. */
    val composerFocus: Color,
    /** accent @ 0.14: M3 primary and secondary containers, navigation indicator. Not an ot3 role. */
    val accentContainer: Color,
    /** failed @ 0.14: M3 error container. Not an ot3 role. */
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
    /** Sheets and dialogs sit on black @ 0.5 (`ot3:ui/qml/T3Panel.qml:386`). */
    val scrim: Color get() = Scrim

    companion object {
        const val MATERIAL_YOU_ID = "material-you"
        val Scrim = Color(0f, 0f, 0f, 0.5f)

        /**
         * The lib's `theme.tokens` values, unchanged. [OmarchyTheme.roles] has no Material
         * containers (ot3 has none): those two are accent and failed at 0.14 over the
         * background, blended here by [Ot3Blend] until the lib returns them
         * (docs/LIB_REQUESTS.md, "theme.tokens: Material containers").
         */
        fun fromTheme(theme: OmarchyTheme): Ot3Colors {
            fun base(key: String) = rgb(theme.base(key))
            fun role(key: String) = rgb(theme.role(key))
            val background = base("background")
            return Ot3Colors(
                themeId = theme.id,
                isLight = theme.isLight,
                background = background,
                foreground = base("foreground"),
                accent = base("accent"),
                muted = base("muted"),
                dim = role("dim"),
                statusMuted = role("statusMuted"),
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
                composerBorder = role("composerBorder"),
                composerFocus = role("composerFocus"),
                accentContainer = Ot3Blend.over(base("accent"), background, CONTAINER_ALPHA),
                failedContainer = Ot3Blend.over(role("failed"), background, CONTAINER_ALPHA),
                working = role("working"),
                done = role("done"),
                waiting = role("waiting"),
                failed = role("failed"),
                warning = role("warning"),
                input = role("input"),
                onAccent = role("onAccent"),
                projectPalette = theme.projectPalette.map(::rgb),
                sky = Ot3Sky(
                    lightSky = theme.sky.lightSky,
                    brightness = theme.sky.headerBrightness,
                    nebula = theme.sky.headerNebula,
                    meteorRate = theme.sky.headerMeteorRate,
                    ink = theme.sky.ink,
                ),
            )
        }

        /** The Material containers' alpha, in thousandths (0.14). */
        private const val CONTAINER_ALPHA = 140

        internal fun rgb(value: Int): Color = Color(0xFF000000.toInt() or value)
    }
}

/**
 * Opaque straight-alpha compositing (round half to even on exact ties). Only for the two
 * Material containers the lib does not return; ot3's roles come blended from `theme.tokens`.
 */
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
