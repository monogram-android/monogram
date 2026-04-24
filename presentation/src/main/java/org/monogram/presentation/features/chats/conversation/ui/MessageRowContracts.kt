package org.monogram.presentation.features.chats.conversation.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

@Immutable
internal data class MessageAppearanceConfig(
    val fontSize: Float,
    val letterSpacing: Float,
    val bubbleRadius: Float,
    val stickerSize: Float,
    val showLinkPreviews: Boolean = true,
    val autoplayGifs: Boolean = true,
    val autoplayVideos: Boolean = true,
    val autoDownloadMobile: Boolean = false,
    val autoDownloadWifi: Boolean = false,
    val autoDownloadRoaming: Boolean = false,
    val autoDownloadFiles: Boolean = false
)

@Immutable
internal data class MessageRowBehaviorConfig(
    val isGroup: Boolean,
    val isChannel: Boolean,
    val isTopicClosed: Boolean,
    val canReply: Boolean,
    val swipeEnabled: Boolean,
    val isSelectionMode: Boolean,
    val isAnyViewerOpen: Boolean
)

@Immutable
internal data class MessageRowUiFlags(
    val isSelected: Boolean = false,
    val isHighlighted: Boolean = false,
    val showUnreadSeparator: Boolean = false,
    val unreadCount: Int = 0,
    val shouldReportPosition: Boolean = false
)

@Immutable
internal data class MessageSenderGrouping(
    val isSameSenderAbove: Boolean,
    val isSameSenderBelow: Boolean
)

internal class MessageBubbleLayoutTracker {
    var outerColumnPosition: Offset = Offset.Zero
    var bubblePosition: Offset = Offset.Zero
    var bubbleSize: IntSize = IntSize.Zero
}

