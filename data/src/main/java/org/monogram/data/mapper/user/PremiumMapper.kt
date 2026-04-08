package org.monogram.data.mapper.user

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.PremiumFeatureType
import org.monogram.domain.models.PremiumLimitType
import org.monogram.domain.models.PremiumPaymentOptionModel
import org.monogram.domain.models.PremiumSource
import org.monogram.domain.models.PremiumStateModel

fun TdApi.PremiumState.toDomain() : PremiumStateModel =
    PremiumStateModel(
        state = this.state.text,
        animations = this.animations.map { it.feature.toDomain() },
        paymentOptions = this.paymentOptions.map { option ->
            PremiumPaymentOptionModel(
                currency = option.paymentOption.currency,
                amount = option.paymentOption.amount,
                monthCount = option.paymentOption.monthCount,
                storeProductId = option.paymentOption.storeProductId,
                paymentLink = option.paymentOption.paymentLink?.toString()
            )
        }
    )

fun TdApi.PremiumFeature.toDomain() : PremiumFeatureType = when (this) {
    is TdApi.PremiumFeatureIncreasedLimits -> PremiumFeatureType.DOUBLE_LIMITS
    is TdApi.PremiumFeatureVoiceRecognition -> PremiumFeatureType.VOICE_TO_TEXT
    is TdApi.PremiumFeatureImprovedDownloadSpeed -> PremiumFeatureType.FASTER_DOWNLOAD
    is TdApi.PremiumFeatureRealTimeChatTranslation -> PremiumFeatureType.TRANSLATION
    is TdApi.PremiumFeatureCustomEmoji -> PremiumFeatureType.ANIMATED_EMOJI
    is TdApi.PremiumFeatureAdvancedChatManagement -> PremiumFeatureType.ADVANCED_CHAT_MANAGEMENT
    is TdApi.PremiumFeatureDisabledAds -> PremiumFeatureType.NO_ADS
    is TdApi.PremiumFeatureUniqueReactions -> PremiumFeatureType.INFINITE_REACTIONS
    is TdApi.PremiumFeatureProfileBadge -> PremiumFeatureType.BADGE
    is TdApi.PremiumFeatureAppIcons -> PremiumFeatureType.APP_ICONS
    is TdApi.PremiumFeatureEmojiStatus -> PremiumFeatureType.PROFILE_BADGE
    else -> PremiumFeatureType.UNKNOWN
}

fun PremiumSource.toApi(): TdApi.PremiumSource? = when (this) {
    PremiumSource.SETTINGS -> TdApi.PremiumSourceSettings()
    PremiumSource.LIMIT_EXCEEDED -> TdApi.PremiumSourceLimitExceeded()
    PremiumSource.STORY_STATUS -> TdApi.PremiumSourceStoryFeature()
    PremiumSource.LINK -> TdApi.PremiumSourceLink()
    else -> null
}

fun PremiumLimitType.toApi(): TdApi.PremiumLimitType? = when (this) {
    PremiumLimitType.SUPERGROUP_COUNT -> TdApi.PremiumLimitTypeSupergroupCount()
    PremiumLimitType.CHAT_FOLDER_COUNT -> TdApi.PremiumLimitTypeChatFolderCount()
    PremiumLimitType.PINNED_CHAT_COUNT -> TdApi.PremiumLimitTypePinnedChatCount()
    PremiumLimitType.CREATED_PUBLIC_CHAT_COUNT -> TdApi.PremiumLimitTypeCreatedPublicChatCount()
    PremiumLimitType.CHAT_FOLDER_INVITE_LINK_COUNT -> TdApi.PremiumLimitTypeChatFolderInviteLinkCount()
    PremiumLimitType.SHAREABLE_CHAT_FOLDER_COUNT -> TdApi.PremiumLimitTypeShareableChatFolderCount()
    PremiumLimitType.ACTIVE_STORY_COUNT -> TdApi.PremiumLimitTypeActiveStoryCount()
    else -> null
}