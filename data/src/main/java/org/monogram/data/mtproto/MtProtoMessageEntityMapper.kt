package org.monogram.data.mtproto

import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType
import org.monogram.mtproto.tl.generated.cloud.layer223.InputMessageEntityMentionName
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUser_4020eae812
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntity as TlMessageEntity
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityBankCard
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityBlockquote
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityBold
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityBotCommand
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityCashtag
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityCode
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityCustomEmoji
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityEmail
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityFormattedDate
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityHashtag
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityItalic
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityMention
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityPhone
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityPre
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntitySpoiler
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityStrike
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityTextUrl
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityUnderline
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityUrl

internal suspend fun MessageEntity.toMtProtoEntity(
    scope: MtProtoAuthKeyScope,
    text: String,
    users: MtProtoUserProjectionStore,
): TlMessageEntity {
    require(offset >= 0 && length > 0 && offset <= text.length - length) {
        "MTProto message entity range is outside the message text"
    }
    val end = offset + length
    require(offset == 0 || !Character.isLowSurrogate(text[offset])) {
        "MTProto message entity offset splits a UTF-16 surrogate pair"
    }
    require(end == text.length || !Character.isHighSurrogate(text[end - 1])) {
        "MTProto message entity length splits a UTF-16 surrogate pair"
    }
    return when (val entityType = type) {
        MessageEntityType.Bold -> MessageEntityBold(offset, length)
        MessageEntityType.Italic -> MessageEntityItalic(offset, length)
        MessageEntityType.Underline -> MessageEntityUnderline(offset, length)
        MessageEntityType.Strikethrough -> MessageEntityStrike(offset, length)
        MessageEntityType.Spoiler -> MessageEntitySpoiler(offset, length)
        MessageEntityType.BlockQuote -> MessageEntityBlockquote(false, offset, length)
        MessageEntityType.BlockQuoteExpandable -> MessageEntityBlockquote(true, offset, length)
        MessageEntityType.Code -> MessageEntityCode(offset, length)
        is MessageEntityType.Pre -> MessageEntityPre(offset, length, entityType.language)
        is MessageEntityType.TextUrl -> MessageEntityTextUrl(offset, length, entityType.url)
        MessageEntityType.Mention -> MessageEntityMention(offset, length)
        is MessageEntityType.TextMention -> {
            val user = requireNotNull(users.get(scope, entityType.userId)) {
                "Missing MTProto user projection for text mention: ${entityType.userId}"
            }
            val accessHash = requireNotNull(user.accessHash) {
                "Missing MTProto user access hash for text mention: ${entityType.userId}"
            }
            InputMessageEntityMentionName(offset, length, InputUser_4020eae812(entityType.userId, accessHash))
        }
        MessageEntityType.Hashtag -> MessageEntityHashtag(offset, length)
        MessageEntityType.Cashtag -> MessageEntityCashtag(offset, length)
        MessageEntityType.BotCommand -> MessageEntityBotCommand(offset, length)
        MessageEntityType.Url -> MessageEntityUrl(offset, length)
        MessageEntityType.Email -> MessageEntityEmail(offset, length)
        MessageEntityType.PhoneNumber -> MessageEntityPhone(offset, length)
        MessageEntityType.BankCardNumber -> MessageEntityBankCard(offset, length)
        is MessageEntityType.DateTime -> MessageEntityFormattedDate(false, false, false, false, false, false, offset, length, entityType.unixTime)
        is MessageEntityType.CustomEmoji -> {
            require(entityType.emojiId > 0) { "MTProto custom emoji id must be positive" }
            MessageEntityCustomEmoji(offset, length, entityType.emojiId)
        }
        is MessageEntityType.MediaTimestamp, is MessageEntityType.Other -> throw UnsupportedOperationException("MTProto message entity type is not available")
    }
}
