package org.monogram.data.compat

import org.drinkless.tdlib.TdApi

internal fun buildBotCommand(command: String, description: String): TdApi.BotCommand =
    TdApi.BotCommand(command, description, false)

internal fun buildRichMessageSourceMarkdown(text: String): TdApi.RichMessageSourceMarkdown =
    TdApi.RichMessageSourceMarkdown(text, emptyArray())

internal fun TdApi.SuggestedPostPrice.toLegacyToncoinCentCount(): Long? = when (this) {
    is TdApi.SuggestedPostPriceGram -> gramCentCount
    else -> null
}

internal fun TdApi.GiftResalePrice.toLegacyToncoinCentCount(): Long? = when (this) {
    is TdApi.GiftResalePriceGram -> gramCentCount
    else -> null
}

internal fun TdApi.MessageStakeDice.legacyStakeTonAmount(): Long = stakeGramAmount

internal fun TdApi.MessageStakeDice.legacyPrizeTonAmount(): Long = prizeGramAmount

internal fun TdApi.MessageGiftedTon.legacyTonAmount(): Long = gramAmount

internal fun TdApi.MessageSuggestedPostPaid.legacyTonAmount(): Long = gramAmount
