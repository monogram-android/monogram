package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.stories.ActiveStoryListModel
import org.monogram.domain.models.stories.StoryAreaModel
import org.monogram.domain.models.stories.StoryAreaPositionModel
import org.monogram.domain.models.stories.StoryAreaTypeModel
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.models.stories.StoryMediaModel
import org.monogram.domain.models.stories.StoryMediaType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.domain.models.stories.StoryPostCapabilityModel
import org.monogram.domain.models.stories.StoryPrivacyMode
import org.monogram.domain.models.stories.StoryPrivacySettingsModel
import org.monogram.domain.models.stories.StoryReactionModel
import org.monogram.domain.models.stories.StoryStealthModeModel
import org.monogram.domain.models.stories.StorySummaryModel

object StoryMapper {
    fun mapActiveStories(activeStories: TdApi.ChatActiveStories): ActiveStoryListModel {
        val maxReadStoryId = activeStories.maxReadStoryId
        return ActiveStoryListModel(
            chatId = activeStories.chatId,
            listType = activeStories.list.toDomainStoryListType() ?: StoryListType.MAIN,
            order = activeStories.order,
            canBeArchived = activeStories.canBeArchived,
            maxReadStoryId = maxReadStoryId,
            stories = activeStories.stories.orEmpty()
                .map { summary ->
                    StorySummaryModel(
                        storyId = summary.storyId,
                        date = summary.date,
                        isForCloseFriends = summary.isForCloseFriends,
                        isLive = summary.isLive,
                        isRead = summary.storyId <= maxReadStoryId
                    )
                }
        )
    }

    fun mapStory(
        story: TdApi.Story,
        activeStories: ActiveStoryListModel? = null,
        mediaOverride: StoryMediaModel? = null
    ): StoryModel {
        return StoryModel(
            id = story.id,
            posterChatId = story.posterChatId,
            date = story.date,
            caption = story.caption?.text.orEmpty(),
            media = mediaOverride ?: story.content.toDomainMedia(),
            privacy = story.privacySettings.toDomainPrivacy(),
            albumIds = story.albumIds?.toList().orEmpty(),
            areas = story.areas.orEmpty().mapNotNull(::mapStoryArea),
            linkUrls = story.areas.orEmpty()
                .mapNotNull { area -> (area.type as? TdApi.StoryAreaTypeLink)?.url },
            isBeingPosted = story.isBeingPosted,
            isBeingEdited = story.isBeingEdited,
            isEdited = story.isEdited,
            isPostedToChatPage = story.isPostedToChatPage,
            isVisibleOnlyForSelf = story.isVisibleOnlyForSelf,
            canBeDeleted = story.canBeDeleted,
            canBeEdited = story.canBeEdited,
            canBeForwarded = story.canBeForwarded,
            canBeReplied = story.canBeReplied,
            canSetPrivacySettings = story.canSetPrivacySettings,
            canToggleIsPostedToChatPage = story.canToggleIsPostedToChatPage,
            canGetStatistics = story.canGetStatistics,
            canGetInteractions = story.canGetInteractions,
            hasExpiredViewers = story.hasExpiredViewers,
            isRead = activeStories?.stories?.firstOrNull { it.storyId == story.id }?.isRead
                ?: (story.id <= (activeStories?.maxReadStoryId ?: 0))
        )
    }

    fun mapStealthMode(update: TdApi.UpdateStoryStealthMode): StoryStealthModeModel {
        return StoryStealthModeModel(
            activeUntilDate = update.activeUntilDate,
            cooldownUntilDate = update.cooldownUntilDate
        )
    }

    fun mapPostCapability(result: TdApi.CanPostStoryResult?): StoryPostCapabilityModel {
        return when (result) {
            is TdApi.CanPostStoryResultOk -> StoryPostCapabilityModel.Allowed(result.storyCount)
            is TdApi.CanPostStoryResultPremiumNeeded -> StoryPostCapabilityModel.PremiumNeeded
            is TdApi.CanPostStoryResultBoostNeeded -> StoryPostCapabilityModel.BoostNeeded
            is TdApi.CanPostStoryResultActiveStoryLimitExceeded -> StoryPostCapabilityModel.ActiveStoryLimitExceeded
            is TdApi.CanPostStoryResultWeeklyLimitExceeded -> StoryPostCapabilityModel.WeeklyLimitExceeded(
                result.retryAfter
            )

            is TdApi.CanPostStoryResultMonthlyLimitExceeded -> StoryPostCapabilityModel.MonthlyLimitExceeded(
                result.retryAfter
            )

            is TdApi.CanPostStoryResultLiveStoryIsActive -> StoryPostCapabilityModel.LiveStoryActive(
                result.storyId
            )

            else -> StoryPostCapabilityModel.Unknown("Unknown post capability")
        }
    }

    fun TdApi.StoryList?.toDomainStoryListType(): StoryListType? {
        return when (this) {
            is TdApi.StoryListMain -> StoryListType.MAIN
            is TdApi.StoryListArchive -> StoryListType.ARCHIVE
            else -> null
        }
    }

    fun StoryListType.toTdStoryList(): TdApi.StoryList {
        return when (this) {
            StoryListType.MAIN -> TdApi.StoryListMain()
            StoryListType.ARCHIVE -> TdApi.StoryListArchive()
        }
    }

    fun StoryPrivacySettingsModel.toTdPrivacy(): TdApi.StoryPrivacySettings {
        return when (mode) {
            StoryPrivacyMode.EVERYONE -> TdApi.StoryPrivacySettingsEveryone(exceptUserIds.toLongArray())
            StoryPrivacyMode.CONTACTS -> TdApi.StoryPrivacySettingsContacts(exceptUserIds.toLongArray())
            StoryPrivacyMode.CLOSE_FRIENDS -> TdApi.StoryPrivacySettingsCloseFriends()
            StoryPrivacyMode.SELECTED_USERS -> TdApi.StoryPrivacySettingsSelectedUsers(
                selectedUserIds.toLongArray()
            )
        }
    }

    private fun TdApi.StoryPrivacySettings.toDomainPrivacy(): StoryPrivacySettingsModel {
        return when (this) {
            is TdApi.StoryPrivacySettingsEveryone -> StoryPrivacySettingsModel(
                mode = StoryPrivacyMode.EVERYONE,
                exceptUserIds = exceptUserIds?.toList().orEmpty()
            )

            is TdApi.StoryPrivacySettingsContacts -> StoryPrivacySettingsModel(
                mode = StoryPrivacyMode.CONTACTS,
                exceptUserIds = exceptUserIds?.toList().orEmpty()
            )

            is TdApi.StoryPrivacySettingsCloseFriends -> StoryPrivacySettingsModel(
                mode = StoryPrivacyMode.CLOSE_FRIENDS
            )

            is TdApi.StoryPrivacySettingsSelectedUsers -> StoryPrivacySettingsModel(
                mode = StoryPrivacyMode.SELECTED_USERS,
                selectedUserIds = userIds?.toList().orEmpty()
            )

            else -> StoryPrivacySettingsModel(mode = StoryPrivacyMode.EVERYONE)
        }
    }

    private fun mapStoryArea(area: TdApi.StoryArea?): StoryAreaModel? {
        area ?: return null
        val position = area.position ?: return null
        val type = mapStoryAreaType(area.type) ?: return null
        return StoryAreaModel(
            position = StoryAreaPositionModel(
                xPercentage = position.xPercentage,
                yPercentage = position.yPercentage,
                widthPercentage = position.widthPercentage,
                heightPercentage = position.heightPercentage,
                rotationAngle = position.rotationAngle,
                cornerRadiusPercentage = position.cornerRadiusPercentage
            ),
            type = type
        )
    }

    private fun mapStoryAreaType(type: TdApi.StoryAreaType?): StoryAreaTypeModel? {
        return when (type) {
            is TdApi.StoryAreaTypeLocation -> StoryAreaTypeModel.Location(
                label = listOfNotNull(
                    type.address?.street?.takeIf { it.isNotBlank() },
                    type.address?.city?.takeIf { it.isNotBlank() },
                    type.address?.state?.takeIf { it.isNotBlank() }
                ).firstOrNull().orEmpty().ifBlank { "Location" }
            )

            is TdApi.StoryAreaTypeVenue -> StoryAreaTypeModel.Venue(
                title = type.venue?.title.orEmpty().ifBlank { "Venue" },
                address = type.venue?.address?.takeIf { it.isNotBlank() }
            )

            is TdApi.StoryAreaTypeSuggestedReaction -> StoryAreaTypeModel.SuggestedReaction(
                reaction = type.reactionType.toDomainReaction(),
                totalCount = type.totalCount,
                isDark = type.isDark,
                isFlipped = type.isFlipped
            )

            is TdApi.StoryAreaTypeMessage -> StoryAreaTypeModel.Message(
                chatId = type.chatId,
                messageId = type.messageId
            )

            is TdApi.StoryAreaTypeLink -> StoryAreaTypeModel.Link(type.url)
            is TdApi.StoryAreaTypeWeather -> StoryAreaTypeModel.Weather(
                temperature = type.temperature,
                emoji = type.emoji,
                backgroundColorArgb = type.backgroundColor
            )

            is TdApi.StoryAreaTypeUpgradedGift -> StoryAreaTypeModel.UpgradedGift(type.giftName)
            else -> null
        }
    }

    private fun TdApi.ReactionType?.toDomainReaction(): StoryReactionModel {
        return when (this) {
            is TdApi.ReactionTypeEmoji -> StoryReactionModel(emoji = emoji)
            is TdApi.ReactionTypeCustomEmoji -> StoryReactionModel(customEmojiId = customEmojiId)
            is TdApi.ReactionTypePaid -> StoryReactionModel(isPaid = true)
            else -> StoryReactionModel()
        }
    }

    private fun TdApi.StoryContent.toDomainMedia(): StoryMediaModel {
        return when (this) {
            is TdApi.StoryContentPhoto -> {
                val size = photo.sizes?.maxByOrNull { it.width * it.height }
                StoryMediaModel(
                    type = StoryMediaType.PHOTO,
                    path = size?.photo?.local?.path?.ifBlank { null },
                    previewPath = photo.sizes?.firstOrNull()?.photo?.local?.path?.ifBlank { null },
                    minithumbnail = photo.minithumbnail?.data?.takeIf { it.isNotEmpty() }
                )
            }

            is TdApi.StoryContentVideo -> {
                StoryMediaModel(
                    type = StoryMediaType.VIDEO,
                    path = video.video.local.path.ifBlank { null },
                    previewPath = video.thumbnail?.file?.local?.path?.ifBlank { null },
                    minithumbnail = video.minithumbnail?.data?.takeIf { it.isNotEmpty() },
                    durationSeconds = video.duration,
                    isAnimation = video.isAnimation
                )
            }

            else -> StoryMediaModel(
                type = StoryMediaType.PHOTO,
                path = null,
                previewPath = null,
                minithumbnail = null
            )
        }
    }
}
