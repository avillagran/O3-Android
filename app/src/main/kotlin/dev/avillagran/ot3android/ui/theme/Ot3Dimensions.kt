package dev.avillagran.ot3android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** ot3's spacing scale, 1 px = 1 dp (`omarchy:shell/Commons/Style.qml:234-260`). */
object Ot3Spacing {
    val xxs = 2.dp
    val xs = 3.dp
    val sm = 4.dp
    val md = 6.dp
    val lg = 8.dp
    val xl = 10.dp
    val xxl = 12.dp
    val xxxl = 14.dp
    val huge = 18.dp
    val panelPadding = 18.dp
    val popupPadding = 14.dp

    /** Phone gutters on the timeline and lists (UI_SPEC 3.3). */
    val phoneGutter = xxxl

    /** Tablet timeline margin (`ot3:ui/qml/T3Panel.qml:1583`). */
    val tabletTimelineMargin = 26.dp
}

/** Corner radii (UI_SPEC 3.3, docs/ui/tokens.md section 4). */
object Ot3Radii {
    val projectChip = 4.dp
    val countPill = 3.dp
    val row = 6.dp
    val field = 6.dp
    val iconButton = 6.dp

    /** Menu-like controls: select, button. */
    val control = 7.dp
    val hoverCard = 8.dp
    val thumbnail = 8.dp
    val card = 10.dp
    val dialog = 10.dp
    val bubble = 16.dp

    /** Bottom sheets' top corners; ot3 has no sheets. */
    val sheet = 16.dp
    val composer = 17.dp
}

/** Component sizes (docs/ui/tokens.md section 4); minimums where text can grow. */
object Ot3Sizes {
    /** Every interactive element (UI_SPEC rule 3). */
    val touchTarget = 48.dp
    val border = 1.dp
    val icon = 20.dp
    val inlineIcon = 16.dp
    val iconButtonPressed = 36.dp
    val cardRowMinHeight = 82.dp
    val cardRowPaddingHorizontal = 10.dp
    val cardRowPaddingVertical = 8.dp
    val cardRowGap = 4.dp
    val slimRowHeight = 48.dp
    val shelfHeaderHeight = 40.dp
    val projectChip = 16.dp
    val fieldHeight = 48.dp
    val selectHeight = 40.dp
    val buttonHeight = 40.dp
    val buttonPaddingHorizontal = 15.dp
    val switchWidth = 36.dp
    val switchHeight = 20.dp
    val switchKnob = 14.dp
    val bubblePadding = 12.dp
    val bubbleGap = 16.dp
    val bubbleGapCommentary = 8.dp
    const val BUBBLE_MAX_WIDTH_FRACTION = 0.8f
    val contentLaneMaxWidth = 736.dp
    val composerMargin = 12.dp
    val sendButton = 40.dp
    val fileChipHeight = 28.dp
    val composerThumbnail = 56.dp
    val messageImageWidth = 220.dp
    val messageImageHeight = 160.dp
    val changesCardPadding = 11.dp
    val changesRowHeight = 40.dp
    val modelPickerRowHeight = 56.dp
    val providerRailWidth = 56.dp
    val countPillHeight = 12.dp
    val countPillWidths = listOf(16.dp, 21.dp, 27.dp)

    /** Disabled content opacity (`ot3:ui/qml/T3IconButton.qml:25`, `T3PickerButton.qml:12`). */
    const val DISABLED_ICON_BUTTON_ALPHA = 0.3f
    const val DISABLED_CONTROL_ALPHA = 0.4f
}

/** Material shapes from ot3's radii: chip, row and field, control, card, sheet. */
val Ot3Shapes = Shapes(
    extraSmall = RoundedCornerShape(Ot3Radii.projectChip),
    small = RoundedCornerShape(Ot3Radii.row),
    medium = RoundedCornerShape(Ot3Radii.control),
    large = RoundedCornerShape(Ot3Radii.card),
    extraLarge = RoundedCornerShape(Ot3Radii.sheet),
)
