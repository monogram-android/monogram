package org.monogram.domain.models.stories

import org.monogram.domain.models.StatisticsGraphModel

data class ActiveStoryListModel(
    val chatId: Long,
    val listType: StoryListType,
    val order: Long,
    val canBeArchived: Boolean,
    val maxReadStoryId: Int,
    val stories: List<StorySummaryModel>
)

data class StorySummaryModel(
    val storyId: Int,
    val date: Int,
    val isForCloseFriends: Boolean,
    val isLive: Boolean,
    val isRead: Boolean
)

data class StoryModel(
    val id: Int,
    val posterChatId: Long,
    val date: Int,
    val caption: String,
    val media: StoryMediaModel,
    val chosenReaction: StoryReactionModel? = null,
    val privacy: StoryPrivacySettingsModel,
    val albumIds: List<Int> = emptyList(),
    val areas: List<StoryAreaModel> = emptyList(),
    val linkUrls: List<String> = emptyList(),
    val isBeingPosted: Boolean = false,
    val isBeingEdited: Boolean = false,
    val isEdited: Boolean = false,
    val isPostedToChatPage: Boolean = false,
    val isVisibleOnlyForSelf: Boolean = false,
    val canBeDeleted: Boolean = false,
    val canBeEdited: Boolean = false,
    val canBeForwarded: Boolean = false,
    val canBeReplied: Boolean = false,
    val canSetPrivacySettings: Boolean = false,
    val canToggleIsPostedToChatPage: Boolean = false,
    val canGetStatistics: Boolean = false,
    val canGetInteractions: Boolean = false,
    val hasExpiredViewers: Boolean = false,
    val isRead: Boolean = false
)

data class StoryAreaModel(
    val position: StoryAreaPositionModel,
    val type: StoryAreaTypeModel
)

data class StoryAreaPositionModel(
    val xPercentage: Double,
    val yPercentage: Double,
    val widthPercentage: Double,
    val heightPercentage: Double,
    val rotationAngle: Double,
    val cornerRadiusPercentage: Double
)

sealed class StoryAreaTypeModel {
    data class Location(val label: String) : StoryAreaTypeModel()
    data class Venue(val title: String, val address: String? = null) : StoryAreaTypeModel()
    data class SuggestedReaction(
        val reaction: StoryReactionModel,
        val totalCount: Int,
        val isDark: Boolean,
        val isFlipped: Boolean
    ) : StoryAreaTypeModel()

    data class Message(val chatId: Long, val messageId: Long) : StoryAreaTypeModel()
    data class Link(val url: String) : StoryAreaTypeModel()
    data class Weather(
        val temperature: Double,
        val emoji: String,
        val backgroundColorArgb: Int
    ) : StoryAreaTypeModel()

    data class UpgradedGift(val giftName: String) : StoryAreaTypeModel()
}

data class StoryReactionModel(
    val emoji: String? = null,
    val customEmojiId: Long? = null,
    val isPaid: Boolean = false
) {
    val isCustomEmoji: Boolean
        get() = customEmojiId != null
}

data class StoryAvailableReactionModel(
    val reaction: StoryReactionModel,
    val needsPremium: Boolean = false
)

data class StoryAvailableReactionsModel(
    val topReactions: List<StoryAvailableReactionModel> = emptyList(),
    val recentReactions: List<StoryAvailableReactionModel> = emptyList(),
    val popularReactions: List<StoryAvailableReactionModel> = emptyList(),
    val allowCustomEmoji: Boolean = false,
    val unavailabilityReason: StoryReactionUnavailabilityReasonModel? = null
) {
    val hasAnyReactionOption: Boolean
        get() = topReactions.isNotEmpty() ||
                recentReactions.isNotEmpty() ||
                popularReactions.isNotEmpty() ||
                allowCustomEmoji
}

enum class StoryReactionUnavailabilityReasonModel {
    ANONYMOUS_ADMINISTRATOR,
    GUEST,
    RESTRICTED
}

data class StoryMediaModel(
    val type: StoryMediaType,
    val path: String?,
    val previewPath: String?,
    val minithumbnail: ByteArray? = null,
    val durationSeconds: Double? = null,
    val isAnimation: Boolean = false
)

data class StoryViewerItemModel(
    val posterChatId: Long,
    val storyId: Int
)

data class StoryComposerMediaItemModel(
    val sourcePath: String,
    val mediaType: StoryMediaType
)

data class StoryComposerDraftModel(
    val mediaItems: List<StoryComposerMediaItemModel> = emptyList(),
    val selectedMediaIndex: Int = 0,
    val caption: String = "",
    val privacy: StoryPrivacySettingsModel = StoryPrivacySettingsModel(mode = StoryPrivacyMode.EVERYONE),
    val activePeriodSeconds: Int = DEFAULT_ACTIVE_PERIOD_SECONDS,
    val protectContent: Boolean = false,
    val keepOnProfile: Boolean = true,
    val widgetLink: String? = null
) {
    val isValid: Boolean
        get() = mediaItems.isNotEmpty()

    val currentMedia: StoryComposerMediaItemModel?
        get() = mediaItems.getOrNull(selectedMediaIndex) ?: mediaItems.firstOrNull()

    val sourcePath: String
        get() = currentMedia?.sourcePath.orEmpty()

    val mediaType: StoryMediaType
        get() = currentMedia?.mediaType ?: StoryMediaType.PHOTO

    val mediaCount: Int
        get() = mediaItems.size

    companion object {
        const val DEFAULT_ACTIVE_PERIOD_SECONDS = 24 * 60 * 60
    }
}

data class StoryPrivacySettingsModel(
    val mode: StoryPrivacyMode,
    val exceptUserIds: List<Long> = emptyList(),
    val selectedUserIds: List<Long> = emptyList()
)

data class StoryStealthModeModel(
    val activeUntilDate: Int = 0,
    val cooldownUntilDate: Int = 0
) {
    val isActive: Boolean
        get() = activeUntilDate > 0

    fun isActiveAt(nowSeconds: Int): Boolean = activeUntilDate > nowSeconds

    fun isCoolingDownAt(nowSeconds: Int): Boolean = cooldownUntilDate > nowSeconds
}

data class StoryOptionsModel(
    val captionLengthMax: Int = 0,
    val linkAreaCountMax: Int = 0,
    val stealthModeCooldownPeriod: Int = 0,
    val stealthModeFuturePeriod: Int = 0,
    val stealthModePastPeriod: Int = 0,
    val suggestedReactionAreaCountMax: Int = 0,
    val viewersExpirationDelay: Int = 0
)

data class StoryStatisticsModel(
    val storyInteractionGraph: StatisticsGraphModel,
    val storyReactionGraph: StatisticsGraphModel
)

data class StoryInteractionModel(
    val actorId: Long,
    val actorType: StoryInteractionActorType,
    val interactionDate: Int,
    val type: StoryInteractionTypeModel,
    val reaction: StoryReactionModel? = null,
    val forwardChatId: Long? = null,
    val forwardMessageId: Long? = null,
    val repostStoryId: Int? = null,
    val actorTitle: String? = null,
    val actorAvatarPath: String? = null
)

data class StoryInteractionPageModel(
    val totalCount: Int,
    val totalForwardCount: Int,
    val totalReactionCount: Int,
    val interactions: List<StoryInteractionModel>,
    val nextOffset: String = ""
) {
    val canLoadMore: Boolean
        get() = nextOffset.isNotBlank()
}

enum class StoryInteractionActorType {
    USER,
    CHAT
}

enum class StoryInteractionTypeModel {
    VIEW,
    FORWARD,
    REPOST
}

sealed class StoryPostCapabilityModel {
    data class Allowed(val remainingCount: Int) : StoryPostCapabilityModel()
    data object PremiumNeeded : StoryPostCapabilityModel()
    data object BoostNeeded : StoryPostCapabilityModel()
    data object ActiveStoryLimitExceeded : StoryPostCapabilityModel()
    data class WeeklyLimitExceeded(val retryAfterSeconds: Int) : StoryPostCapabilityModel()
    data class MonthlyLimitExceeded(val retryAfterSeconds: Int) : StoryPostCapabilityModel()
    data class LiveStoryActive(val storyId: Int) : StoryPostCapabilityModel()
    data class Unknown(val message: String) : StoryPostCapabilityModel()
}

sealed class StoryPostResultModel {
    data class Success(val story: StoryModel, val oldStoryId: Int? = null) : StoryPostResultModel()
    data class Failure(
        val story: StoryModel?,
        val message: String,
        val capability: StoryPostCapabilityModel? = null
    ) : StoryPostResultModel()
}

enum class StoryListType {
    MAIN,
    ARCHIVE
}

enum class StoryMediaType {
    PHOTO,
    VIDEO
}

enum class StoryPrivacyMode {
    EVERYONE,
    CONTACTS,
    CLOSE_FRIENDS,
    SELECTED_USERS
}
