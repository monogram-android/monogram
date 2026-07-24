package org.monogram.presentation.features.chats.conversation.logic

import org.monogram.domain.models.ChatViewportCacheEntry
import org.monogram.presentation.features.chats.conversation.ChatConversationLog
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

private fun ChatViewportCacheEntry.primaryAnchorMessageId(): Long? {
    return anchorMessageId ?: anchorAliasIds.firstOrNull()
}

private fun ChatViewportCacheEntry.isCompatibleWith(chatId: Long): Boolean {
    return anchorChatId == null || anchorChatId == chatId
}

internal fun resolveInitialChatScrollTarget(
    chatId: Long,
    explicitMessageId: Long?,
    savedViewport: ChatViewportCacheEntry?,
    firstUnreadMessageId: Long?,
    unreadCount: Int = 0,
    backfillUnreadThreshold: Int = 50,
    isComments: Boolean
): InitialChatScrollTarget {
    val normalizedSavedViewport = savedViewport?.takeIf { it.isCompatibleWith(chatId) }
    if (savedViewport != null && normalizedSavedViewport == null) {
        runCatching {
            ChatConversationLog.logViewport(
                chatId = chatId,
                threadId = null,
                event = "saved_viewport_ignored_chat_mismatch",
                extra = "savedChatId=${savedViewport.anchorChatId ?: 0L} savedAnchor=${savedViewport.primaryAnchorMessageId() ?: 0L}"
            )
        }
    }

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

    val savedAnchorId = normalizedSavedViewport?.primaryAnchorMessageId()
    if (normalizedSavedViewport != null &&
        !normalizedSavedViewport.atBottom &&
        !normalizedSavedViewport.readFully &&
        savedAnchorId != null
    ) {
        return InitialChatScrollTarget.AroundMessage(
            messageId = savedAnchorId,
            origin = InitialChatScrollTargetOrigin.SavedViewport,
            highlight = false,
            command = ChatScrollCommand.RestoreViewport(
                anchorMessageId = savedAnchorId,
                anchorAliasIds = normalizedSavedViewport.anchorAliasIds,
                anchorOffsetPx = normalizedSavedViewport.anchorOffsetPx,
                atBottom = false,
                readFully = normalizedSavedViewport.readFully,
                topEndMessageId = normalizedSavedViewport.topEndMessageId
            )
        )
    }

    if (isComments) {
        return InitialChatScrollTarget.Comments(
            origin = if (normalizedSavedViewport != null) {
                InitialChatScrollTargetOrigin.CommentsSavedViewport
            } else {
                InitialChatScrollTargetOrigin.CommentsStart
            },
            command = normalizedSavedViewport?.let {
                ChatScrollCommand.RestoreViewport(
                    anchorMessageId = it.primaryAnchorMessageId(),
                    anchorAliasIds = it.anchorAliasIds,
                    anchorOffsetPx = it.anchorOffsetPx,
                    atBottom = it.atBottom,
                    readFully = it.readFully,
                    topEndMessageId = it.topEndMessageId
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

    if (normalizedSavedViewport != null && normalizedSavedViewport.atBottom) {
        return InitialChatScrollTarget.Bottom(
            origin = InitialChatScrollTargetOrigin.BottomSavedViewport,
            command = ChatScrollCommand.RestoreViewport(
                anchorMessageId = normalizedSavedViewport.primaryAnchorMessageId(),
                anchorAliasIds = normalizedSavedViewport.anchorAliasIds,
                anchorOffsetPx = normalizedSavedViewport.anchorOffsetPx,
                atBottom = true,
                readFully = normalizedSavedViewport.readFully,
                topEndMessageId = normalizedSavedViewport.topEndMessageId
            )
        )
    }

    return InitialChatScrollTarget.Bottom(
        origin = if (normalizedSavedViewport != null) {
            InitialChatScrollTargetOrigin.BottomSavedViewport
        } else {
            InitialChatScrollTargetOrigin.BottomFallback
        },
        command = normalizedSavedViewport?.let {
            ChatScrollCommand.RestoreViewport(
                anchorMessageId = it.primaryAnchorMessageId(),
                anchorAliasIds = it.anchorAliasIds,
                anchorOffsetPx = it.anchorOffsetPx,
                atBottom = it.atBottom,
                readFully = it.readFully,
                topEndMessageId = it.topEndMessageId
            )
        } ?: ChatScrollCommand.ScrollToBottom(animated = false)
    )
}
