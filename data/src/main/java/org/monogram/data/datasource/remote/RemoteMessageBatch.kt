package org.monogram.data.datasource.remote

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.MessageModel
import org.monogram.domain.repository.ConversationScope

enum class RemoteHistoryFetchMode {
    LocalOnly,
    NetworkOnly,
    LocalThenNetwork
}

internal fun historyFetchAttempts(
    mode: RemoteHistoryFetchMode,
    scope: ConversationScope
): List<Boolean> = when (mode) {
    RemoteHistoryFetchMode.LocalOnly -> if (scope == ConversationScope.Main) listOf(true) else emptyList()
    RemoteHistoryFetchMode.NetworkOnly -> listOf(false)
    RemoteHistoryFetchMode.LocalThenNetwork ->
        if (scope == ConversationScope.Main) listOf(true, false) else listOf(false)
}

internal fun messageMatchesScope(topic: TdApi.MessageTopic?, scope: ConversationScope): Boolean =
    when (scope) {
        ConversationScope.Main -> true
        is ConversationScope.ForumTopic ->
            topic is TdApi.MessageTopicForum && topic.forumTopicId.toLong() == scope.topicId

        is ConversationScope.MessageThread ->
            topic is TdApi.MessageTopicThread && topic.messageThreadId == scope.threadId
    }

internal fun buildLocalMessageRequests(
    chatId: Long,
    messageIds: List<Long>
): List<TdApi.GetMessageLocally> = messageIds.map { messageId ->
    TdApi.GetMessageLocally(chatId, messageId)
}

internal fun buildHistoryRequest(
    chatId: Long,
    fromMessageId: Long,
    offset: Int,
    limit: Int,
    scope: ConversationScope,
    onlyLocal: Boolean
): TdApi.Function<TdApi.Messages> {
    require(scope == ConversationScope.Main || !onlyLocal) {
        "Scoped history must use the dedicated local reconciliation path"
    }
    return when (scope) {
        ConversationScope.Main -> TdApi.GetChatHistory(
            chatId,
            fromMessageId,
            offset,
            limit,
            onlyLocal
        )

        is ConversationScope.ForumTopic -> TdApi.GetForumTopicHistory(
            chatId,
            scope.topicId.toInt(),
            fromMessageId,
            offset,
            limit
        )

        is ConversationScope.MessageThread -> TdApi.GetMessageThreadHistory(
            chatId,
            scope.threadId,
            fromMessageId,
            offset,
            limit
        )
    }
}

data class MessageMapOptions(
    val resolveReplyPreviewFromNetwork: Boolean = true,
    val allowAutoDownload: Boolean = true,
    val resolveEnrichmentFromNetwork: Boolean = true
)

data class RemoteMessageBatch(
    val rawMessages: List<TdApi.Message>,
    val models: List<MessageModel>
)

data class RemoteOlderMessagesPage(
    val rawMessages: List<TdApi.Message>,
    val models: List<MessageModel>,
    val reachedOldest: Boolean,
    val isRemote: Boolean = true
)
