package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType

internal fun TdApi.TextEntity.toMessageEntityOrNull(
    mapUnsupportedToOther: Boolean = false,
    mentionNameAsMention: Boolean = false,
    customEmojiPathResolver: ((Long) -> String?)? = null,
    onMissingCustomEmoji: ((Long) -> Unit)? = null
): MessageEntity? {
    val mappedType = when (val entityType = type) {
        is TdApi.TextEntityTypeBold -> MessageEntityType.Bold
        is TdApi.TextEntityTypeItalic -> MessageEntityType.Italic
        is TdApi.TextEntityTypeUnderline -> MessageEntityType.Underline
        is TdApi.TextEntityTypeStrikethrough -> MessageEntityType.Strikethrough
        is TdApi.TextEntityTypeSpoiler -> MessageEntityType.Spoiler
        is TdApi.TextEntityTypeCode -> MessageEntityType.Code
        is TdApi.TextEntityTypePre -> MessageEntityType.Pre()
        is TdApi.TextEntityTypePreCode -> MessageEntityType.Pre(entityType.language)
        is TdApi.TextEntityTypeTextUrl -> MessageEntityType.TextUrl(entityType.url)
        is TdApi.TextEntityTypeMention -> MessageEntityType.Mention
        is TdApi.TextEntityTypeMentionName -> {
            if (mentionNameAsMention) {
                MessageEntityType.Mention
            } else {
                MessageEntityType.TextMention(entityType.userId)
            }
        }

        is TdApi.TextEntityTypeHashtag -> MessageEntityType.Hashtag
        is TdApi.TextEntityTypeBotCommand -> MessageEntityType.BotCommand
        is TdApi.TextEntityTypeUrl -> MessageEntityType.Url
        is TdApi.TextEntityTypeEmailAddress -> MessageEntityType.Email
        is TdApi.TextEntityTypePhoneNumber -> MessageEntityType.PhoneNumber
        is TdApi.TextEntityTypeBankCardNumber -> MessageEntityType.BankCardNumber
        is TdApi.TextEntityTypeBlockQuote -> MessageEntityType.BlockQuote
        is TdApi.TextEntityTypeExpandableBlockQuote -> MessageEntityType.BlockQuoteExpandable
        is TdApi.TextEntityTypeCustomEmoji -> {
            val path = customEmojiPathResolver?.invoke(entityType.customEmojiId)
            if (path == null) {
                onMissingCustomEmoji?.invoke(entityType.customEmojiId)
            }
            MessageEntityType.CustomEmoji(entityType.customEmojiId, path)
        }

        else -> {
            if (mapUnsupportedToOther) {
                MessageEntityType.Other(entityType.javaClass.simpleName)
            } else {
                null
            }
        }
    } ?: return null

    return MessageEntity(offset = offset, length = length, type = mappedType)
}