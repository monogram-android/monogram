package org.monogram.data.mapper.message

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.data.chats.ChatCache
import org.monogram.data.datasource.remote.TdMessageRemoteDataSource
import org.monogram.data.mapper.CustomEmojiLoader
import org.monogram.data.mapper.TdFileHelper
import org.monogram.data.mapper.WebPageMapper
import org.monogram.data.mapper.toMessageEntityOrNull
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType
import org.monogram.domain.models.PollOption
import org.monogram.domain.models.PollType
import org.monogram.domain.models.StickerFormat
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.StringProvider

internal data class ContentMappingContext(
    val chatId: Long,
    val messageId: Long,
    val senderId: Long,
    val senderName: String,
    val networkAutoDownload: Boolean,
    val isActuallyUploading: Boolean
)

internal class MessageContentMapper(
    private val fileHelper: TdFileHelper,
    private val appPreferences: AppPreferencesProvider,
    private val customEmojiLoader: CustomEmojiLoader,
    private val webPageMapper: WebPageMapper,
    private val cache: ChatCache,
    private val scope: CoroutineScope,
    private val stringProvider: StringProvider
) {
    private val serviceMessageFormatter = ServiceMessageFormatter(
        stringProvider = stringProvider,
        cache = cache
    )

    fun mapContent(msg: TdApi.Message, context: ContentMappingContext): MessageContent {
        return when (val content = msg.content) {
            is TdApi.MessageText -> {
                val rawEntities = mapEntities(
                    entities = content.text.entities,
                    chatId = context.chatId,
                    messageId = context.messageId,
                    networkAutoDownload = context.networkAutoDownload
                )
                val entities = applyLoginCodeSpoiler(content.text.text, rawEntities, context.senderId)
                val webPage = webPageMapper.map(
                    webPage = content.linkPreview,
                    chatId = context.chatId,
                    messageId = context.messageId,
                    networkAutoDownload = context.networkAutoDownload
                )
                MessageContent.Text(content.text.text, entities, webPage)
            }

            is TdApi.MessagePhoto -> {
                val sizes = content.photo.sizes
                val photoSize = sizes.find { it.type == "x" }
                    ?: sizes.find { it.type == "m" }
                    ?: sizes.getOrNull(sizes.size / 2)
                    ?: sizes.lastOrNull()
                val thumbnailSize = sizes.find { it.type == "m" }
                    ?: sizes.find { it.type == "s" }
                    ?: sizes.firstOrNull()

                val photoFile = photoSize?.photo?.let(fileHelper::getUpdatedFile)
                val thumbnailFile = thumbnailSize?.photo?.let(fileHelper::getUpdatedFile)

                val path = fileHelper.findBestAvailablePath(photoFile, sizes)
                val thumbnailPath = fileHelper.resolveLocalFilePath(thumbnailFile)

                if (photoFile != null) {
                    fileHelper.registerCachedFile(photoFile.id, context.chatId, context.messageId)
                    if (path == null && context.networkAutoDownload) {
                        fileHelper.enqueueDownload(
                            photoFile.id,
                            1,
                            TdMessageRemoteDataSource.DownloadType.DEFAULT,
                            0,
                            0,
                            false
                        )
                    }
                }

                if (thumbnailFile != null) {
                    fileHelper.registerCachedFile(thumbnailFile.id, context.chatId, context.messageId)
                    if (thumbnailPath == null && context.networkAutoDownload) {
                        fileHelper.enqueueDownload(
                            thumbnailFile.id,
                            1,
                            TdMessageRemoteDataSource.DownloadType.DEFAULT,
                            0,
                            0,
                            false
                        )
                    }
                }

                val isDownloading = photoFile?.local?.isDownloadingActive ?: false
                val isQueued = photoFile?.let { fileHelper.isFileQueued(it.id) } ?: false
                val downloadProgress = photoFile?.let(fileHelper::computeDownloadProgress) ?: 0f

                MessageContent.Photo(
                    path = path,
                    thumbnailPath = thumbnailPath,
                    width = photoSize?.width ?: 0,
                    height = photoSize?.height ?: 0,
                    caption = content.caption.text,
                    entities = mapEntities(
                        entities = content.caption.entities,
                        chatId = context.chatId,
                        messageId = context.messageId,
                        networkAutoDownload = context.networkAutoDownload
                    ),
                    isUploading = context.isActuallyUploading && (photoFile?.remote?.isUploadingActive ?: false),
                    uploadProgress = photoFile?.let(fileHelper::computeUploadProgress) ?: 0f,
                    isDownloading = isDownloading || isQueued,
                    downloadProgress = downloadProgress,
                    fileId = photoFile?.id ?: 0,
                    minithumbnail = content.photo.minithumbnail?.data
                )
            }

            is TdApi.MessageVideo -> {
                val video = content.video
                val videoFile = fileHelper.getUpdatedFile(video.video)
                val path = fileHelper.resolveLocalFilePath(videoFile)
                fileHelper.registerCachedFile(videoFile.id, context.chatId, context.messageId)

                val thumbFile = video.thumbnail?.file?.let(fileHelper::getUpdatedFile)
                val thumbnailPath = fileHelper.resolveLocalFilePath(thumbFile)

                if (thumbFile != null) {
                    fileHelper.registerCachedFile(thumbFile.id, context.chatId, context.messageId)
                    if (thumbnailPath == null && context.networkAutoDownload) {
                        fileHelper.enqueueDownload(
                            thumbFile.id,
                            1,
                            TdMessageRemoteDataSource.DownloadType.DEFAULT,
                            0,
                            0,
                            false
                        )
                    }
                }

                if (path == null && context.networkAutoDownload && video.supportsStreaming) {
                    fileHelper.enqueueDownload(
                        videoFile.id,
                        1,
                        TdMessageRemoteDataSource.DownloadType.VIDEO,
                        0,
                        0,
                        false
                    )
                }

                val isDownloading = videoFile.local.isDownloadingActive
                val isQueued = fileHelper.isFileQueued(videoFile.id)
                val downloadProgress = fileHelper.computeDownloadProgress(videoFile)

                MessageContent.Video(
                    path = path,
                    thumbnailPath = thumbnailPath,
                    width = video.width,
                    height = video.height,
                    duration = video.duration,
                    caption = content.caption.text,
                    entities = mapEntities(
                        entities = content.caption.entities,
                        chatId = context.chatId,
                        messageId = context.messageId,
                        networkAutoDownload = context.networkAutoDownload
                    ),
                    isUploading = context.isActuallyUploading && videoFile.remote.isUploadingActive,
                    uploadProgress = fileHelper.computeUploadProgress(videoFile),
                    isDownloading = isDownloading || isQueued,
                    downloadProgress = downloadProgress,
                    fileId = videoFile.id,
                    minithumbnail = video.minithumbnail?.data,
                    supportsStreaming = video.supportsStreaming
                )
            }

            is TdApi.MessageVoiceNote -> {
                val voice = content.voiceNote
                val voiceFile = fileHelper.getUpdatedFile(voice.voice)
                val path = fileHelper.resolveLocalFilePath(voiceFile)
                fileHelper.registerCachedFile(voiceFile.id, context.chatId, context.messageId)

                if (path == null && context.networkAutoDownload) {
                    fileHelper.enqueueDownload(
                        voiceFile.id,
                        1,
                        TdMessageRemoteDataSource.DownloadType.DEFAULT,
                        0,
                        0,
                        false
                    )
                }

                val isDownloading = voiceFile.local.isDownloadingActive
                val isQueued = fileHelper.isFileQueued(voiceFile.id)
                val downloadProgress = fileHelper.computeDownloadProgress(voiceFile)

                MessageContent.Voice(
                    path = path,
                    duration = voice.duration,
                    waveform = voice.waveform,
                    isUploading = context.isActuallyUploading && voiceFile.remote.isUploadingActive,
                    uploadProgress = fileHelper.computeUploadProgress(voiceFile),
                    isDownloading = isDownloading || isQueued,
                    downloadProgress = downloadProgress,
                    fileId = voiceFile.id
                )
            }

            is TdApi.MessageVideoNote -> {
                val note = content.videoNote
                val videoFile = fileHelper.getUpdatedFile(note.video)
                val videoPath = fileHelper.resolveLocalFilePath(videoFile)
                fileHelper.registerCachedFile(videoFile.id, context.chatId, context.messageId)

                if (videoPath == null && context.networkAutoDownload && appPreferences.autoDownloadVideoNotes.value) {
                    fileHelper.enqueueDownload(
                        videoFile.id,
                        1,
                        TdMessageRemoteDataSource.DownloadType.VIDEO_NOTE,
                        0,
                        0,
                        false
                    )
                }

                val thumbFile = note.thumbnail?.file?.let(fileHelper::getUpdatedFile)
                val thumbPath = fileHelper.resolveLocalFilePath(thumbFile)
                if (thumbFile != null) {
                    fileHelper.registerCachedFile(thumbFile.id, context.chatId, context.messageId)
                    if (thumbPath == null && context.networkAutoDownload) {
                        fileHelper.enqueueDownload(
                            thumbFile.id,
                            1,
                            TdMessageRemoteDataSource.DownloadType.DEFAULT,
                            0,
                            0,
                            false
                        )
                    }
                }

                val isUploading = context.isActuallyUploading && videoFile.remote.isUploadingActive
                val uploadProgress = fileHelper.computeUploadProgress(videoFile)
                val isDownloading = videoFile.local.isDownloadingActive
                val isQueued = fileHelper.isFileQueued(videoFile.id)
                val downloadProgress = fileHelper.computeDownloadProgress(videoFile)

                MessageContent.VideoNote(
                    path = videoPath,
                    thumbnail = thumbPath,
                    duration = note.duration,
                    length = note.length,
                    isUploading = isUploading,
                    uploadProgress = uploadProgress,
                    isDownloading = isDownloading || isQueued,
                    downloadProgress = downloadProgress,
                    fileId = videoFile.id
                )
            }

            is TdApi.MessageSticker -> {
                val sticker = content.sticker
                val stickerFile = fileHelper.getUpdatedFile(sticker.sticker)
                val path = fileHelper.resolveLocalFilePath(stickerFile)

                fileHelper.registerCachedFile(stickerFile.id, context.chatId, context.messageId)
                if (path == null && context.networkAutoDownload && appPreferences.autoDownloadStickers.value) {
                    fileHelper.enqueueDownload(
                        stickerFile.id,
                        1,
                        TdMessageRemoteDataSource.DownloadType.STICKER,
                        0,
                        0,
                        false
                    )
                }

                val format = when (sticker.format) {
                    is TdApi.StickerFormatWebp -> StickerFormat.STATIC
                    is TdApi.StickerFormatTgs -> StickerFormat.ANIMATED
                    is TdApi.StickerFormatWebm -> StickerFormat.VIDEO
                    else -> StickerFormat.UNKNOWN
                }

                val isDownloading = stickerFile.local.isDownloadingActive
                val isQueued = fileHelper.isFileQueued(stickerFile.id)
                val downloadProgress = fileHelper.computeDownloadProgress(stickerFile)

                MessageContent.Sticker(
                    id = sticker.sticker.id.toLong(),
                    setId = sticker.setId,
                    path = path,
                    width = sticker.width,
                    height = sticker.height,
                    emoji = sticker.emoji,
                    format = format,
                    isDownloading = isDownloading || isQueued,
                    downloadProgress = downloadProgress,
                    fileId = stickerFile.id
                )
            }

            is TdApi.MessageAnimation -> {
                val animation = content.animation
                val animationFile = fileHelper.getUpdatedFile(animation.animation)
                val path = fileHelper.resolveLocalFilePath(animationFile)
                fileHelper.registerCachedFile(animationFile.id, context.chatId, context.messageId)

                if (path == null && context.networkAutoDownload) {
                    fileHelper.enqueueDownload(
                        animationFile.id,
                        1,
                        TdMessageRemoteDataSource.DownloadType.GIF,
                        0,
                        0,
                        false
                    )
                }

                val thumbFile = animation.thumbnail?.file?.let(fileHelper::getUpdatedFile)
                if (thumbFile != null) {
                    fileHelper.registerCachedFile(thumbFile.id, context.chatId, context.messageId)
                    if (!fileHelper.isValidPath(thumbFile.local.path) && context.networkAutoDownload) {
                        fileHelper.enqueueDownload(
                            thumbFile.id,
                            1,
                            TdMessageRemoteDataSource.DownloadType.DEFAULT,
                            0,
                            0,
                            false
                        )
                    }
                }

                val isDownloading = animationFile.local.isDownloadingActive
                val isQueued = fileHelper.isFileQueued(animationFile.id)
                val downloadProgress = fileHelper.computeDownloadProgress(animationFile)

                MessageContent.Gif(
                    path = path,
                    width = animation.width,
                    height = animation.height,
                    caption = content.caption.text,
                    entities = mapEntities(
                        entities = content.caption.entities,
                        chatId = context.chatId,
                        messageId = context.messageId,
                        networkAutoDownload = context.networkAutoDownload
                    ),
                    isUploading = context.isActuallyUploading && animationFile.remote.isUploadingActive,
                    uploadProgress = fileHelper.computeUploadProgress(animationFile),
                    isDownloading = isDownloading || isQueued,
                    downloadProgress = downloadProgress,
                    fileId = animationFile.id,
                    minithumbnail = animation.minithumbnail?.data
                )
            }

            is TdApi.MessageAnimatedEmoji -> {
                val customEmojiId =
                    (content.animatedEmoji.sticker?.fullType as? TdApi.StickerFullTypeCustomEmoji)?.customEmojiId
                val entities = if (customEmojiId != null && customEmojiId != 0L) {
                    val resolvedPath = customEmojiLoader.getPathIfValid(customEmojiId)
                    if (resolvedPath == null) {
                        scope.launch {
                            customEmojiLoader.loadIfNeeded(
                                emojiId = customEmojiId,
                                chatId = context.chatId,
                                messageId = context.messageId,
                                autoDownload = context.networkAutoDownload
                            )
                        }
                    }
                    listOf(
                        MessageEntity(
                            offset = 0,
                            length = content.emoji.length,
                            type = MessageEntityType.CustomEmoji(customEmojiId, resolvedPath)
                        )
                    )
                } else {
                    emptyList()
                }
                MessageContent.Text(content.emoji, entities)
            }
            is TdApi.MessageDice -> {
                val valueStr = if (content.value != 0) " (Result: ${content.value})" else ""
                MessageContent.Text("${content.emoji}$valueStr")
            }

            is TdApi.MessageDocument -> {
                val document = content.document
                val documentFile = fileHelper.getUpdatedFile(document.document)
                val path = fileHelper.resolveLocalFilePath(documentFile)
                fileHelper.registerCachedFile(documentFile.id, context.chatId, context.messageId)

                val thumbFile = document.thumbnail?.file?.let(fileHelper::getUpdatedFile)
                if (thumbFile != null) {
                    fileHelper.registerCachedFile(thumbFile.id, context.chatId, context.messageId)
                    if (!fileHelper.isValidPath(thumbFile.local.path) && context.networkAutoDownload) {
                        fileHelper.enqueueDownload(
                            thumbFile.id,
                            1,
                            TdMessageRemoteDataSource.DownloadType.DEFAULT,
                            0,
                            0,
                            false
                        )
                    }
                }

                val isDownloading = documentFile.local.isDownloadingActive
                val isQueued = fileHelper.isFileQueued(documentFile.id)
                val downloadProgress = fileHelper.computeDownloadProgress(documentFile)

                MessageContent.Document(
                    path = path,
                    fileName = document.fileName,
                    mimeType = document.mimeType,
                    size = documentFile.size,
                    caption = content.caption.text,
                    entities = mapEntities(
                        entities = content.caption.entities,
                        chatId = context.chatId,
                        messageId = context.messageId,
                        networkAutoDownload = context.networkAutoDownload
                    ),
                    isUploading = context.isActuallyUploading && documentFile.remote.isUploadingActive,
                    uploadProgress = fileHelper.computeUploadProgress(documentFile),
                    isDownloading = isDownloading || isQueued,
                    downloadProgress = downloadProgress,
                    fileId = documentFile.id
                )
            }

            is TdApi.MessageAudio -> {
                val audio = content.audio
                val audioFile = fileHelper.getUpdatedFile(audio.audio)
                val path = fileHelper.resolveLocalFilePath(audioFile)
                fileHelper.registerCachedFile(audioFile.id, context.chatId, context.messageId)

                if (path == null && context.networkAutoDownload) {
                    fileHelper.enqueueDownload(
                        audioFile.id,
                        1,
                        TdMessageRemoteDataSource.DownloadType.DEFAULT,
                        0,
                        0,
                        false
                    )
                }

                val isDownloading = audioFile.local.isDownloadingActive
                val isQueued = fileHelper.isFileQueued(audioFile.id)
                val downloadProgress = fileHelper.computeDownloadProgress(audioFile)

                MessageContent.Audio(
                    path = path,
                    duration = audio.duration,
                    title = audio.title ?: stringProvider.getString("unknown_user"),
                    performer = audio.performer ?: stringProvider.getString("unknown_user"),
                    fileName = audio.fileName ?: "audio.mp3",
                    mimeType = audio.mimeType ?: "audio/mpeg",
                    size = audioFile.size,
                    caption = content.caption.text,
                    entities = mapEntities(
                        entities = content.caption.entities,
                        chatId = context.chatId,
                        messageId = context.messageId,
                        networkAutoDownload = context.networkAutoDownload
                    ),
                    isUploading = context.isActuallyUploading && audioFile.remote.isUploadingActive,
                    uploadProgress = fileHelper.computeUploadProgress(audioFile),
                    isDownloading = isDownloading || isQueued,
                    downloadProgress = downloadProgress,
                    fileId = audioFile.id
                )
            }

            is TdApi.MessageCall -> {
                val label = stringProvider.getString("chat_mapper_call")
                val text = if (content.duration > 0) "$label (${content.duration}s)" else label
                MessageContent.Text(text)
            }
            is TdApi.MessageContact -> {
                val contact = content.contact
                MessageContent.Contact(
                    phoneNumber = contact.phoneNumber,
                    firstName = contact.firstName,
                    lastName = contact.lastName,
                    vcard = contact.vcard,
                    userId = contact.userId
                )
            }

            is TdApi.MessageLocation -> {
                val location = content.location
                MessageContent.Location(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    horizontalAccuracy = location.horizontalAccuracy,
                    livePeriod = content.livePeriod,
                    heading = content.heading,
                    proximityAlertRadius = content.proximityAlertRadius
                )
            }

            is TdApi.MessageVenue -> {
                val venue = content.venue
                MessageContent.Venue(
                    latitude = venue.location.latitude,
                    longitude = venue.location.longitude,
                    title = venue.title,
                    address = venue.address,
                    provider = venue.provider,
                    venueId = venue.id,
                    venueType = venue.type
                )
            }

            is TdApi.MessagePoll -> {
                val poll = content.poll
                val pollType = when (val type = poll.type) {
                    is TdApi.PollTypeRegular -> PollType.Regular(poll.allowsMultipleAnswers)
                    is TdApi.PollTypeQuiz -> {
                        PollType.Quiz(type.correctOptionIds.toList(), type.explanation?.text)
                    }

                    else -> PollType.Regular(poll.allowsMultipleAnswers)
                }

                MessageContent.Poll(
                    id = poll.id,
                    question = poll.question.text,
                    description = null,
                    options = poll.options.map { option ->
                        PollOption(
                            text = option.text.text,
                            voterCount = option.voterCount,
                            votePercentage = option.votePercentage,
                            isChosen = option.isChosen,
                            isBeingChosen = false
                        )
                    },
                    totalVoterCount = poll.totalVoterCount,
                    isClosed = poll.isClosed,
                    isAnonymous = poll.isAnonymous,
                    allowsRevoting = true,
                    shuffleOptions = false,
                    hideResultsUntilCloses = false,
                    type = pollType,
                    openPeriod = poll.openPeriod,
                    closeDate = poll.closeDate
                )
            }

            is TdApi.MessageGame -> {
                val label = stringProvider.getString("chat_mapper_game")
                val title = content.game.title.takeIf { it.isNotBlank() }
                MessageContent.Text(title?.let { "$label: $it" } ?: label)
            }

            is TdApi.MessageInvoice -> {
                val label = stringProvider.getString("chat_mapper_invoice")
                val title = content.productInfo.title.takeIf { it.isNotBlank() }
                MessageContent.Text(title?.let { "$label: $it" } ?: label)
            }

            is TdApi.MessageStory -> MessageContent.Text(stringProvider.getString("chat_mapper_story"))
            is TdApi.MessageExpiredPhoto -> MessageContent.Text(stringProvider.getString("message_expired_photo"))
            is TdApi.MessageExpiredVideo -> MessageContent.Text(stringProvider.getString("message_expired_video"))
            else -> serviceMessageFormatter.format(content, context)
                ?: MessageContent.Text("ℹ️ Unsupported message type: ${content.javaClass.simpleName}")
        }
    }

    fun mapEntities(
        entities: Array<TdApi.TextEntity>,
        chatId: Long,
        messageId: Long,
        networkAutoDownload: Boolean
    ): List<MessageEntity> {
        return entities.mapNotNull { entity ->
            entity.toMessageEntityOrNull(
                mapUnsupportedToOther = true,
                mentionNameAsMention = true,
                customEmojiPathResolver = { emojiId ->
                    customEmojiLoader.getPathIfValid(emojiId)
                },
                onMissingCustomEmoji = { emojiId ->
                    scope.launch {
                        customEmojiLoader.loadIfNeeded(emojiId, chatId, messageId, networkAutoDownload)
                    }
                }
            )
        }
    }

    fun resolveMessageDate(msg: TdApi.Message): Int {
        return when (val schedulingState = msg.schedulingState) {
            is TdApi.MessageSchedulingStateSendAtDate -> schedulingState.sendDate
            else -> msg.date
        }
    }

    private fun applyLoginCodeSpoiler(
        text: String,
        entities: List<MessageEntity>,
        senderId: Long
    ): List<MessageEntity> {
        if (senderId != TELEGRAM_SERVICE_ID && senderId != TELEGRAM_VERIFY_ID) {
            return entities
        }

        val match = LOGIN_CODE_PATTERN.find(text) ?: return entities
        val offset = match.range.first
        val length = match.range.last - match.range.first + 1

        val hasSpoiler = entities.any {
            it.type is MessageEntityType.Spoiler &&
                    it.offset <= offset &&
                    (it.offset + it.length) >= (offset + length)
        }

        return if (hasSpoiler) {
            entities
        } else {
            entities + MessageEntity(offset, length, MessageEntityType.Spoiler)
        }
    }

    companion object {
        private val LOGIN_CODE_PATTERN = Regex("""[\d\-]{5,8}""")
        private const val TELEGRAM_SERVICE_ID = 777000L
        private const val TELEGRAM_VERIFY_ID = 42777L
    }
}