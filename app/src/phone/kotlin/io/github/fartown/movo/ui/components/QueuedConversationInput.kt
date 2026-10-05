package io.github.fartown.movo.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/** UI projection of the existing AppState queue; no separate message storage. */
internal data class QueuedConversationInput(
    val text: String? = null,
    val busy: Boolean = false,
    val edit: () -> Unit = {},
    val discard: () -> Unit = {},
)
internal val LocalQueuedConversationInput = staticCompositionLocalOf { QueuedConversationInput() }
