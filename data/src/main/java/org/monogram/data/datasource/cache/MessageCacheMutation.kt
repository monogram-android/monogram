package org.monogram.data.datasource.cache

import org.monogram.data.db.model.MessageEntity
import org.monogram.data.db.model.MessageWindowEntity
import org.monogram.domain.repository.ConversationKey

sealed interface MessageCacheMutation {
    data class Persist(
        val message: MessageEntity,
        val key: ConversationKey
    ) : MessageCacheMutation

    data class HistoryWrite(
        val message: MessageEntity,
        val expectedExisting: MessageEntity?
    )

    data class PersistHistoryBatch(
        val writes: List<HistoryWrite>,
        val window: MessageWindowEntity
    ) : MessageCacheMutation

    data class UpdateWindow(val window: MessageWindowEntity) : MessageCacheMutation

    data class ReplaceId(
        val chatId: Long,
        val oldMessageId: Long,
        val message: MessageEntity,
        val key: ConversationKey
    ) : MessageCacheMutation

    data class UpdateContent(
        val chatId: Long,
        val messageId: Long,
        val content: String,
        val contentType: String,
        val contentMeta: String?,
        val mediaFileId: Int,
        val mediaPath: String?,
        val editDate: Int
    ) : MessageCacheMutation

    data class UpdateInteraction(
        val chatId: Long,
        val messageId: Long,
        val viewCount: Int,
        val forwardCount: Int,
        val replyCount: Int
    ) : MessageCacheMutation

    data class MarkRead(val chatId: Long, val upToMessageId: Long) : MessageCacheMutation

    data class DeleteMessages(val chatId: Long, val messageIds: List<Long>) : MessageCacheMutation

    data class UpdateMediaPath(
        val chatId: Long,
        val messageId: Long,
        val fileId: Int,
        val path: String
    ) : MessageCacheMutation
}
