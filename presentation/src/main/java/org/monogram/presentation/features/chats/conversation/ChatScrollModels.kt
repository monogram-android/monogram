package org.monogram.presentation.features.chats.conversation

import androidx.compose.runtime.Immutable

@Immutable
sealed interface ChatScrollCommand {
    @Immutable
    data class RestoreViewport(
        val anchorMessageId: Long?,
        val anchorAliasIds: List<Long> = emptyList(),
        val anchorOffsetPx: Int,
        val atBottom: Boolean,
        val readFully: Boolean = atBottom,
        val topEndMessageId: Long? = null
    ) : ChatScrollCommand

    @Immutable
    data class JumpToMessage(
        val messageId: Long,
        val highlight: Boolean,
        val align: ScrollAlign = ScrollAlign.Center,
        val animated: Boolean = true
    ) : ChatScrollCommand

    @Immutable
    data class ScrollToBottom(
        val animated: Boolean = true
    ) : ChatScrollCommand

    @Immutable
    data class ScrollToStart(
        val animated: Boolean = true
    ) : ChatScrollCommand
}

@Immutable
data class MessageHighlightRequest(
    val messageId: Long,
    val token: Long
)

enum class ScrollAlign {
    Start,
    Center,
    End
}
