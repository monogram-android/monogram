package org.monogram.data.mapper.message

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.data.datasource.remote.TdMessageRemoteDataSource
import org.monogram.data.mapper.CustomEmojiLoader
import org.monogram.data.mapper.TdFileHelper
import org.monogram.data.mapper.WebPageMapper
import org.monogram.data.mapper.toMessageEntityOrNull
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.PollOption
import org.monogram.domain.models.PollType
import org.monogram.domain.models.StickerFormat
import org.monogram.domain.repository.AppPreferencesProvider

internal data class ContentMappingContext(
    val chatId: Long,
    val messageId: Long,
    val senderName: String,
    val networkAutoDownload: Boolean,
    val isActuallyUploading: Boolean
)

internal class MessageContentMapper(
    private val fileHelper: TdFileHelper,
    private val appPreferences: AppPreferencesProvider,
    private val customEmojiLoader: CustomEmojiLoader,
    private val webPageMapper: WebPageMapper,
    private val scope: CoroutineScope
) {
    fun mapContent(msg: TdApi.Message, context: ContentMappingContext): MessageContent {
        return when (val content = msg.content) {
            is TdApi.MessageText -> {
                val entities = mapEntities(
                    entities = content.text.entities,
                    chatId = context.chatId,
                    messageId = context.messageId,
                    networkAutoDownload = context.networkAutoDownload
                )
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

            is TdApi.MessageAnimatedEmoji -> MessageContent.Text(content.emoji)
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
                    title = audio.title ?: "Unknown",
                    performer = audio.performer ?: "Unknown",
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

            is TdApi.MessageCall -> MessageContent.Text("📞 Call (${content.duration}s)")
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

            is TdApi.MessageGame -> MessageContent.Text("🎮 Game: ${content.game.title}")
            is TdApi.MessageInvoice -> MessageContent.Text("💳 Invoice: ${content.productInfo.title}")
            is TdApi.MessageStory -> MessageContent.Text("📖 Story")
            is TdApi.MessageExpiredPhoto -> MessageContent.Text("📷 Photo has expired")
            is TdApi.MessageExpiredVideo -> MessageContent.Text("📹 Video has expired")

            is TdApi.MessageChatJoinByLink -> MessageContent.Service("${context.senderName} has joined the group via invite link")
            is TdApi.MessageChatAddMembers -> MessageContent.Service("${context.senderName} added members")
            is TdApi.MessageChatDeleteMember -> MessageContent.Service("${context.senderName}  left the chat")
            is TdApi.MessagePinMessage -> MessageContent.Service("${context.senderName} pinned a message")
            is TdApi.MessageChatChangeTitle -> MessageContent.Service("${context.senderName} changed group name to \"${content.title}\"")
            is TdApi.MessageChatChangePhoto -> MessageContent.Service("${context.senderName} changed group photo")
            is TdApi.MessageChatDeletePhoto -> MessageContent.Service("${context.senderName} removed group photo")
            is TdApi.MessageScreenshotTaken -> MessageContent.Service("${context.senderName} took a screenshot")
            is TdApi.MessageContactRegistered -> MessageContent.Service("${context.senderName} joined Telegram!")
            is TdApi.MessageChatUpgradeTo -> MessageContent.Service("${context.senderName} upgraded to supergroup")
            is TdApi.MessageChatUpgradeFrom -> MessageContent.Service("group created")
            is TdApi.MessageBasicGroupChatCreate -> MessageContent.Service("created the group \"${content.title}\"")
            is TdApi.MessageSupergroupChatCreate -> MessageContent.Service("created the supergroup \"${content.title}\"")
            is TdApi.MessagePaymentSuccessful -> MessageContent.Service("Payment successful: ${content.currency} ${content.totalAmount}")
            is TdApi.MessagePaymentSuccessfulBot -> MessageContent.Service("Payment successful")
            is TdApi.MessagePassportDataSent -> MessageContent.Service("Passport data sent")
            is TdApi.MessagePassportDataReceived -> MessageContent.Service("Passport data received")
            is TdApi.MessageProximityAlertTriggered -> MessageContent.Service("is within ${content.distance}m")
            is TdApi.MessageForumTopicCreated -> MessageContent.Service("${context.senderName} created topic \"${content.name}\"")
            is TdApi.MessageForumTopicEdited -> MessageContent.Service("${context.senderName} edited topic")
            is TdApi.MessageForumTopicIsClosedToggled -> MessageContent.Service("${context.senderName} toggled topic closed status")
            is TdApi.MessageForumTopicIsHiddenToggled -> MessageContent.Service("${context.senderName} toggled topic hidden status")
            is TdApi.MessageSuggestProfilePhoto -> MessageContent.Service("${context.senderName} suggested a profile photo")
            is TdApi.MessageCustomServiceAction -> MessageContent.Service(content.text)
            is TdApi.MessageChatBoost -> MessageContent.Service("Chat boost: ${content.boostCount}")
            is TdApi.MessageChatSetTheme -> MessageContent.Service("Chat theme changed to ${content.theme}")
            is TdApi.MessageGameScore -> MessageContent.Service("Game score: ${content.score}")
            is TdApi.MessageVideoChatScheduled -> MessageContent.Service("Video chat scheduled for ${content.startDate}")
            is TdApi.MessageVideoChatStarted -> MessageContent.Service("Video chat started")
            is TdApi.MessageVideoChatEnded -> MessageContent.Service("Video chat ended")
            is TdApi.MessageChatSetBackground -> MessageContent.Service("Chat background changed")
            else -> MessageContent.Text("ℹ️ Unsupported message type: ${content.javaClass.simpleName}")
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
}