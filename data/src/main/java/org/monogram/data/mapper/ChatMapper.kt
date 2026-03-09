package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.data.db.model.ChatEntity
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.ChatType

class ChatMapper {
    fun mapToDomain(entity: ChatEntity): ChatModel {
        return ChatModel(
            id = entity.id,
            title = entity.title,
            unreadCount = entity.unreadCount,
            avatarPath = entity.avatarPath,
            lastMessageText = entity.lastMessageText,
            lastMessageTime = entity.lastMessageTime,
            order = entity.order,
            isPinned = entity.isPinned,
            isMuted = entity.isMuted,
            isChannel = entity.isChannel,
            isGroup = entity.isGroup,
            type = ChatType.valueOf(entity.type)
        )
    }

    fun mapToEntity(domain: ChatModel): ChatEntity {
        return ChatEntity(
            id = domain.id,
            title = domain.title,
            unreadCount = domain.unreadCount,
            avatarPath = domain.avatarPath,
            lastMessageText = domain.lastMessageText,
            lastMessageTime = domain.lastMessageTime,
            order = domain.order,
            isPinned = domain.isPinned,
            isMuted = domain.isMuted,
            isChannel = domain.isChannel,
            isGroup = domain.isGroup,
            type = domain.type.name,
            createdAt = System.currentTimeMillis()
        )
    }

    fun formatMessageInfo(
        message: TdApi.Message?,
        chat: TdApi.Chat?,
        fetchUserName: (Long) -> String?
    ): Triple<String, List<org.monogram.domain.models.MessageEntity>, String> {
        if (message == null) return Triple("", emptyList(), "")

        val text = when (val content = message.content) {
            is TdApi.MessageText -> content.text.text
            is TdApi.MessagePhoto -> content.caption.text.ifEmpty { "Photo" }
            is TdApi.MessageVideo -> content.caption.text.ifEmpty { "Video" }
            is TdApi.MessageSticker -> content.sticker.emoji.ifEmpty { "Sticker" }
            is TdApi.MessageAnimation -> "GIF"
            is TdApi.MessageAudio -> content.audio.title ?: "Audio"
            is TdApi.MessageVoiceNote -> "Voice message"
            is TdApi.MessageVideoNote -> "Video message"
            is TdApi.MessageDocument -> content.document.fileName
            else -> "Message"
        }

        val time = message.date.toLong().toString()
        return Triple(text, emptyList(), time)
    }

    fun formatUserStatus(status: TdApi.UserStatus, isBot: Boolean): String {
        if (isBot) return "bot"
        return when (status) {
            is TdApi.UserStatusOnline -> "online"
            is TdApi.UserStatusOffline -> "offline"
            is TdApi.UserStatusRecently -> "last seen recently"
            is TdApi.UserStatusLastWeek -> "last seen within a week"
            is TdApi.UserStatusLastMonth -> "last seen within a month"
            else -> ""
        }
    }
}