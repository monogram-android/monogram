package org.monogram.data.mapper.message

import org.drinkless.tdlib.TdApi
import org.monogram.data.chats.ChatCache
import org.monogram.data.mapper.SenderNameResolver
import org.monogram.data.mapper.TdFileHelper
import org.monogram.domain.models.ForwardInfo
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.PollType
import org.monogram.data.db.model.MessageEntity as MessageDbEntity

internal class MessagePersistenceMapper(
    private val cache: ChatCache,
    private val fileHelper: TdFileHelper
) {
    data class CachedMessageContent(
        val type: String,
        val text: String,
        val meta: String?,
        val fileId: Int = 0,
        val path: String? = null,
        val thumbnailPath: String? = null,
        val minithumbnail: ByteArray? = null
    )

    private data class CachedReplyPreview(
        val senderName: String,
        val contentType: String,
        val text: String
    )

    private data class CachedForwardOrigin(
        val fromName: String,
        val fromId: Long,
        val originChatId: Long? = null,
        val originMessageId: Long? = null
    )

    fun mapToEntity(
        msg: TdApi.Message,
        getSenderName: ((Long) -> String?)? = null
    ): MessageDbEntity {
        val senderId = when (val sender = msg.senderId) {
            is TdApi.MessageSenderUser -> sender.userId
            is TdApi.MessageSenderChat -> sender.chatId
            else -> 0L
        }
        val senderName = getSenderName?.invoke(senderId).orEmpty()
        val content = extractCachedContent(msg.content)
        val entitiesEncoded = encodeEntities(msg.content)
        val replyToMessageId = (msg.replyTo as? TdApi.MessageReplyToMessage)?.messageId ?: 0L
        val replyToPreview = buildReplyPreview(msg)
        val forwardOrigin = msg.forwardInfo?.origin?.let(::extractForwardOrigin)

        return MessageDbEntity(
            id = msg.id,
            chatId = msg.chatId,
            senderId = senderId,
            senderName = senderName,
            content = content.text,
            contentType = content.type,
            contentMeta = content.meta,
            mediaFileId = content.fileId,
            mediaPath = content.path,
            mediaThumbnailPath = content.thumbnailPath,
            minithumbnail = content.minithumbnail,
            date = resolveMessageDate(msg),
            isOutgoing = msg.isOutgoing,
            isRead = false,
            replyToMessageId = replyToMessageId,
            replyToPreview = replyToPreview?.let(::encodeReplyPreview),
            replyToPreviewType = replyToPreview?.contentType,
            replyToPreviewText = replyToPreview?.text,
            replyToPreviewSenderName = replyToPreview?.senderName,
            replyCount = msg.interactionInfo?.replyInfo?.replyCount ?: 0,
            forwardFromName = forwardOrigin?.fromName,
            forwardFromId = forwardOrigin?.fromId ?: 0L,
            forwardOriginChatId = forwardOrigin?.originChatId,
            forwardOriginMessageId = forwardOrigin?.originMessageId,
            forwardDate = msg.forwardInfo?.date ?: 0,
            editDate = msg.editDate,
            mediaAlbumId = msg.mediaAlbumId,
            entities = entitiesEncoded,
            viewCount = msg.interactionInfo?.viewCount ?: 0,
            forwardCount = msg.interactionInfo?.forwardCount ?: 0,
            createdAt = System.currentTimeMillis()
        )
    }

    fun extractCachedContent(content: TdApi.MessageContent): CachedMessageContent {
        return when (content) {
            is TdApi.MessageText -> CachedMessageContent("text", content.text.text, null)
            is TdApi.MessagePhoto -> {
                val sizes = content.photo.sizes
                val best = sizes.find { it.type == "x" }
                    ?: sizes.find { it.type == "m" }
                    ?: sizes.getOrNull(sizes.size / 2)
                    ?: sizes.lastOrNull()
                val thumbnail = sizes.find { it.type == "m" }
                    ?: sizes.find { it.type == "s" }
                val fileId = best?.photo?.id ?: 0
                val path = best?.photo?.local?.path?.takeIf { fileHelper.isValidPath(it) }
                val thumbnailPath = thumbnail?.photo?.local?.path?.takeIf { fileHelper.isValidPath(it) }
                CachedMessageContent(
                    "photo",
                    content.caption.text,
                    encodeMeta(best?.width ?: 0, best?.height ?: 0),
                    fileId = fileId,
                    path = path,
                    thumbnailPath = thumbnailPath,
                    minithumbnail = content.photo.minithumbnail?.data
                )
            }

            is TdApi.MessageVideo -> {
                val fileId = content.video.video.id
                val path = content.video.video.local.path.takeIf { fileHelper.isValidPath(it) }
                CachedMessageContent(
                    "video",
                    content.caption.text,
                    encodeMeta(
                        content.video.width,
                        content.video.height,
                        content.video.duration,
                        content.video.thumbnail?.file?.local?.path
                            ?.takeIf { fileHelper.isValidPath(it) }
                            .orEmpty(),
                        if (content.video.supportsStreaming) 1 else 0
                    ),
                    fileId = fileId,
                    path = path,
                    thumbnailPath = content.video.thumbnail?.file?.local?.path?.takeIf { fileHelper.isValidPath(it) },
                    minithumbnail = content.video.minithumbnail?.data
                )
            }

            is TdApi.MessageVoiceNote -> CachedMessageContent(
                "voice",
                content.caption.text,
                encodeMeta(content.voiceNote.duration),
                fileId = content.voiceNote.voice.id,
                path = content.voiceNote.voice.local.path.takeIf { fileHelper.isValidPath(it) }
            )

            is TdApi.MessageVideoNote -> CachedMessageContent(
                "video_note",
                "",
                encodeMeta(
                    content.videoNote.duration,
                    content.videoNote.length,
                    content.videoNote.thumbnail?.file?.local?.path
                        ?.takeIf { fileHelper.isValidPath(it) }
                        .orEmpty()
                ),
                fileId = content.videoNote.video.id,
                path = content.videoNote.video.local.path.takeIf { fileHelper.isValidPath(it) }
            )

            is TdApi.MessageSticker -> {
                val format = when (content.sticker.format) {
                    is TdApi.StickerFormatWebp -> "webp"
                    is TdApi.StickerFormatTgs -> "tgs"
                    is TdApi.StickerFormatWebm -> "webm"
                    else -> "unknown"
                }
                CachedMessageContent(
                    "sticker",
                    content.sticker.emoji,
                    encodeMeta(
                        content.sticker.setId,
                        content.sticker.emoji,
                        content.sticker.width,
                        content.sticker.height,
                        format
                    ),
                    fileId = content.sticker.sticker.id,
                    path = content.sticker.sticker.local.path.takeIf { fileHelper.isValidPath(it) }
                )
            }

            is TdApi.MessageDocument -> CachedMessageContent(
                "document",
                content.caption.text,
                encodeMeta(content.document.fileName, content.document.mimeType, content.document.document.size),
                fileId = content.document.document.id,
                path = content.document.document.local.path.takeIf { fileHelper.isValidPath(it) }
            )

            is TdApi.MessageAudio -> CachedMessageContent(
                "audio",
                content.caption.text,
                encodeMeta(
                    content.audio.duration,
                    content.audio.title.orEmpty(),
                    content.audio.performer.orEmpty(),
                    content.audio.fileName.orEmpty()
                ),
                fileId = content.audio.audio.id,
                path = content.audio.audio.local.path.takeIf { fileHelper.isValidPath(it) }
            )

            is TdApi.MessageAnimation -> CachedMessageContent(
                "gif",
                content.caption.text,
                encodeMeta(
                    content.animation.width,
                    content.animation.height,
                    content.animation.duration,
                    content.animation.thumbnail?.file?.local?.path
                        ?.takeIf { fileHelper.isValidPath(it) }
                        .orEmpty()
                ),
                fileId = content.animation.animation.id,
                path = content.animation.animation.local.path.takeIf { fileHelper.isValidPath(it) }
            )

            is TdApi.MessagePoll -> CachedMessageContent(
                "poll",
                content.poll.question.text,
                encodeMeta(content.poll.options.size, if (content.poll.isClosed) 1 else 0)
            )

            is TdApi.MessageContact -> CachedMessageContent(
                "contact",
                listOf(content.contact.firstName, content.contact.lastName).filter { it.isNotBlank() }
                    .joinToString(" "),
                encodeMeta(
                    content.contact.phoneNumber,
                    content.contact.firstName,
                    content.contact.lastName,
                    content.contact.userId
                )
            )

            is TdApi.MessageLocation -> CachedMessageContent(
                "location",
                "",
                encodeMeta(content.location.latitude, content.location.longitude, content.livePeriod)
            )

            is TdApi.MessageCall -> CachedMessageContent("service", "Call (${content.duration}s)", null)
            is TdApi.MessagePinMessage -> CachedMessageContent("service", "Pinned a message", null)
            is TdApi.MessageChatAddMembers -> CachedMessageContent("service", "Added members", null)
            is TdApi.MessageChatDeleteMember -> CachedMessageContent("service", "Removed a member", null)
            is TdApi.MessageChatChangeTitle -> CachedMessageContent("service", "Changed title", null)
            is TdApi.MessageAnimatedEmoji -> CachedMessageContent("text", content.emoji, null)
            is TdApi.MessageDice -> CachedMessageContent("text", content.emoji, null)
            else -> CachedMessageContent("unsupported", "", null)
        }
    }

    fun mapEntityToModel(entity: MessageDbEntity): MessageModel {
        val meta = decodeMeta(entity.contentMeta)
        val usesLegacyEmbeddedMedia = entity.mediaFileId == 0 && entity.mediaPath.isNullOrBlank()
        val (legacyFileId, legacyPath) = if (usesLegacyEmbeddedMedia) {
            resolveLegacyMediaFromMeta(entity.contentType, meta)
        } else {
            0 to null
        }
        val mediaFileId = entity.mediaFileId.takeIf { it != 0 } ?: legacyFileId
        val mediaPath = entity.mediaPath?.takeIf { it.isNotBlank() } ?: legacyPath
        val replyToMsgId = entity.replyToMessageId.takeIf { it != 0L }
        val replyPreview = resolveReplyPreview(entity)
        val replyPreviewModel =
            if (replyToMsgId != null && replyPreview != null) createReplyPreviewModel(
                entity,
                replyToMsgId,
                replyPreview
            ) else null

        val cachedSenderUser = entity.senderId.takeIf { it > 0L }?.let { cache.getUser(it) }
        val cachedSenderChat = if (cachedSenderUser == null && entity.senderId > 0L) {
            cache.getChat(entity.senderId)
        } else {
            null
        }

        val resolvedSenderName = resolveSenderNameFromCache(entity.senderId, entity.senderName)
        val resolvedSenderAvatar = when {
            cachedSenderUser != null -> fileHelper.resolveLocalFilePath(cachedSenderUser.profilePhoto?.small)
            cachedSenderChat != null -> fileHelper.resolveLocalFilePath(cachedSenderChat.photo?.small)
            else -> null
        }
        val resolvedSenderPersonalAvatar = cache.getUserFullInfo(entity.senderId)
            ?.personalPhoto
            ?.sizes
            ?.firstOrNull()
            ?.photo
            ?.let { fileHelper.resolveLocalFilePath(it) }

        val senderStatusEmojiId = when (val type = cachedSenderUser?.emojiStatus?.type) {
            is TdApi.EmojiStatusTypeCustomEmoji -> type.customEmojiId
            is TdApi.EmojiStatusTypeUpgradedGift -> type.modelCustomEmojiId
            else -> 0L
        }

        val forwardInfo = entity.forwardFromName
            ?.takeIf { it.isNotBlank() }
            ?.let { fromName ->
                ForwardInfo(
                    date = entity.forwardDate.takeIf { it > 0 } ?: entity.date,
                    fromId = entity.forwardFromId,
                    fromName = fromName,
                    originChatId = entity.forwardOriginChatId,
                    originMessageId = entity.forwardOriginMessageId
                )
            }

        val content: MessageContent = when (entity.contentType) {
            "text" -> MessageContent.Text(entity.content)

            "photo" -> {
                val fileId = mediaFileId
                fileHelper.registerCachedFile(fileId, entity.chatId, entity.id)
                MessageContent.Photo(
                    path = fileHelper.resolveCachedPath(fileId, mediaPath),
                    thumbnailPath = entity.mediaThumbnailPath?.takeIf { fileHelper.isValidPath(it) },
                    width = meta.getOrNull(0)?.toIntOrNull() ?: 0,
                    height = meta.getOrNull(1)?.toIntOrNull() ?: 0,
                    caption = entity.content,
                    fileId = fileId,
                    minithumbnail = entity.minithumbnail
                )
            }

            "video" -> {
                val fileId = mediaFileId
                val supportsStreaming = if (usesLegacyEmbeddedMedia) {
                    (meta.getOrNull(6)?.toIntOrNull() ?: 0) == 1
                } else {
                    (meta.getOrNull(4)?.toIntOrNull() ?: 0) == 1
                }
                fileHelper.registerCachedFile(fileId, entity.chatId, entity.id)
                MessageContent.Video(
                    path = fileHelper.resolveCachedPath(fileId, mediaPath),
                    thumbnailPath = (
                            entity.mediaThumbnailPath?.takeIf { fileHelper.isValidPath(it) }
                                ?: meta.getOrNull(3)
                            )?.takeIf { fileHelper.isValidPath(it) },
                    width = meta.getOrNull(0)?.toIntOrNull() ?: 0,
                    height = meta.getOrNull(1)?.toIntOrNull() ?: 0,
                    duration = meta.getOrNull(2)?.toIntOrNull() ?: 0,
                    caption = entity.content,
                    fileId = fileId,
                    supportsStreaming = supportsStreaming,
                    minithumbnail = entity.minithumbnail
                )
            }

            "voice" -> {
                val fileId = mediaFileId
                fileHelper.registerCachedFile(fileId, entity.chatId, entity.id)
                MessageContent.Voice(
                    path = fileHelper.resolveCachedPath(fileId, mediaPath),
                    duration = meta.getOrNull(0)?.toIntOrNull() ?: 0,
                    fileId = fileId
                )
            }

            "video_note" -> {
                val fileId = mediaFileId
                val storedThumbPath = if (usesLegacyEmbeddedMedia) meta.getOrNull(4) else meta.getOrNull(2)
                fileHelper.registerCachedFile(fileId, entity.chatId, entity.id)
                MessageContent.VideoNote(
                    path = fileHelper.resolveCachedPath(fileId, mediaPath),
                    thumbnail = storedThumbPath?.takeIf { fileHelper.isValidPath(it) },
                    duration = meta.getOrNull(0)?.toIntOrNull() ?: 0,
                    length = meta.getOrNull(1)?.toIntOrNull() ?: 0,
                    fileId = fileId
                )
            }

            "sticker" -> {
                val fileId = mediaFileId
                fileHelper.registerCachedFile(fileId, entity.chatId, entity.id)
                MessageContent.Sticker(
                    id = 0L,
                    setId = meta.getOrNull(0)?.toLongOrNull() ?: 0L,
                    path = fileHelper.resolveCachedPath(fileId, mediaPath),
                    width = meta.getOrNull(2)?.toIntOrNull() ?: 0,
                    height = meta.getOrNull(3)?.toIntOrNull() ?: 0,
                    emoji = entity.content,
                    fileId = fileId
                )
            }

            "document" -> {
                val fileId = mediaFileId
                fileHelper.registerCachedFile(fileId, entity.chatId, entity.id)
                MessageContent.Document(
                    path = fileHelper.resolveCachedPath(fileId, mediaPath),
                    fileName = meta.getOrNull(0).orEmpty(),
                    mimeType = meta.getOrNull(1).orEmpty(),
                    size = meta.getOrNull(2)?.toLongOrNull() ?: 0L,
                    caption = entity.content,
                    fileId = fileId
                )
            }

            "audio" -> {
                val fileId = mediaFileId
                fileHelper.registerCachedFile(fileId, entity.chatId, entity.id)
                MessageContent.Audio(
                    path = fileHelper.resolveCachedPath(fileId, mediaPath),
                    duration = meta.getOrNull(0)?.toIntOrNull() ?: 0,
                    title = meta.getOrNull(1).orEmpty(),
                    performer = meta.getOrNull(2).orEmpty(),
                    fileName = meta.getOrNull(3).orEmpty(),
                    mimeType = "",
                    size = 0L,
                    caption = entity.content,
                    fileId = fileId
                )
            }

            "gif" -> {
                val fileId = mediaFileId
                fileHelper.registerCachedFile(fileId, entity.chatId, entity.id)
                MessageContent.Gif(
                    path = fileHelper.resolveCachedPath(fileId, mediaPath),
                    width = meta.getOrNull(0)?.toIntOrNull() ?: 0,
                    height = meta.getOrNull(1)?.toIntOrNull() ?: 0,
                    caption = entity.content,
                    fileId = fileId
                )
            }

            "poll" -> MessageContent.Poll(
                id = 0L,
                question = entity.content,
                options = emptyList(),
                totalVoterCount = 0,
                isClosed = (meta.getOrNull(1)?.toIntOrNull() ?: 0) == 1,
                isAnonymous = true,
                type = PollType.Regular(false),
                openPeriod = 0,
                closeDate = 0
            )

            "contact" -> MessageContent.Contact(
                phoneNumber = meta.getOrNull(0).orEmpty(),
                firstName = meta.getOrNull(1).orEmpty(),
                lastName = meta.getOrNull(2).orEmpty(),
                vcard = "",
                userId = meta.getOrNull(3)?.toLongOrNull() ?: 0L
            )

            "location" -> MessageContent.Location(
                latitude = meta.getOrNull(0)?.toDoubleOrNull() ?: 0.0,
                longitude = meta.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
                livePeriod = meta.getOrNull(2)?.toIntOrNull() ?: 0
            )

            "service" -> MessageContent.Service(entity.content)
            else -> MessageContent.Text(entity.content)
        }

        return MessageModel(
            id = entity.id,
            date = entity.date,
            isOutgoing = entity.isOutgoing,
            senderName = resolvedSenderName,
            chatId = entity.chatId,
            content = content,
            senderId = entity.senderId,
            senderAvatar = resolvedSenderAvatar,
            senderPersonalAvatar = resolvedSenderPersonalAvatar,
            isRead = entity.isRead,
            replyToMsgId = replyToMsgId,
            replyToMsg = replyPreviewModel,
            forwardInfo = forwardInfo,
            mediaAlbumId = entity.mediaAlbumId,
            editDate = entity.editDate,
            views = entity.viewCount,
            viewCount = entity.viewCount,
            replyCount = entity.replyCount,
            isSenderVerified = cachedSenderUser?.verificationStatus?.isVerified ?: false,
            isSenderPremium = cachedSenderUser?.isPremium ?: false,
            senderStatusEmojiId = senderStatusEmojiId
        )
    }

    private fun resolveSenderNameFromCache(senderId: Long, fallback: String): String {
        val user = cache.getUser(senderId)
        if (user != null) {
            return SenderNameResolver.fromParts(
                firstName = user.firstName,
                lastName = user.lastName,
                fallback = fallback.ifBlank { "User" }
            )
        }

        val chat = cache.getChat(senderId)
        if (chat != null) {
            return chat.title.takeIf { it.isNotBlank() } ?: fallback.ifBlank { "User" }
        }

        return fallback.ifBlank { "User" }
    }

    private fun buildReplyPreview(msg: TdApi.Message): CachedReplyPreview? {
        val reply = msg.replyTo as? TdApi.MessageReplyToMessage ?: return null
        val replied = cache.getMessage(msg.chatId, reply.messageId) ?: return null
        val replySenderName = when (val sender = replied.senderId) {
            is TdApi.MessageSenderUser -> {
                val user = cache.getUser(sender.userId)
                SenderNameResolver.fromParts(
                    firstName = user?.firstName,
                    lastName = user?.lastName,
                    fallback = "User"
                )
            }

            is TdApi.MessageSenderChat -> cache.getChat(sender.chatId)?.title.orEmpty()
            else -> ""
        }
        val extracted = extractCachedContent(replied.content)
        return CachedReplyPreview(
            senderName = replySenderName,
            contentType = extracted.type,
            text = extracted.text.take(100)
        )
    }

    private fun encodeReplyPreview(preview: CachedReplyPreview): String {
        return "${preview.senderName}|${preview.contentType}|${preview.text}"
    }

    private fun parseReplyPreview(raw: String?): CachedReplyPreview? {
        if (raw.isNullOrBlank()) return null
        val firstSeparator = raw.indexOf('|')
        val secondSeparator = raw.indexOf('|', firstSeparator + 1)
        if (firstSeparator < 0 || secondSeparator <= firstSeparator) return null

        val senderName = raw.substring(0, firstSeparator)
        val contentType = raw.substring(firstSeparator + 1, secondSeparator)
        val text = raw.substring(secondSeparator + 1)
        if (contentType.isBlank()) return null

        return CachedReplyPreview(senderName = senderName, contentType = contentType, text = text)
    }

    private fun resolveReplyPreview(entity: MessageDbEntity): CachedReplyPreview? {
        val encodedPreview = parseReplyPreview(entity.replyToPreview)
        val senderName = entity.replyToPreviewSenderName ?: encodedPreview?.senderName
        val contentType = entity.replyToPreviewType ?: encodedPreview?.contentType
        val text = entity.replyToPreviewText ?: encodedPreview?.text ?: ""

        if (senderName.isNullOrBlank() && contentType.isNullOrBlank() && text.isBlank()) {
            return null
        }

        return CachedReplyPreview(
            senderName = senderName.orEmpty(),
            contentType = contentType?.ifBlank { "text" } ?: "text",
            text = text
        )
    }

    private fun createReplyPreviewModel(
        entity: MessageDbEntity,
        replyToMsgId: Long,
        preview: CachedReplyPreview
    ): MessageModel {
        return MessageModel(
            id = replyToMsgId,
            date = entity.date,
            isOutgoing = false,
            senderName = preview.senderName.ifBlank { "Unknown" },
            chatId = entity.chatId,
            content = mapReplyPreviewContent(preview),
            senderId = 0L,
            isRead = true
        )
    }

    private fun mapReplyPreviewContent(preview: CachedReplyPreview): MessageContent {
        return when (preview.contentType) {
            "photo" -> MessageContent.Photo(path = null, width = 0, height = 0, caption = preview.text)
            "video" -> MessageContent.Video(path = null, width = 0, height = 0, duration = 0, caption = preview.text)
            "voice" -> MessageContent.Voice(path = null, duration = 0)
            "video_note" -> MessageContent.VideoNote(path = null, thumbnail = null, duration = 0, length = 0)
            "sticker" -> MessageContent.Sticker(
                id = 0L,
                setId = 0L,
                path = null,
                width = 0,
                height = 0,
                emoji = preview.text
            )

            "document" -> MessageContent.Document(
                path = null,
                fileName = "",
                mimeType = "",
                size = 0L,
                caption = preview.text
            )

            "audio" -> MessageContent.Audio(
                path = null,
                duration = 0,
                title = "",
                performer = "",
                fileName = "",
                mimeType = "",
                size = 0L,
                caption = preview.text
            )

            "gif" -> MessageContent.Gif(path = null, width = 0, height = 0, caption = preview.text)
            "poll" -> MessageContent.Poll(
                id = 0L,
                question = preview.text,
                options = emptyList(),
                totalVoterCount = 0,
                isClosed = false,
                isAnonymous = true,
                type = PollType.Regular(false),
                openPeriod = 0,
                closeDate = 0
            )

            "contact" -> MessageContent.Contact(
                phoneNumber = "",
                firstName = preview.text,
                lastName = "",
                vcard = "",
                userId = 0L
            )

            "location" -> MessageContent.Location(latitude = 0.0, longitude = 0.0)
            "service" -> MessageContent.Service(preview.text)
            else -> MessageContent.Text(preview.text)
        }
    }

    private fun extractForwardOrigin(origin: TdApi.MessageOrigin): CachedForwardOrigin {
        return when (origin) {
            is TdApi.MessageOriginUser -> {
                val user = cache.getUser(origin.senderUserId)
                val name = SenderNameResolver.fromParts(
                    firstName = user?.firstName,
                    lastName = user?.lastName,
                    fallback = "User"
                )
                CachedForwardOrigin(fromName = name, fromId = origin.senderUserId)
            }

            is TdApi.MessageOriginChat -> CachedForwardOrigin(
                fromName = cache.getChat(origin.senderChatId)?.title ?: "Chat",
                fromId = origin.senderChatId
            )

            is TdApi.MessageOriginChannel -> CachedForwardOrigin(
                fromName = cache.getChat(origin.chatId)?.title ?: "Channel",
                fromId = origin.chatId,
                originChatId = origin.chatId,
                originMessageId = origin.messageId
            )

            is TdApi.MessageOriginHiddenUser -> CachedForwardOrigin(
                fromName = origin.senderName.ifBlank { "Hidden user" },
                fromId = 0L
            )

            else -> CachedForwardOrigin(fromName = "Unknown", fromId = 0L)
        }
    }

    private fun encodeEntities(content: TdApi.MessageContent): String? {
        val formatted = when (content) {
            is TdApi.MessageText -> content.text
            is TdApi.MessagePhoto -> content.caption
            is TdApi.MessageVideo -> content.caption
            is TdApi.MessageDocument -> content.caption
            is TdApi.MessageAudio -> content.caption
            is TdApi.MessageAnimation -> content.caption
            is TdApi.MessageVoiceNote -> content.caption
            else -> null
        } ?: return null

        if (formatted.entities.isNullOrEmpty()) return null

        return buildString {
            formatted.entities.forEachIndexed { index, entity ->
                if (index > 0) append('|')
                append(entity.offset).append(',').append(entity.length).append(',')
                when (val type = entity.type) {
                    is TdApi.TextEntityTypeBold -> append("b")
                    is TdApi.TextEntityTypeItalic -> append("i")
                    is TdApi.TextEntityTypeUnderline -> append("u")
                    is TdApi.TextEntityTypeStrikethrough -> append("s")
                    is TdApi.TextEntityTypeSpoiler -> append("sp")
                    is TdApi.TextEntityTypeCode -> append("c")
                    is TdApi.TextEntityTypePre -> append("p")
                    is TdApi.TextEntityTypeUrl -> append("url")
                    is TdApi.TextEntityTypeTextUrl -> append("turl,").append(type.url)
                    is TdApi.TextEntityTypeMention -> append("m")
                    is TdApi.TextEntityTypeMentionName -> append("mn,").append(type.userId)
                    is TdApi.TextEntityTypeHashtag -> append("h")
                    is TdApi.TextEntityTypeBotCommand -> append("bc")
                    is TdApi.TextEntityTypeCustomEmoji -> append("ce,").append(type.customEmojiId)
                    is TdApi.TextEntityTypeEmailAddress -> append("em")
                    is TdApi.TextEntityTypePhoneNumber -> append("ph")
                    else -> append("?")
                }
            }
        }
    }

    private fun encodeMeta(vararg parts: Any?): String {
        return parts.joinToString(META_SEPARATOR.toString()) { it?.toString().orEmpty() }
    }

    private fun decodeMeta(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return if (raw.contains(META_SEPARATOR)) raw.split(META_SEPARATOR) else raw.split('|')
    }

    private fun resolveLegacyMediaFromMeta(contentType: String, meta: List<String>): Pair<Int, String?> {
        return when (contentType) {
            "photo" -> (meta.getOrNull(2)?.toIntOrNull() ?: 0) to meta.getOrNull(3)
            "video" -> (meta.getOrNull(3)?.toIntOrNull() ?: 0) to meta.getOrNull(4)
            "voice" -> (meta.getOrNull(1)?.toIntOrNull() ?: 0) to meta.getOrNull(2)
            "video_note" -> (meta.getOrNull(2)?.toIntOrNull() ?: 0) to meta.getOrNull(3)
            "sticker" -> (meta.getOrNull(4)?.toIntOrNull() ?: 0) to meta.getOrNull(6)
            "document" -> (meta.getOrNull(3)?.toIntOrNull() ?: 0) to meta.getOrNull(4)
            "audio" -> (meta.getOrNull(4)?.toIntOrNull() ?: 0) to meta.getOrNull(5)
            "gif" -> (meta.getOrNull(3)?.toIntOrNull() ?: 0) to meta.getOrNull(4)
            else -> 0 to null
        }
    }

    private fun resolveMessageDate(msg: TdApi.Message): Int {
        return when (val schedulingState = msg.schedulingState) {
            is TdApi.MessageSchedulingStateSendAtDate -> schedulingState.sendDate
            else -> msg.date
        }
    }

    private companion object {
        private const val META_SEPARATOR = '\u001F'
    }
}