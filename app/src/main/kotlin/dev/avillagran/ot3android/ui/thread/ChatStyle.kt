package dev.avillagran.ot3android.ui.thread

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp

/** Desktop's default 12px body and 10px caption, retaining Android font scaling. */
@Composable
internal fun ChatStyle(content: @Composable () -> Unit) {
    val type = MaterialTheme.typography
    MaterialTheme(typography = type.copy(
        bodyLarge = type.bodyLarge.copy(fontSize = 12.sp, lineHeight = 18.sp),
        bodyMedium = type.bodyMedium.copy(fontSize = 12.sp, lineHeight = 18.sp),
        bodySmall = type.bodySmall.copy(fontSize = 10.sp, lineHeight = 15.sp),
        labelSmall = type.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
        labelMedium = type.labelMedium.copy(fontSize = 11.sp, lineHeight = 14.sp),
    ), content = content)
}
