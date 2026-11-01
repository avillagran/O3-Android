package dev.avillagran.ot3android.ui.home.layouts

import androidx.compose.runtime.staticCompositionLocalOf

/** Shared by all sidebar layouts; callbacks and their gesture ownership stay unchanged. */
data class SidebarRowAppearance(val enabled: Boolean = false, val sober: Boolean = false, val compact: Boolean = false,
    val marquee: Boolean = false, val projectColors: Boolean = true,
    val shelved: Boolean = false, val hostStyles: Map<String, HostStyle> = emptyMap(), val host: HostStyle = HostStyle())
val LocalSidebarRows = staticCompositionLocalOf { SidebarRowAppearance() }
