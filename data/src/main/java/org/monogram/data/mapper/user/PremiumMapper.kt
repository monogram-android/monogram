package org.monogram.data.mapper.user

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.PremiumFeatureModel
import org.monogram.domain.models.PremiumFeatureType
import org.monogram.domain.models.PremiumFeaturesModel
import org.monogram.domain.models.PremiumLimitModel
import org.monogram.domain.models.PremiumLimitType
import org.monogram.domain.models.PremiumPaymentOptionModel
import org.monogram.domain.models.PremiumSource
import org.monogram.domain.models.PremiumStateModel

fun TdApi.PremiumState.toDomain() : PremiumStateModel =
    PremiumStateModel(
        state = this.state.text,
        animations = this.animations.map { it.feature.toDomain().type },
        paymentOptions = this.paymentOptions.map { option ->
            PremiumPaymentOptionModel(
                currency = option.paymentOption.currency,
                amount = option.paymentOption.amount,
                discountPercentage = option.paymentOption.discountPercentage,
                monthCount = option.paymentOption.monthCount,
                storeProductId = option.paymentOption.storeProductId,
                paymentLink = option.paymentOption.paymentLink?.toString(),
                isCurrent = option.isCurrent,
                isUpgrade = option.isUpgrade,
                lastTransactionId = option.lastTransactionId.takeIf { it.isNotBlank() }
            )
        },
        businessAnimations = this.businessAnimations.map { it.feature.javaClass.simpleName }
    )

fun TdApi.PremiumFeatures.toDomain(): PremiumFeaturesModel =
    PremiumFeaturesModel(
        features = features.map { it.toDomain() },
        limits = limits.map { it.toDomain() },
        paymentLink = paymentLink?.toString()
    )

fun TdApi.PremiumFeature.toDomain(): PremiumFeatureModel =
    PremiumFeatureModel(
        type = when (getConstructor()) {
            TdApi.PremiumFeatureIncreasedLimits.CONSTRUCTOR -> PremiumFeatureType.DOUBLE_LIMITS
            TdApi.PremiumFeatureIncreasedUploadFileSize.CONSTRUCTOR -> PremiumFeatureType.INCREASED_UPLOAD_FILE_SIZE
            TdApi.PremiumFeatureImprovedDownloadSpeed.CONSTRUCTOR -> PremiumFeatureType.FASTER_DOWNLOAD
            TdApi.PremiumFeatureVoiceRecognition.CONSTRUCTOR -> PremiumFeatureType.VOICE_TO_TEXT
            TdApi.PremiumFeatureDisabledAds.CONSTRUCTOR -> PremiumFeatureType.NO_ADS
            TdApi.PremiumFeatureUniqueReactions.CONSTRUCTOR -> PremiumFeatureType.INFINITE_REACTIONS
            TdApi.PremiumFeatureUniqueStickers.CONSTRUCTOR -> PremiumFeatureType.UNIQUE_STICKERS
            TdApi.PremiumFeatureCustomEmoji.CONSTRUCTOR -> PremiumFeatureType.ANIMATED_EMOJI
            TdApi.PremiumFeatureAdvancedChatManagement.CONSTRUCTOR -> PremiumFeatureType.ADVANCED_CHAT_MANAGEMENT
            TdApi.PremiumFeatureProfileBadge.CONSTRUCTOR -> PremiumFeatureType.BADGE
            TdApi.PremiumFeatureEmojiStatus.CONSTRUCTOR -> PremiumFeatureType.PROFILE_BADGE
            TdApi.PremiumFeatureAnimatedProfilePhoto.CONSTRUCTOR -> PremiumFeatureType.ANIMATED_PROFILE_PHOTO
            TdApi.PremiumFeatureForumTopicIcon.CONSTRUCTOR -> PremiumFeatureType.FORUM_TOPIC_ICON
            TdApi.PremiumFeatureAppIcons.CONSTRUCTOR -> PremiumFeatureType.APP_ICONS
            TdApi.PremiumFeatureRealTimeChatTranslation.CONSTRUCTOR -> PremiumFeatureType.TRANSLATION
            TdApi.PremiumFeatureUpgradedStories.CONSTRUCTOR -> PremiumFeatureType.UPGRADED_STORIES
            TdApi.PremiumFeatureChatBoost.CONSTRUCTOR -> PremiumFeatureType.CHAT_BOOST
            TdApi.PremiumFeatureAccentColor.CONSTRUCTOR -> PremiumFeatureType.ACCENT_COLOR
            TdApi.PremiumFeatureBackgroundForBoth.CONSTRUCTOR -> PremiumFeatureType.BACKGROUND_FOR_BOTH
            TdApi.PremiumFeatureSavedMessagesTags.CONSTRUCTOR -> PremiumFeatureType.SAVED_MESSAGES_TAGS
            TdApi.PremiumFeatureMessagePrivacy.CONSTRUCTOR -> PremiumFeatureType.MESSAGE_PRIVACY
            TdApi.PremiumFeatureLastSeenTimes.CONSTRUCTOR -> PremiumFeatureType.LAST_SEEN_TIMES
            TdApi.PremiumFeatureBusiness.CONSTRUCTOR -> PremiumFeatureType.BUSINESS
            TdApi.PremiumFeatureMessageEffects.CONSTRUCTOR -> PremiumFeatureType.MESSAGE_EFFECTS
            TdApi.PremiumFeatureChecklists.CONSTRUCTOR -> PremiumFeatureType.CHECKLISTS
            TdApi.PremiumFeaturePaidMessages.CONSTRUCTOR -> PremiumFeatureType.PAID_MESSAGES
            TdApi.PremiumFeatureProtectPrivateChatContent.CONSTRUCTOR -> PremiumFeatureType.PROTECT_PRIVATE_CHAT_CONTENT
            TdApi.PremiumFeatureTextComposition.CONSTRUCTOR -> PremiumFeatureType.TEXT_COMPOSITION
            TdApi.PremiumFeatureRichMessages.CONSTRUCTOR -> PremiumFeatureType.RICH_MESSAGES
            else -> PremiumFeatureType.UNKNOWN
        },
        apiName = javaClass.simpleName
    )

fun TdApi.PremiumLimit.toDomain(): PremiumLimitModel =
    PremiumLimitModel(
        type = when (type.getConstructor()) {
            TdApi.PremiumLimitTypeSupergroupCount.CONSTRUCTOR -> PremiumLimitType.SUPERGROUP_COUNT
            TdApi.PremiumLimitTypePinnedChatCount.CONSTRUCTOR -> PremiumLimitType.PINNED_CHAT_COUNT
            TdApi.PremiumLimitTypeCreatedPublicChatCount.CONSTRUCTOR -> PremiumLimitType.CREATED_PUBLIC_CHAT_COUNT
            TdApi.PremiumLimitTypeSavedAnimationCount.CONSTRUCTOR -> PremiumLimitType.SAVED_ANIMATION_COUNT
            TdApi.PremiumLimitTypeFavoriteStickerCount.CONSTRUCTOR -> PremiumLimitType.FAVORITE_STICKER_COUNT
            TdApi.PremiumLimitTypeChatFolderCount.CONSTRUCTOR -> PremiumLimitType.CHAT_FOLDER_COUNT
            TdApi.PremiumLimitTypeChatFolderChosenChatCount.CONSTRUCTOR -> PremiumLimitType.CHAT_FOLDER_CHOSEN_CHAT_COUNT
            TdApi.PremiumLimitTypePinnedArchivedChatCount.CONSTRUCTOR -> PremiumLimitType.PINNED_ARCHIVED_CHAT_COUNT
            TdApi.PremiumLimitTypePinnedSavedMessagesTopicCount.CONSTRUCTOR -> PremiumLimitType.PINNED_SAVED_MESSAGES_TOPIC_COUNT
            TdApi.PremiumLimitTypeMessageTextLength.CONSTRUCTOR -> PremiumLimitType.MESSAGE_TEXT_LENGTH
            TdApi.PremiumLimitTypeCaptionLength.CONSTRUCTOR -> PremiumLimitType.CAPTION_LENGTH
            TdApi.PremiumLimitTypeBioLength.CONSTRUCTOR -> PremiumLimitType.BIO_LENGTH
            TdApi.PremiumLimitTypeChatFolderInviteLinkCount.CONSTRUCTOR -> PremiumLimitType.CHAT_FOLDER_INVITE_LINK_COUNT
            TdApi.PremiumLimitTypeShareableChatFolderCount.CONSTRUCTOR -> PremiumLimitType.SHAREABLE_CHAT_FOLDER_COUNT
            TdApi.PremiumLimitTypeActiveStoryCount.CONSTRUCTOR -> PremiumLimitType.ACTIVE_STORY_COUNT
            TdApi.PremiumLimitTypeWeeklyPostedStoryCount.CONSTRUCTOR -> PremiumLimitType.WEEKLY_POSTED_STORY_COUNT
            TdApi.PremiumLimitTypeMonthlyPostedStoryCount.CONSTRUCTOR -> PremiumLimitType.MONTHLY_POSTED_STORY_COUNT
            TdApi.PremiumLimitTypeStoryCaptionLength.CONSTRUCTOR -> PremiumLimitType.STORY_CAPTION_LENGTH
            TdApi.PremiumLimitTypeStorySuggestedReactionAreaCount.CONSTRUCTOR -> PremiumLimitType.STORY_SUGGESTED_REACTION_AREA_COUNT
            TdApi.PremiumLimitTypeSimilarChatCount.CONSTRUCTOR -> PremiumLimitType.SIMILAR_CHAT_COUNT
            TdApi.PremiumLimitTypeOwnedBotCount.CONSTRUCTOR -> PremiumLimitType.OWNED_BOT_COUNT
            TdApi.PremiumLimitTypeCustomTextCompositionStyleCount.CONSTRUCTOR -> PremiumLimitType.CUSTOM_TEXT_COMPOSITION_STYLE_COUNT
            else -> PremiumLimitType.UNKNOWN
        },
        defaultValue = defaultValue,
        premiumValue = premiumValue,
        apiName = type.javaClass.simpleName
    )

fun PremiumSource.toApi(): TdApi.PremiumSource? = when (this) {
    PremiumSource.SETTINGS -> TdApi.PremiumSourceSettings()
    PremiumSource.LIMIT_EXCEEDED -> TdApi.PremiumSourceLimitExceeded()
    PremiumSource.STORY_STATUS -> TdApi.PremiumSourceStoryFeature()
    PremiumSource.LINK -> TdApi.PremiumSourceLink()
    else -> null
}
