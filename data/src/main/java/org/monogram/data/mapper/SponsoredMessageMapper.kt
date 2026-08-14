package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.data.mapper.message.ContentMappingContext
import org.monogram.data.mapper.message.MessageContentMapper
import org.monogram.domain.models.AdvertisementSponsorModel
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.SponsoredMessageModel

internal class SponsoredMessageMapper(
    private val fileHelper: TdFileHelper,
    private val contentMapper: MessageContentMapper
) {
    fun map(chatId: Long, message: TdApi.SponsoredMessage): SponsoredMessageModel {
        val sponsor = message.sponsor
        val mappedContent = mapContent(chatId, message)
        val sponsorPhotoPath = sponsor.photo
            ?.sizes
            ?.asSequence()
            ?.map { it.photo }
            ?.mapNotNull(fileHelper::resolveLocalFilePath)
            ?.firstOrNull()
        return SponsoredMessageModel(
            messageId = message.messageId,
            isRecommended = message.isRecommended,
            canBeReported = message.canBeReported,
            title = message.title.ifBlank { null },
            buttonText = message.buttonText.ifBlank { null },
            additionalInfo = message.additionalInfo.ifBlank { null },
            accentColorId = message.accentColorId,
            backgroundCustomEmojiId = message.backgroundCustomEmojiId,
            content = mappedContent,
            sponsor = AdvertisementSponsorModel(
                url = sponsor.url,
                photoPath = sponsorPhotoPath,
                info = sponsor.info.ifBlank { null }
            )
        )
    }

    private fun mapContent(chatId: Long, message: TdApi.SponsoredMessage): MessageContent {
        val tdMessage = TdApi.Message().apply {
            id = message.messageId
            this.chatId = chatId
            date = 0
            isOutgoing = false
            content = message.content
        }
        val mappedContent = contentMapper.mapContent(
            tdMessage,
            ContentMappingContext(
                chatId = chatId,
                messageId = message.messageId,
                senderId = 0L,
                senderName = message.title.orEmpty(),
                networkAutoDownload = false,
                isActuallyUploading = false
            )
        )
        enqueueSponsoredMediaDownload(chatId, message.messageId, mappedContent)
        return mappedContent
    }

    private fun enqueueSponsoredMediaDownload(
        chatId: Long,
        messageId: Long,
        content: MessageContent
    ) {
        when (content) {
            is MessageContent.Photo -> {
                if (content.path.isNullOrBlank() && content.fileId != 0) {
                    fileHelper.registerSponsoredCachedFile(content.fileId, chatId, messageId)
                    fileHelper.enqueueDownload(
                        fileId = content.fileId,
                        priority = 16,
                        downloadType = org.monogram.data.datasource.remote.TdMessageRemoteDataSource.DownloadType.DEFAULT
                    )
                }
            }

            is MessageContent.Video -> {
                if (content.path.isNullOrBlank() && content.fileId != 0 && !content.supportsStreaming) {
                    fileHelper.registerSponsoredCachedFile(content.fileId, chatId, messageId)
                    fileHelper.enqueueDownload(
                        fileId = content.fileId,
                        priority = 16,
                        downloadType = org.monogram.data.datasource.remote.TdMessageRemoteDataSource.DownloadType.VIDEO
                    )
                }
            }

            is MessageContent.Gif -> {
                if (content.path.isNullOrBlank() && content.fileId != 0) {
                    fileHelper.registerSponsoredCachedFile(content.fileId, chatId, messageId)
                    fileHelper.enqueueDownload(
                        fileId = content.fileId,
                        priority = 16,
                        downloadType = org.monogram.data.datasource.remote.TdMessageRemoteDataSource.DownloadType.GIF
                    )
                }
            }

            else -> Unit
        }
    }
}
