package dev.avillagran.ot3android.ui.thread

import androidx.compose.runtime.staticCompositionLocalOf

data class ReaderCheckpoint(val messages: Set<String> = emptySet(), val open: (String) -> Unit = {})
val LocalReaderCheckpoint = staticCompositionLocalOf { ReaderCheckpoint() }
