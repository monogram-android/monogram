package org.monogram.core.database.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "messages",
    primaryKeys = ["chatId", "id"],
    indices = [Index(value = ["chatId", "date"])],
)
data class MessageEntity(
    val chatId: Long,
    val id: Int,
    val senderId: Long?,
    val text: String?,
    val date: Long,
    val editDate: Long? = null,
    val outgoing: Boolean,
    val mediaCacheKey: String?,
    val mediaKind: String?,
    val thumbCacheKey: String? = null,
    val mediaDuration: Int? = null,
    val mediaWidth: Int? = null,
    val mediaHeight: Int? = null,
    val groupedId: Long? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val reactionsJson: String? = null,
    val repliesCount: Int = 0,
    val discussionPeerId: Long? = null,
    val replyQuote: String? = null,
    val entitiesJson: String? = null,
    val replyToMsgId: Int? = null,
    val fwdFrom: String? = null,
    val fwdFromId: Long? = null,
    val fwdDate: Long? = null,
    val viaBot: String? = null,
    val senderName: String? = null,
    val replyMarkupJson: String? = null,
    val pending: Boolean = false,
    val randomId: Long? = null,
    val checklistJson: String? = null,
)
