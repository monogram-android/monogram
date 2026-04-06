package org.monogram.data.mapper

import android.text.format.DateUtils
import org.drinkless.tdlib.TdApi
import org.monogram.data.db.model.ChatEntity
import org.monogram.domain.models.*
import org.monogram.domain.repository.StringProvider
import java.text.SimpleDateFormat
import java.util.*

class ChatMapper(private val stringProvider: StringProvider) {
    fun mapChatToModel(
        chat: TdApi.Chat,
        order: Long,
        isPinned: Boolean,
        isArchived: Boolean,
        smallPhotoPath: String?,
        photoId: Int,
        isOnline: Boolean,
        userStatus: String,
        isVerified: Boolean,
        isSponsor: Boolean,
        isForum: Boolean,
        isBot: Boolean,
        memberCount: Int,
        onlineCount: Int,
        emojiPath: String?,
        typingAction: String?,
        lastMessageText: String,
        lastMessageEntities: List<MessageEntity>,
        lastMessageTime: String,
        lastMessageDate: Int,
        isMuted: Boolean,
        isAdmin: Boolean,
        isMember: Boolean,
        username: String? = null,
        usernames: UsernamesModel? = null,
        description: String? = null,
        inviteLink: String? = null,
        hasAutomaticTranslation: Boolean = false,
        personalAvatarPath: String? = null
    ): ChatModel {
        val p = chat.permissions ?: TdApi.ChatPermissions()
        val permissions = ChatPermissionsModel(
            canSendBasicMessages = p.canSendBasicMessages,
            canSendAudios = p.canSendAudios,
            canSendDocuments = p.canSendDocuments,
            canSendPhotos = p.canSendPhotos,
            canSendVideos = p.canSendVideos,
            canSendVideoNotes = p.canSendVideoNotes,
            canSendVoiceNotes = p.canSendVoiceNotes,
            canSendPolls = p.canSendPolls,
            canSendOtherMessages = p.canSendOtherMessages,
            canAddLinkPreviews = p.canAddLinkPreviews,
            canEditTag = p.canEditTag,
            canChangeInfo = p.canChangeInfo,
            canInviteUsers = p.canInviteUsers,
            canPinMessages = p.canPinMessages,
            canCreateTopics = p.canCreateTopics,
        )

        val isChannel = (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel ?: false

        val draft = chat.draftMessage?.inputMessageText as? TdApi.InputMessageText
        val draftText = draft?.text?.text
        val draftEntities = draft?.text?.entities?.map { mapEntity(it) } ?: emptyList()

        return ChatModel(
            id = chat.id,
            title = chat.title,
            unreadCount = chat.unreadCount,
            unreadMentionCount = chat.unreadMentionCount,
            unreadReactionCount = chat.unreadReactionCount,
            avatarPath = smallPhotoPath,
            personalAvatarPath = personalAvatarPath,
            lastMessageText = lastMessageText,
            lastMessageEntities = lastMessageEntities,
            lastMessageTime = lastMessageTime,
            lastMessageDate = lastMessageDate,
            order = order,
            isGroup = chat.type is TdApi.ChatTypeBasicGroup || (chat.type is TdApi.ChatTypeSupergroup && !isChannel),
            isSupergroup = chat.type is TdApi.ChatTypeSupergroup,
            isChannel = isChannel,
            memberCount = memberCount,
            onlineCount = onlineCount,
            photoId = photoId,
            isPinned = isPinned,
            isArchived = isArchived,
            accentColorId = chat.accentColorId,
            profileAccentColorId = chat.profileAccentColorId,
            backgroundCustomEmojiId = chat.backgroundCustomEmojiId,
            emojiStatusId = (chat.emojiStatus?.type as? TdApi.EmojiStatusTypeCustomEmoji)?.customEmojiId,
            emojiStatusPath = emojiPath,
            isAdmin = isAdmin,
            isOnline = isOnline,
            userStatus = userStatus,
            typingAction = typingAction,
            draftMessage = draftText,
            draftMessageEntities = draftEntities,
            isMuted = isMuted,
            isMarkedAsUnread = chat.isMarkedAsUnread,
            hasProtectedContent = chat.hasProtectedContent,
            isTranslatable = chat.isTranslatable,
            hasAutomaticTranslation = hasAutomaticTranslation,
            messageAutoDeleteTime = chat.messageAutoDeleteTime,
            canBeDeletedOnlyForSelf = chat.canBeDeletedOnlyForSelf,
            canBeDeletedForAllUsers = chat.canBeDeletedForAllUsers,
            canBeReported = chat.canBeReported,
            lastReadInboxMessageId = chat.lastReadInboxMessageId,
            lastReadOutboxMessageId = chat.lastReadOutboxMessageId,
            lastMessageId = chat.lastMessage?.id ?: 0L,
            isLastMessageOutgoing = chat.lastMessage?.isOutgoing ?: false,
            replyMarkupMessageId = chat.replyMarkupMessageId,
            messageSenderId = when (val sender = chat.messageSenderId) {
                is TdApi.MessageSenderUser -> sender.userId
                is TdApi.MessageSenderChat -> sender.chatId
                else -> null
            },
            blockList = chat.blockList != null,
            isVerified = isVerified || isForcedVerifiedChat(chat.id),
            isSponsor = isSponsor,
            viewAsTopics = chat.viewAsTopics,
            isForum = isForum,
            isBot = isBot,
            username = username,
            usernames = usernames,
            description = description,
            inviteLink = inviteLink,
            type = when (chat.type) {
                is TdApi.ChatTypePrivate -> ChatType.PRIVATE
                is TdApi.ChatTypeBasicGroup -> ChatType.BASIC_GROUP
                is TdApi.ChatTypeSupergroup -> ChatType.SUPERGROUP
                is TdApi.ChatTypeSecret -> ChatType.SECRET
                else -> ChatType.PRIVATE
            },
            permissions = permissions,
            isMember = isMember
        )
    }

    fun mapToDomain(entity: ChatEntity): ChatModel {
        return ChatModel(
            id = entity.id,
            title = entity.title,
            unreadCount = entity.unreadCount,
            avatarPath = entity.avatarPath,
            lastMessageText = entity.lastMessageText,
            lastMessageTime = entity.lastMessageTime,
            lastMessageDate = entity.lastMessageDate,
            order = entity.order,
            isPinned = entity.isPinned,
            isMuted = entity.isMuted,
            isChannel = entity.isChannel,
            isGroup = entity.isGroup,
            type = ChatType.valueOf(entity.type),
            isArchived = entity.isArchived,
            memberCount = entity.memberCount,
            onlineCount = entity.onlineCount,
            unreadMentionCount = entity.unreadMentionCount,
            unreadReactionCount = entity.unreadReactionCount,
            isMarkedAsUnread = entity.isMarkedAsUnread,
            hasProtectedContent = entity.hasProtectedContent,
            isTranslatable = entity.isTranslatable,
            hasAutomaticTranslation = entity.hasAutomaticTranslation,
            messageAutoDeleteTime = entity.messageAutoDeleteTime,
            canBeDeletedOnlyForSelf = entity.canBeDeletedOnlyForSelf,
            canBeDeletedForAllUsers = entity.canBeDeletedForAllUsers,
            canBeReported = entity.canBeReported,
            lastReadInboxMessageId = entity.lastReadInboxMessageId,
            lastReadOutboxMessageId = entity.lastReadOutboxMessageId,
            lastMessageId = entity.lastMessageId,
            isLastMessageOutgoing = entity.isLastMessageOutgoing,
            replyMarkupMessageId = entity.replyMarkupMessageId,
            messageSenderId = entity.messageSenderId,
            blockList = entity.blockList,
            emojiStatusId = entity.emojiStatusId,
            accentColorId = entity.accentColorId,
            profileAccentColorId = entity.profileAccentColorId,
            backgroundCustomEmojiId = entity.backgroundCustomEmojiId,
            photoId = entity.photoId,
            isSupergroup = entity.isSupergroup,
            isAdmin = entity.isAdmin,
            isOnline = entity.isOnline,
            typingAction = entity.typingAction,
            draftMessage = entity.draftMessage,
            isVerified = entity.isVerified || isForcedVerifiedChat(entity.id),
            isSponsor = entity.isSponsor || (entity.privateUserId != 0L && isSponsoredUser(entity.privateUserId)),
            viewAsTopics = entity.viewAsTopics,
            isForum = entity.isForum,
            isBot = entity.isBot,
            isMember = entity.isMember,
            username = entity.username,
            description = entity.description,
            inviteLink = entity.inviteLink,
            permissions = ChatPermissionsModel(
                canSendBasicMessages = entity.permissionCanSendBasicMessages,
                canSendAudios = entity.permissionCanSendAudios,
                canSendDocuments = entity.permissionCanSendDocuments,
                canSendPhotos = entity.permissionCanSendPhotos,
                canSendVideos = entity.permissionCanSendVideos,
                canSendVideoNotes = entity.permissionCanSendVideoNotes,
                canSendVoiceNotes = entity.permissionCanSendVoiceNotes,
                canSendPolls = entity.permissionCanSendPolls,
                canSendOtherMessages = entity.permissionCanSendOtherMessages,
                canAddLinkPreviews = entity.permissionCanAddLinkPreviews,
                canEditTag = entity.permissionCanEditTag,
                canChangeInfo = entity.permissionCanChangeInfo,
                canInviteUsers = entity.permissionCanInviteUsers,
                canPinMessages = entity.permissionCanPinMessages,
                canCreateTopics = entity.permissionCanCreateTopics
            )
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
            lastMessageDate = domain.lastMessageDate,
            order = domain.order,
            isPinned = domain.isPinned,
            isMuted = domain.isMuted,
            isChannel = domain.isChannel,
            isGroup = domain.isGroup,
            type = domain.type.name,
            privateUserId = 0L,
            basicGroupId = 0L,
            supergroupId = 0L,
            secretChatId = 0,
            positionsCache = null,
            isArchived = domain.isArchived,
            memberCount = domain.memberCount,
            onlineCount = domain.onlineCount,
            unreadMentionCount = domain.unreadMentionCount,
            unreadReactionCount = domain.unreadReactionCount,
            isMarkedAsUnread = domain.isMarkedAsUnread,
            hasProtectedContent = domain.hasProtectedContent,
            isTranslatable = domain.isTranslatable,
            hasAutomaticTranslation = domain.hasAutomaticTranslation,
            messageAutoDeleteTime = domain.messageAutoDeleteTime,
            canBeDeletedOnlyForSelf = domain.canBeDeletedOnlyForSelf,
            canBeDeletedForAllUsers = domain.canBeDeletedForAllUsers,
            canBeReported = domain.canBeReported,
            lastReadInboxMessageId = domain.lastReadInboxMessageId,
            lastReadOutboxMessageId = domain.lastReadOutboxMessageId,
            lastMessageId = domain.lastMessageId,
            isLastMessageOutgoing = domain.isLastMessageOutgoing,
            replyMarkupMessageId = domain.replyMarkupMessageId,
            messageSenderId = domain.messageSenderId,
            blockList = domain.blockList,
            emojiStatusId = domain.emojiStatusId,
            accentColorId = domain.accentColorId,
            profileAccentColorId = domain.profileAccentColorId,
            backgroundCustomEmojiId = domain.backgroundCustomEmojiId,
            photoId = domain.photoId,
            isSupergroup = domain.isSupergroup,
            isAdmin = domain.isAdmin,
            isOnline = domain.isOnline,
            typingAction = domain.typingAction,
            draftMessage = domain.draftMessage,
            isVerified = domain.isVerified || isForcedVerifiedChat(domain.id),
            isSponsor = domain.isSponsor,
            viewAsTopics = domain.viewAsTopics,
            isForum = domain.isForum,
            isBot = domain.isBot,
            isMember = domain.isMember,
            username = domain.username,
            description = domain.description,
            inviteLink = domain.inviteLink,
            permissionCanSendBasicMessages = domain.permissions.canSendBasicMessages,
            permissionCanSendAudios = domain.permissions.canSendAudios,
            permissionCanSendDocuments = domain.permissions.canSendDocuments,
            permissionCanSendPhotos = domain.permissions.canSendPhotos,
            permissionCanSendVideos = domain.permissions.canSendVideos,
            permissionCanSendVideoNotes = domain.permissions.canSendVideoNotes,
            permissionCanSendVoiceNotes = domain.permissions.canSendVoiceNotes,
            permissionCanSendPolls = domain.permissions.canSendPolls,
            permissionCanSendOtherMessages = domain.permissions.canSendOtherMessages,
            permissionCanAddLinkPreviews = domain.permissions.canAddLinkPreviews,
            permissionCanEditTag = domain.permissions.canEditTag,
            permissionCanChangeInfo = domain.permissions.canChangeInfo,
            permissionCanInviteUsers = domain.permissions.canInviteUsers,
            permissionCanPinMessages = domain.permissions.canPinMessages,
            permissionCanCreateTopics = domain.permissions.canCreateTopics,
            lastMessageContentType = "text",
            lastMessageSenderName = "",
            createdAt = System.currentTimeMillis()
        )
    }

    fun mapToEntity(chat: TdApi.Chat, domain: ChatModel): ChatEntity {
        val privateUserId: Long
        val basicGroupId: Long
        val supergroupId: Long
        val secretChatId: Int
        when (val t = chat.type) {
            is TdApi.ChatTypePrivate -> {
                privateUserId = t.userId
                basicGroupId = 0L
                supergroupId = 0L
                secretChatId = 0
            }
            is TdApi.ChatTypeBasicGroup -> {
                privateUserId = 0L
                basicGroupId = t.basicGroupId
                supergroupId = 0L
                secretChatId = 0
            }
            is TdApi.ChatTypeSupergroup -> {
                privateUserId = 0L
                basicGroupId = 0L
                supergroupId = t.supergroupId
                secretChatId = 0
            }
            is TdApi.ChatTypeSecret -> {
                privateUserId = 0L
                basicGroupId = 0L
                supergroupId = 0L
                secretChatId = t.secretChatId
            }
            else -> {
                privateUserId = 0L
                basicGroupId = 0L
                supergroupId = 0L
                secretChatId = 0
            }
        }
        val encodedPositions = encodePositions(chat.positions)
        val (lastMessageContentType, lastMessageSenderName) = chat.lastMessage?.let { message ->
            val type = when (message.content) {
                is TdApi.MessageText -> "text"
                is TdApi.MessagePhoto -> "photo"
                is TdApi.MessageVideo -> "video"
                is TdApi.MessageVoiceNote -> "voice"
                is TdApi.MessageVideoNote -> "video_note"
                is TdApi.MessageSticker -> "sticker"
                is TdApi.MessageDocument -> "document"
                is TdApi.MessageAudio -> "audio"
                is TdApi.MessageAnimation -> "gif"
                is TdApi.MessageContact -> "contact"
                is TdApi.MessagePoll -> "poll"
                is TdApi.MessageLocation -> "location"
                is TdApi.MessageVenue -> "location"
                is TdApi.MessageCall -> "call"
                is TdApi.MessageGame -> "game"
                is TdApi.MessageInvoice -> "invoice"
                is TdApi.MessageStory -> "story"
                is TdApi.MessagePinMessage -> "pinned"
                else -> "message"
            }
            val sender = ""
            type to sender
        } ?: ("text" to "")

        return mapToEntity(domain).copy(
            privateUserId = privateUserId,
            basicGroupId = basicGroupId,
            supergroupId = supergroupId,
            secretChatId = secretChatId,
            positionsCache = encodedPositions,
            lastMessageDate = chat.lastMessage?.date ?: domain.lastMessageDate,
            lastMessageContentType = lastMessageContentType,
            lastMessageSenderName = lastMessageSenderName
        )
    }

    private fun encodePositions(positions: Array<TdApi.ChatPosition>): String? {
        if (positions.isEmpty()) return null

        val encoded = positions.mapNotNull { pos ->
            if (pos.order == 0L) return@mapNotNull null
            val pinned = if (pos.isPinned) 1 else 0
            when (val list = pos.list) {
                is TdApi.ChatListMain -> "m:${pos.order}:$pinned"
                is TdApi.ChatListArchive -> "a:${pos.order}:$pinned"
                is TdApi.ChatListFolder -> "f:${list.chatFolderId}:${pos.order}:$pinned"
                else -> null
            }
        }

        return if (encoded.isEmpty()) null else encoded.joinToString("|")
    }

    fun formatMessageInfo(
        lastMsg: TdApi.Message?,
        chat: TdApi.Chat?,
        getUserName: (Long) -> String?
    ): Triple<String, List<MessageEntity>, String> {
        if (lastMsg == null) return Triple("", emptyList(), "")
        var entities = emptyList<MessageEntity>()

        fun captionOrFallback(caption: TdApi.FormattedText?, emojiPrefix: String, fallbackKey: String): String {
            val text = caption?.text?.trim().orEmpty()
            if (text.isNotEmpty()) {
                entities = caption?.entities?.map { mapEntity(it) } ?: emptyList()
                return "$emojiPrefix$text"
            }
            return stringProvider.getString(fallbackKey)
        }

        var txt = when (val c = lastMsg.content) {
            is TdApi.MessageText -> {
                entities = c.text.entities.map { mapEntity(it) }
                c.text.text
            }
            is TdApi.MessagePhoto -> captionOrFallback(c.caption, "📷 ", "chat_mapper_photo")
            is TdApi.MessageVideo -> captionOrFallback(c.caption, "📹 ", "chat_mapper_video")
            is TdApi.MessageDocument -> {
                val fileName = c.document.fileName?.trim().orEmpty()
                val captionText = captionOrFallback(c.caption, "📎 ", "chat_mapper_document")
                if (c.caption.text.trim().isNotEmpty()) {
                    captionText
                } else if (fileName.isNotEmpty()) {
                    "📎 $fileName"
                } else {
                    captionText
                }
            }

            is TdApi.MessageAudio -> {
                val title = c.audio.title?.trim().orEmpty()
                val captionText = captionOrFallback(c.caption, "🎵 ", "chat_mapper_audio")
                if (c.caption.text.trim().isNotEmpty()) {
                    captionText
                } else if (title.isNotEmpty()) {
                    "🎵 $title"
                } else {
                    captionText
                }
            }

            is TdApi.MessageAnimation -> captionOrFallback(c.caption, "GIF · ", "chat_mapper_gif")
            is TdApi.MessageVoiceNote -> stringProvider.getString("chat_mapper_voice")
            is TdApi.MessageVideoNote -> stringProvider.getString("chat_mapper_video_note")
            is TdApi.MessageSticker -> stringProvider.getString("chat_mapper_sticker")
            is TdApi.MessageContact -> {
                val fullName = listOf(c.contact.firstName, c.contact.lastName)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                if (fullName.isNotBlank()) "👤 $fullName" else stringProvider.getString("chat_mapper_contact")
            }

            is TdApi.MessagePoll -> {
                val question = c.poll.question.text.trim()
                if (question.isNotEmpty()) "📊 $question" else stringProvider.getString("chat_mapper_poll")
            }

            is TdApi.MessageLocation -> stringProvider.getString("chat_mapper_location")
            is TdApi.MessageVenue -> {
                val title = c.venue.title.trim()
                if (title.isNotEmpty()) "📍 $title" else stringProvider.getString("chat_mapper_location")
            }

            is TdApi.MessageCall -> stringProvider.getString("chat_mapper_call")
            is TdApi.MessageGame -> {
                val title = c.game.title.trim()
                if (title.isNotEmpty()) "🎮 $title" else stringProvider.getString("chat_mapper_game")
            }

            is TdApi.MessageInvoice -> {
                val title = c.productInfo.title.trim()
                if (title.isNotEmpty()) "💳 $title" else stringProvider.getString("chat_mapper_invoice")
            }

            is TdApi.MessageStory -> stringProvider.getString("chat_mapper_story")
            is TdApi.MessagePinMessage -> stringProvider.getString("chat_mapper_pinned")
            else -> stringProvider.getString("chat_mapper_message")
        }.replace("\n", " ")

        if (chat != null && !lastMsg.isOutgoing) {
            if (chat.type !is TdApi.ChatTypePrivate) {
                val senderId = lastMsg.senderId
                if (senderId is TdApi.MessageSenderUser) {
                    val userName = getUserName(senderId.userId)
                    if (userName != null) {
                        val prefix = "$userName: "
                        txt = prefix + txt
                        entities = entities.map { it.copy(offset = it.offset + prefix.length) }
                    }
                }
            }
        }

        if (entities.any { it.type is MessageEntityType.Spoiler }) {
            txt = maskSpoilerText(txt, entities)
        }

        val date = lastMsg.date
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val time = if (date > 0) timeFormat.format(Date(date.toLong() * 1000)) else ""
        return Triple(txt, entities, time)
    }

    private fun maskSpoilerText(text: String, entities: List<MessageEntity>): String {
        if (text.isEmpty()) return text
        val chars = text.toCharArray()
        entities.forEach { entity ->
            if (entity.type is MessageEntityType.Spoiler) {
                val start = entity.offset.coerceIn(0, chars.size)
                val end = (entity.offset + entity.length).coerceIn(start, chars.size)
                for (i in start until end) {
                    chars[i] = '•'
                }
            }
        }
        return String(chars)
    }

    private fun mapEntity(entity: TdApi.TextEntity): MessageEntity {
        return MessageEntity(
            offset = entity.offset,
            length = entity.length,
            type = when (entity.type) {
                is TdApi.TextEntityTypeBold -> MessageEntityType.Bold
                is TdApi.TextEntityTypeItalic -> MessageEntityType.Italic
                is TdApi.TextEntityTypeUnderline -> MessageEntityType.Underline
                is TdApi.TextEntityTypeStrikethrough -> MessageEntityType.Strikethrough
                is TdApi.TextEntityTypeSpoiler -> MessageEntityType.Spoiler
                is TdApi.TextEntityTypeCode -> MessageEntityType.Code
                is TdApi.TextEntityTypePre -> MessageEntityType.Pre()
                is TdApi.TextEntityTypeTextUrl -> MessageEntityType.TextUrl((entity.type as TdApi.TextEntityTypeTextUrl).url)
                is TdApi.TextEntityTypeMention -> MessageEntityType.Mention
                is TdApi.TextEntityTypeMentionName -> MessageEntityType.TextMention((entity.type as TdApi.TextEntityTypeMentionName).userId)
                is TdApi.TextEntityTypeHashtag -> MessageEntityType.Hashtag
                is TdApi.TextEntityTypeBotCommand -> MessageEntityType.BotCommand
                is TdApi.TextEntityTypeUrl -> MessageEntityType.Url
                is TdApi.TextEntityTypeEmailAddress -> MessageEntityType.Email
                is TdApi.TextEntityTypePhoneNumber -> MessageEntityType.PhoneNumber
                is TdApi.TextEntityTypeBankCardNumber -> MessageEntityType.BankCardNumber
                is TdApi.TextEntityTypeCustomEmoji -> MessageEntityType.CustomEmoji((entity.type as TdApi.TextEntityTypeCustomEmoji).customEmojiId)
                is TdApi.TextEntityTypeBlockQuote -> MessageEntityType.BlockQuote
                is TdApi.TextEntityTypeExpandableBlockQuote -> MessageEntityType.BlockQuoteExpandable
                else -> MessageEntityType.Other(entity.type.javaClass.simpleName)
            }
        )
    }

    fun formatUserStatus(status: TdApi.UserStatus, isBot: Boolean = false): String {
        if (isBot) return stringProvider.getString("chat_mapper_bot")
        return when (status) {
            is TdApi.UserStatusOnline -> stringProvider.getString("chat_mapper_online")
            is TdApi.UserStatusOffline -> {
                val wasOnline = status.wasOnline.toLong() * 1000L
                if (wasOnline == 0L) return stringProvider.getString("chat_mapper_offline")
                val now = System.currentTimeMillis()
                val diff = now - wasOnline
                when {
                    diff < 60 * 1000 -> stringProvider.getString("chat_mapper_seen_just_now")
                    diff < 60 * 60 * 1000 -> {
                        val minutes = diff / (60 * 1000L)
                        if (minutes == 1L) stringProvider.getString("chat_mapper_seen_minutes_ago", 1)
                        else stringProvider.getString("chat_mapper_seen_minutes_ago_plural", minutes)
                    }
                    DateUtils.isToday(wasOnline) -> {
                        val date = Date(wasOnline)
                        val format = SimpleDateFormat("HH:mm", Locale.getDefault())
                        stringProvider.getString("chat_mapper_seen_at", format.format(date))
                    }

                    isYesterday(wasOnline) -> {
                        val date = Date(wasOnline)
                        val format = SimpleDateFormat("HH:mm", Locale.getDefault())
                        stringProvider.getString("chat_mapper_seen_yesterday", format.format(date))
                    }
                    else -> {
                        val date = Date(wasOnline)
                        val format = SimpleDateFormat("dd.MM.yy", Locale.getDefault())
                        stringProvider.getString("chat_mapper_seen_date", format.format(date))
                    }
                }
            }

            is TdApi.UserStatusRecently -> stringProvider.getString("chat_mapper_seen_recently")
            is TdApi.UserStatusLastWeek -> stringProvider.getString("chat_mapper_seen_week")
            is TdApi.UserStatusLastMonth -> stringProvider.getString("chat_mapper_seen_month")
            is TdApi.UserStatusEmpty -> stringProvider.getString("chat_mapper_offline")
            else -> ""
        }
    }

    private fun isYesterday(timestamp: Long): Boolean {
        return DateUtils.isToday(timestamp + DateUtils.DAY_IN_MILLIS)
    }
}
