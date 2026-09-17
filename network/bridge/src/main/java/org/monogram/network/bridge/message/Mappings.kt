package org.monogram.network.bridge.message

import org.monogram.core.models.Checklists
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.ReplyMarkups
import org.monogram.core.models.TextEntities
import org.monogram.core.models.UploadItem
import uniffi.monogram_mtproto.MessageDto

internal fun MessageDto.toModel(): Message = Message(
    id = MessageId(chatId = PeerId(chatId), id = id),
    senderId = senderId?.let(::PeerId),
    text = text,
    date = date,
    editDate = editDate,
    outgoing = outgoing,
    mediaCacheKey = mediaCacheKey,
    mediaKind = mediaKind,
    thumbCacheKey = thumbCacheKey,
    mediaDuration = mediaDuration,
    mediaWidth = mediaWidth,
    mediaHeight = mediaHeight,
    groupedId = groupedId,
    fileName = if (mediaKind == "todo") null else fileName,
    fileSize = fileSize,
    reactionsJson = reactionsJson,
    repliesCount = repliesCount,
    discussionPeerId = discussionPeerId,
    replyQuote = replyQuote,
    entities = TextEntities.parse(entitiesJson),
    noforwards = noforwards,
    replyToMsgId = replyToMsgId,
    replyToTopId = replyToTopId,
    fwdFrom = fwdFrom,
    fwdFromId = fwdFromId,
    fwdDate = fwdDate,
    viaBot = viaBot,
    senderName = senderName,
    senderEmojiStatusDocumentId = senderEmojiStatusDocumentId,
    replyMarkup = ReplyMarkups.parse(replyMarkupJson),
    checklist = if (mediaKind == "todo") Checklists.parse(fileName) else null,
)

internal fun UploadItem.toDto() = uniffi.monogram_mtproto.UploadItemDto(
    path = path,
    kind = kind,
    mimeType = mimeType,
    fileName = fileName,
    caption = caption,
    duration = duration,
    width = width,
    height = height,
    randomId = randomId,
)
