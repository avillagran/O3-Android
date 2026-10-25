package dev.avillagran.ot3android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.avillagran.ot3android.R

/** Fira Code (SIL OFL 1.1, docs/LICENSES.md), Regular and Medium: code, diffs, paths, commands. */
val FiraCode: FontFamily = FontFamily(
    Font(R.font.fira_code_regular, FontWeight.Normal),
    Font(R.font.fira_code_medium, FontWeight.Medium),
)

/**
 * ot3's type scale (`omarchy:shell/Commons/Style.qml:327-338`, base 12 px) × 7/6, rounded to
 * the nearest sp (docs/ui/tokens.md section 3). Text uses the system UI font, as ot3 does.
 */
object Ot3TypeScale {
    val caption = 12.sp
    val bodySmall = 13.sp
    val body = 14.sp
    val subtitle = 15.sp
    val title = 16.sp
    val heading = 19.sp
    val display = 28.sp
    val displayLarge = 33.sp

    /** The empty-thread "T3" mark, 42 px (`ot3:ui/qml/T3Panel.qml:1941-1948`). */
    val emptyMark = 49.sp

    /** Prose and list items (`ot3:ui/js/MarkdownStyle.js:34`). */
    val proseLineHeight = 1.5.em

    /** Headings (`ot3:ui/js/MarkdownStyle.js:36`). */
    val headingLineHeight = 1.3.em

    /** Markdown block spacing, 10.4 px (`ot3:ui/js/MarkdownStyle.js:33`). */
    val blockSpacing = 12.sp
}

private val Centered = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    lineHeight: TextUnit = Ot3TypeScale.headingLineHeight,
    family: FontFamily = FontFamily.Default,
    letterSpacing: TextUnit = 0.sp,
) = TextStyle(
    fontFamily = family,
    fontSize = size,
    fontWeight = weight,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
    lineHeightStyle = Centered,
)

/**
 * The Material slots of docs/ui/tokens.md section 3 plus ot3's own named styles. Slots
 * the table does not map (display*, headlineLarge, titleLarge) reuse the nearest ot3 size;
 * ot3 has no use for them.
 */
@Immutable
class Ot3Typography(
    val material: Typography = Typography(
        displayLarge = style(Ot3TypeScale.emptyMark),
        displayMedium = style(Ot3TypeScale.displayLarge),
        displaySmall = style(Ot3TypeScale.displayLarge),
        headlineLarge = style(Ot3TypeScale.displayLarge),
        headlineMedium = style(Ot3TypeScale.displayLarge),
        headlineSmall = style(Ot3TypeScale.display),
        titleLarge = style(Ot3TypeScale.heading, FontWeight.SemiBold),
        titleMedium = style(Ot3TypeScale.heading, FontWeight.SemiBold),
        titleSmall = style(Ot3TypeScale.title, FontWeight.Medium),
        bodyLarge = style(Ot3TypeScale.subtitle, lineHeight = Ot3TypeScale.proseLineHeight),
        bodyMedium = style(Ot3TypeScale.body, lineHeight = Ot3TypeScale.proseLineHeight),
        bodySmall = style(Ot3TypeScale.caption, lineHeight = Ot3TypeScale.proseLineHeight),
        labelLarge = style(Ot3TypeScale.body, FontWeight.Medium),
        labelMedium = style(Ot3TypeScale.bodySmall),
        labelSmall = style(Ot3TypeScale.caption),
    ),
    /** Body-size mono: paths, command lines, diffs. */
    val mono: TextStyle = style(Ot3TypeScale.body, family = FiraCode, lineHeight = Ot3TypeScale.proseLineHeight),
    /** Markdown prose. */
    val markdownBody: TextStyle = style(Ot3TypeScale.body, lineHeight = Ot3TypeScale.proseLineHeight),
    val markdownH1: TextStyle = style(23.sp, FontWeight.SemiBold),
    val markdownH2: TextStyle = style(21.sp, FontWeight.SemiBold),
    val markdownH3: TextStyle = style(19.sp, FontWeight.SemiBold),
    /** Inline and block code, body × 12/14 (`ot3:ui/js/MarkdownStyle.js:39`), drawn on `surface`. */
    val markdownCode: TextStyle = style(Ot3TypeScale.caption, family = FiraCode, lineHeight = Ot3TypeScale.proseLineHeight),
    /** Wordmark "Code" (`ot3:ui/qml/T3Panel.qml:1147`). */
    val wordmark: TextStyle = style(21.sp, FontWeight.Bold),
    /** Wordmark "OMARCHY", drawn in `dim` (`ot3:ui/qml/T3Panel.qml:1149`). */
    val wordmarkOmarchy: TextStyle = style(9.sp, letterSpacing = 2.3.sp),
    /** Count pill digits (`ot3:ui/qml/T3StatusBadges.qml:19`). */
    val countPill: TextStyle = style(9.sp, FontWeight.Bold),
    /** The empty-thread "T3" mark. */
    val emptyMark: TextStyle = style(Ot3TypeScale.emptyMark, FontWeight.Bold),
) {
    companion object {
        val Default = Ot3Typography()
    }
}

val LocalOt3Typography = staticCompositionLocalOf { Ot3Typography.Default }
