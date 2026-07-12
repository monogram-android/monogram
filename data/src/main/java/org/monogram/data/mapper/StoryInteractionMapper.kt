package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.data.mapper.user.toDomain
import org.monogram.domain.models.stories.StoryInteractionActorType
import org.monogram.domain.models.stories.StoryInteractionModel
import org.monogram.domain.models.stories.StoryInteractionPageModel
import org.monogram.domain.models.stories.StoryInteractionTypeModel
import org.monogram.domain.models.stories.StoryStatisticsModel

object StoryInteractionMapper {
    fun mapStoryStatistics(statistics: TdApi.StoryStatistics): StoryStatisticsModel {
        return StoryStatisticsModel(
            storyInteractionGraph = statistics.storyInteractionGraph.toDomain(),
            storyReactionGraph = statistics.storyReactionGraph.toDomain()
        )
    }

    fun mapStoryInteractions(interactions: TdApi.StoryInteractions): StoryInteractionPageModel {
        return StoryInteractionPageModel(
            totalCount = interactions.totalCount,
            totalForwardCount = interactions.totalForwardCount,
            totalReactionCount = interactions.totalReactionCount,
            interactions = interactions.interactions.orEmpty().map(::mapStoryInteraction),
            nextOffset = interactions.nextOffset.orEmpty()
        )
    }

    private fun mapStoryInteraction(interaction: TdApi.StoryInteraction): StoryInteractionModel {
        val actorId = when (val actor = interaction.actorId) {
            is TdApi.MessageSenderUser -> actor.userId
            is TdApi.MessageSenderChat -> actor.chatId
            else -> 0L
        }
        val actorType = when (interaction.actorId) {
            is TdApi.MessageSenderChat -> StoryInteractionActorType.CHAT
            else -> StoryInteractionActorType.USER
        }

        return when (val type = interaction.type) {
            is TdApi.StoryInteractionTypeView -> StoryInteractionModel(
                actorId = actorId,
                actorType = actorType,
                interactionDate = interaction.interactionDate,
                type = StoryInteractionTypeModel.VIEW,
                reaction = type.chosenReactionType.toReactionLabel()
            )

            is TdApi.StoryInteractionTypeForward -> StoryInteractionModel(
                actorId = actorId,
                actorType = actorType,
                interactionDate = interaction.interactionDate,
                type = StoryInteractionTypeModel.FORWARD,
                forwardChatId = type.message.chatId.takeIf { it != 0L },
                forwardMessageId = type.message.id.takeIf { it != 0L }
            )

            is TdApi.StoryInteractionTypeRepost -> StoryInteractionModel(
                actorId = actorId,
                actorType = actorType,
                interactionDate = interaction.interactionDate,
                type = StoryInteractionTypeModel.REPOST,
                repostStoryId = type.story.id.takeIf { it != 0 }
            )

            else -> StoryInteractionModel(
                actorId = actorId,
                actorType = actorType,
                interactionDate = interaction.interactionDate,
                type = StoryInteractionTypeModel.VIEW
            )
        }
    }

    private fun TdApi.ReactionType?.toReactionLabel(): String? {
        return when (this) {
            is TdApi.ReactionTypeEmoji -> emoji
            is TdApi.ReactionTypeCustomEmoji -> "custom:$customEmojiId"
            is TdApi.ReactionTypePaid -> "paid"
            else -> null
        }
    }
}
