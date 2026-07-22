package org.monogram.presentation.features.chats.conversation.logic

import org.monogram.domain.models.ChatViewportCacheEntry
import org.monogram.presentation.features.chats.conversation.ChatScrollCommand
import org.monogram.presentation.features.chats.conversation.ScrollAlign

internal sealed interface InitialChatScrollTarget {
    val command: ChatScrollCommand
    val origin: InitialChatScrollTargetOrigin
    val anchorMessageId: Long?

    data class AroundMessage(
        val messageId: Long,
        override val command: ChatScrollCommand,
        override val origin: InitialChatScrollTargetOrigin,
        val highlight: Boolean,
        val backfillNewerAfterInitialLoad: Boolean = false
    ) : InitialChatScrollTarget {
        override val anchorMessageId: Long = messageId
    }

    data class Bottom(
        override val command: ChatScrollCommand,
        override val origin: InitialChatScrollTargetOrigin
    ) : InitialChatScrollTarget {
        override val anchorMessageId: Long? =
            (command as? ChatScrollCommand.RestoreViewport)?.anchorMessageId
    }

    data class Comments(
        override val command: ChatScrollCommand,
        override val origin: InitialChatScrollTargetOrigin
    ) : InitialChatScrollTarget {
        override val anchorMessageId: Long? =
            (command as? ChatScrollCommand.RestoreViewport)?.anchorMessageId
    }
}

internal enum class InitialChatScrollTargetOrigin(private val suffix: String) {
    ExplicitMessage("explicit"),
    SavedViewport("saved_viewport"),
    FirstUnread("first_unread"),
    CommentsSavedViewport("comments_saved"),
    CommentsStart("comments_start"),
    BottomSavedViewport("bottom_saved"),
    BottomFallback("bottom_fallback");

    fun perfName(prefix: String): String = "$prefix.$suffix"
}

internal fun InitialChatScrollTarget.perfTargetName(): String = when (this) {
    is InitialChatScrollTarget.AroundMessage -> origin.perfName("around")
    is InitialChatScrollTarget.Bottom -> origin.perfName("bottom")
    is InitialChatScrollTarget.Comments -> origin.perfName("comments")
}

internal fun resolveInitialChatScrollTarget(
    explicitMessageId: Long?,
    savedViewport: ChatViewportCacheEntry?,
    firstUnreadMessageId: Long?,
    unreadCount: Int = 0,
    backfillUnreadThreshold: Int = 50,
    isComments: Boolean
): InitialChatScrollTarget {
    if (explicitMessageId != null) {
        return InitialChatScrollTarget.AroundMessage(
            messageId = explicitMessageId,
            origin = InitialChatScrollTargetOrigin.ExplicitMessage,
            highlight = true,
            command = ChatScrollCommand.JumpToMessage(
                messageId = explicitMessageId,
                highlight = true,
                align = ScrollAlign.Center,
                animated = false
            )
        )
    }

    val savedAnchorId = savedViewport?.anchorMessageId
    if (savedViewport != null && !savedViewport.atBottom && savedAnchorId != null) {
        return InitialChatScrollTarget.AroundMessage(
            messageId = savedAnchorId,
            origin = InitialChatScrollTargetOrigin.SavedViewport,
            highlight = false,
            command = ChatScrollCommand.RestoreViewport(
                anchorMessageId = savedAnchorId,
                anchorOffsetPx = savedViewport.anchorOffsetPx,
                atBottom = false
            )
        )
    }

    if (isComments) {
        return InitialChatScrollTarget.Comments(
            origin = if (savedViewport != null) {
                InitialChatScrollTargetOrigin.CommentsSavedViewport
            } else {
                InitialChatScrollTargetOrigin.CommentsStart
            },
            command = savedViewport?.let {
                ChatScrollCommand.RestoreViewport(
                    anchorMessageId = it.anchorMessageId,
                    anchorOffsetPx = it.anchorOffsetPx,
                    atBottom = it.atBottom
                )
            } ?: ChatScrollCommand.ScrollToStart(animated = false)
        )
    }

    if (firstUnreadMessageId != null) {
        return InitialChatScrollTarget.AroundMessage(
            messageId = firstUnreadMessageId,
            origin = InitialChatScrollTargetOrigin.FirstUnread,
            highlight = false,
            backfillNewerAfterInitialLoad = !isComments && unreadCount > backfillUnreadThreshold,
            command = ChatScrollCommand.JumpToMessage(
                messageId = firstUnreadMessageId,
                highlight = false,
                align = ScrollAlign.Center,
                animated = false
            )
        )
    }

    if (savedViewport != null && savedViewport.atBottom) {
        return InitialChatScrollTarget.Bottom(
            origin = InitialChatScrollTargetOrigin.BottomSavedViewport,
            command = ChatScrollCommand.RestoreViewport(
                anchorMessageId = savedViewport.anchorMessageId,
                anchorOffsetPx = savedViewport.anchorOffsetPx,
                atBottom = true
            )
        )
    }

    return InitialChatScrollTarget.Bottom(
        origin = if (savedViewport != null) {
            InitialChatScrollTargetOrigin.BottomSavedViewport
        } else {
            InitialChatScrollTargetOrigin.BottomFallback
        },
        command = savedViewport?.let {
            ChatScrollCommand.RestoreViewport(
                anchorMessageId = it.anchorMessageId,
                anchorOffsetPx = it.anchorOffsetPx,
                atBottom = it.atBottom
            )
        } ?: ChatScrollCommand.ScrollToBottom(animated = false)
    )
}
