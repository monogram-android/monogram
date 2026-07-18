package org.monogram.data.mapper.message

import org.drinkless.tdlib.TdApi
import org.monogram.data.compat.toLegacyToncoinCentCount
import org.monogram.domain.models.FactCheckModel
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType
import org.monogram.domain.models.SuggestedPostInfoModel
import org.monogram.domain.models.SuggestedPostPriceModel
import org.monogram.domain.models.SuggestedPostStateModel

internal fun TdApi.FactCheck.toDomain(): FactCheckModel = FactCheckModel(
    text = text?.text.orEmpty(),
    entities = text?.entities.toDomainEntities(),
    countryCode = countryCode.takeIf { it.isNotBlank() }
)

internal fun TdApi.SuggestedPostInfo.toDomain(): SuggestedPostInfoModel = SuggestedPostInfoModel(
    price = when (val value = price) {
        is TdApi.SuggestedPostPriceStar -> SuggestedPostPriceModel.Star(value.starCount)
        else -> value?.toLegacyToncoinCentCount()?.let(SuggestedPostPriceModel::Ton)
    },
    sendDate = sendDate,
    state = when (state) {
        is TdApi.SuggestedPostStateApproved -> SuggestedPostStateModel.APPROVED
        is TdApi.SuggestedPostStateDeclined -> SuggestedPostStateModel.DECLINED
        else -> SuggestedPostStateModel.PENDING
    },
    canBeApproved = canBeApproved,
    canBeDeclined = canBeDeclined
)

internal fun Array<TdApi.TextEntity>?.toDomainEntities(): List<MessageEntity> {
    if (isNullOrEmpty()) return emptyList()
    return mapNotNull { entity ->
        val type = when (val entityType = entity.type) {
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
            is TdApi.TextEntityTypeMentionName -> MessageEntityType.TextMention(entityType.userId)
            is TdApi.TextEntityTypeHashtag -> MessageEntityType.Hashtag
            is TdApi.TextEntityTypeCashtag -> MessageEntityType.Cashtag
            is TdApi.TextEntityTypeBotCommand -> MessageEntityType.BotCommand
            is TdApi.TextEntityTypeUrl -> MessageEntityType.Url
            is TdApi.TextEntityTypeEmailAddress -> MessageEntityType.Email
            is TdApi.TextEntityTypePhoneNumber -> MessageEntityType.PhoneNumber
            is TdApi.TextEntityTypeBankCardNumber -> MessageEntityType.BankCardNumber
            is TdApi.TextEntityTypeDateTime -> MessageEntityType.DateTime(entityType.unixTime)
            is TdApi.TextEntityTypeMediaTimestamp -> MessageEntityType.MediaTimestamp(entityType.mediaTimestamp)
            is TdApi.TextEntityTypeCustomEmoji -> MessageEntityType.CustomEmoji(entityType.customEmojiId)
            is TdApi.TextEntityTypeBlockQuote -> MessageEntityType.BlockQuote
            is TdApi.TextEntityTypeExpandableBlockQuote -> MessageEntityType.BlockQuoteExpandable
            else -> MessageEntityType.Other(entityType.javaClass.simpleName)
        }
        MessageEntity(entity.offset, entity.length, type)
    }
}
