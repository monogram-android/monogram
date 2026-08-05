package org.monogram.domain.models

data class PremiumStateModel(
    val state: String,
    val animations: List<PremiumFeatureType> = emptyList(),
    val paymentOptions: List<PremiumPaymentOptionModel> = emptyList(),
    val businessAnimations: List<String> = emptyList()
)

data class PremiumFeaturesModel(
    val features: List<PremiumFeatureModel> = emptyList(),
    val limits: List<PremiumLimitModel> = emptyList(),
    val paymentLink: String? = null
)

data class PremiumFeatureModel(
    val type: PremiumFeatureType,
    val apiName: String
)

data class PremiumLimitModel(
    val type: PremiumLimitType,
    val defaultValue: Int,
    val premiumValue: Int,
    val apiName: String
)

enum class PremiumFeatureType {
    DOUBLE_LIMITS,
    INCREASED_UPLOAD_FILE_SIZE,
    UNIQUE_STICKERS,
    VOICE_TO_TEXT,
    FASTER_DOWNLOAD,
    TRANSLATION,
    ANIMATED_EMOJI,
    ADVANCED_CHAT_MANAGEMENT,
    NO_ADS,
    INFINITE_REACTIONS,
    BADGE,
    PROFILE_BADGE,
    APP_ICONS,
    ANIMATED_PROFILE_PHOTO,
    FORUM_TOPIC_ICON,
    UPGRADED_STORIES,
    CHAT_BOOST,
    ACCENT_COLOR,
    BACKGROUND_FOR_BOTH,
    SAVED_MESSAGES_TAGS,
    MESSAGE_PRIVACY,
    LAST_SEEN_TIMES,
    BUSINESS,
    MESSAGE_EFFECTS,
    CHECKLISTS,
    PAID_MESSAGES,
    PROTECT_PRIVATE_CHAT_CONTENT,
    TEXT_COMPOSITION,
    RICH_MESSAGES,
    UNKNOWN
}

enum class PremiumSource {
    SETTINGS,
    LIMIT_EXCEEDED,
    VIDEO_STATUS,
    STORY_STATUS,
    LINK
}

enum class PremiumLimitType {
    SUPERGROUP_COUNT,
    PINNED_CHAT_COUNT,
    CREATED_PUBLIC_CHAT_COUNT,
    SAVED_ANIMATION_COUNT,
    FAVORITE_STICKER_COUNT,
    CHAT_FOLDER_COUNT,
    CHAT_FOLDER_CHOSEN_CHAT_COUNT,
    PINNED_ARCHIVED_CHAT_COUNT,
    PINNED_SAVED_MESSAGES_TOPIC_COUNT,
    MESSAGE_TEXT_LENGTH,
    CAPTION_LENGTH,
    BIO_LENGTH,
    CHAT_FOLDER_INVITE_LINK_COUNT,
    SHAREABLE_CHAT_FOLDER_COUNT,
    ACTIVE_STORY_COUNT,
    WEEKLY_POSTED_STORY_COUNT,
    MONTHLY_POSTED_STORY_COUNT,
    STORY_CAPTION_LENGTH,
    STORY_SUGGESTED_REACTION_AREA_COUNT,
    SIMILAR_CHAT_COUNT,
    OWNED_BOT_COUNT,
    CUSTOM_TEXT_COMPOSITION_STYLE_COUNT,
    UNKNOWN
}

data class PremiumPaymentOptionModel(
    val currency: String,
    val amount: Long,
    val discountPercentage: Int,
    val monthCount: Int,
    val storeProductId: String,
    val paymentLink: String?,
    val isCurrent: Boolean = false,
    val isUpgrade: Boolean = false,
    val lastTransactionId: String? = null
)
