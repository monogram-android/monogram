package org.monogram.data.mtproto

import org.monogram.domain.models.stories.StoryAvailableReactionModel
import org.monogram.domain.models.stories.StoryAvailableReactionsModel
import org.monogram.domain.models.stories.StoryReactionModel
import org.monogram.mtproto.tl.generated.cloud.layer223.AvailableReaction_bcdc20ef08
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.AvailableReactions_a572c1b4d2
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetAvailableReactions

/**
 * Reads the globally available story/message reactions through
 * `messages.getAvailableReactions` (the closest TL method to the removed
 * `stories.getAvailableReactions` on the pinned cloud layer 223 schema).
 */
internal interface MtProtoStoryAvailableReactionsReader {
    suspend fun get(rowSize: Int): StoryAvailableReactionsModel?
}

internal class MtProtoStoryAvailableReactionsRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoStoryAvailableReactionsReader {
    override suspend fun get(rowSize: Int): StoryAvailableReactionsModel? = runCatching {
        require(rowSize > 0) { "MTProto reaction row size must be positive" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        // hash = 0 forces the server to return the full reaction list.
        val response = transportFactory.open(accountSlot).use { transport ->
            transport.execute(GetAvailableReactions(hash = 0))
        }
        val reactions = (response as? AvailableReactions_a572c1b4d2)
            ?.reactions
            .orEmpty()
            .filterIsInstance<AvailableReaction_bcdc20ef08>()
            .filterNot { it.inactive }
            .map { available ->
                StoryAvailableReactionModel(
                    reaction = StoryReactionModel(emoji = available.reaction),
                    needsPremium = available.premium,
                )
            }
        StoryAvailableReactionsModel(
            topReactions = reactions,
            recentReactions = emptyList(),
            popularReactions = emptyList(),
            allowCustomEmoji = false,
            unavailabilityReason = null,
        )
    }.getOrNull()

    private companion object { const val DEFAULT_ACCOUNT_SLOT = "default" }
}
