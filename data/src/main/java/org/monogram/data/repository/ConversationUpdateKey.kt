package org.monogram.data.repository

import org.drinkless.tdlib.TdApi

internal fun conversationUpdatedMessageKey(
    update: TdApi.Update,
    pollMessageKey: Pair<Long, Long>? = null
): Pair<Long, Long>? = when (update) {
    is TdApi.UpdateMessageContent -> update.chatId to update.messageId
    is TdApi.UpdateMessageEdited -> update.chatId to update.messageId
    is TdApi.UpdateMessageInteractionInfo -> update.chatId to update.messageId
    is TdApi.UpdateMessageReaction -> update.chatId to update.messageId
    is TdApi.UpdateMessageReactions -> update.chatId to update.messageId
    is TdApi.UpdateMessageMentionRead -> update.chatId to update.messageId
    is TdApi.UpdateMessageUnreadReactions -> update.chatId to update.messageId
    is TdApi.UpdateMessageFactCheck -> update.chatId to update.messageId
    is TdApi.UpdateMessageSuggestedPostInfo -> update.chatId to update.messageId
    is TdApi.UpdateMessageIsPinned -> update.chatId to update.messageId
    is TdApi.UpdateMessageContainsUnreadPollVotes -> update.chatId to update.messageId
    is TdApi.UpdateMessageLiveLocationViewed -> update.chatId to update.messageId
    is TdApi.UpdateMessageContentOpened -> update.chatId to update.messageId
    is TdApi.UpdatePollAnswer -> pollMessageKey
    else -> null
}
