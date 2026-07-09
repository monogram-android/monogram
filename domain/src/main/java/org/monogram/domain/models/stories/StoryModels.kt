package org.monogram.domain.models.stories

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
    val privacy: StoryPrivacySettingsModel,
    val albumIds: List<Int> = emptyList(),
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

data class StoryComposerDraftModel(
    val sourcePath: String = "",
    val mediaType: StoryMediaType = StoryMediaType.PHOTO,
    val caption: String = "",
    val privacy: StoryPrivacySettingsModel = StoryPrivacySettingsModel(mode = StoryPrivacyMode.EVERYONE),
    val activePeriodSeconds: Int = DEFAULT_ACTIVE_PERIOD_SECONDS,
    val protectContent: Boolean = false,
    val keepOnProfile: Boolean = true,
    val widgetLink: String? = null
) {
    val isValid: Boolean
        get() = sourcePath.isNotBlank()

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
