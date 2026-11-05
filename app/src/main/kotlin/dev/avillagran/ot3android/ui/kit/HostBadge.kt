package dev.avillagran.ot3android.ui.kit

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import dev.avillagran.ot3android.ui.home.layouts.HostStyle
import dev.avillagran.ot3android.ui.theme.Ot3Theme

val LocalHostStyles = staticCompositionLocalOf<Map<String, HostStyle>> { emptyMap() }

@Composable
fun HostBadge(label: String, environmentId: String = "", modifier: Modifier = Modifier,
    style: HostStyle = LocalHostStyles.current[environmentId] ?: HostStyle()) {
    val tint = style.color.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }
        ?.let { Color(it.toColorInt()) } ?: Ot3Theme.colors.dim
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Ot3Icon(style.displayIcon, null, tint = tint, size = 12.dp)
        Text(label, Modifier.widthIn(max = 120.dp), color = tint, style = MaterialTheme.typography.labelSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun HostProjectTitle(title: String, environmentId: String, modifier: Modifier = Modifier) {
    val style = LocalHostStyles.current[environmentId] ?: HostStyle()
    val tint = style.color.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }
        ?.let { Color(it.toColorInt()) } ?: Ot3Theme.colors.dim
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Ot3Icon(style.displayIcon, null, tint = tint, size = 16.dp)
        Text(title, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
