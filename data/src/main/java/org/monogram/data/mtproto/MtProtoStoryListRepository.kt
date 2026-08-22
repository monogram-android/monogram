package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.models.stories.StoryListType
import org.monogram.mtproto.codec.CloudTlObjectCodec
import org.monogram.domain.models.stories.StoryPostCapabilityModel
import org.monogram.domain.models.stories.StoryReactionModel
import org.monogram.domain.models.stories.StoryInteractionActorType
import org.monogram.domain.models.stories.StoryInteractionModel
import org.monogram.domain.models.stories.StoryInteractionPageModel
import org.monogram.domain.models.stories.StoryInteractionTypeModel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.ReactionCustomEmoji
import org.monogram.mtproto.tl.generated.cloud.layer223.ReactionEmoji
import org.monogram.mtproto.tl.generated.cloud.layer223.ReactionEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.ReactionPaid
import org.monogram.mtproto.tl.generated.cloud.layer223.StoryItemDeleted
import org.monogram.mtproto.tl.generated.cloud.layer223.Peer
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.StoryReaction_d79d7ffe4e
import org.monogram.mtproto.tl.generated.cloud.layer223.StoryReactionPublicRepost
import org.monogram.mtproto.tl.generated.cloud.layer223.StoryView_db9a819eaf
import org.monogram.mtproto.tl.generated.cloud.layer223.StoryViewPublicRepost
import org.monogram.mtproto.tl.generated.cloud.layer223.Reaction
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.CanSendStory
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.DeleteStories
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.IncrementStoryViews
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.ActivateStealthMode
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.CanSendStoryCount_11d73fe4aa
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.ReadStories
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.SendReaction
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.TogglePeerStoriesHidden
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.TogglePinned
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.GetStoryViewsList
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.GetStoryReactionsList
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.StoryViewsList_3efe2a40ae
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.StoryReactionsList_423ac03963

internal interface MtProtoStoryListRepository {
    suspend fun setActiveStoriesList(chatId: Long, listType: StoryListType?): Boolean
    suspend fun markRead(chatId: Long, storyId: Int) {
        throw UnsupportedOperationException("MTProto story read marking is not configured")
    }
    suspend fun setReaction(chatId: Long, storyId: Int, reaction: StoryReactionModel): Boolean {
        throw UnsupportedOperationException("MTProto story reactions are not configured")
    }
    suspend fun canSend(chatId: Long): StoryPostCapabilityModel {
        throw UnsupportedOperationException("MTProto story send capability is not configured")
    }
    suspend fun delete(chatId: Long, storyId: Int): Boolean {
        throw UnsupportedOperationException("MTProto story deletion is not configured")
    }
    suspend fun close(chatId: Long, storyId: Int) {
        throw UnsupportedOperationException("MTProto story close acknowledgment is not configured")
    }
    suspend fun activateStealthMode(): Boolean {
        throw UnsupportedOperationException("MTProto story stealth mode is not configured")
    }
    suspend fun setPostedToChatPage(chatId: Long, storyId: Int, isPostedToChatPage: Boolean): Boolean {
        throw UnsupportedOperationException("MTProto story chat-page pinning is not configured")
    }
    suspend fun getInteractions(
        chatId: Long,
        storyId: Int,
        offset: String,
        limit: Int,
        query: String,
        onlyContacts: Boolean,
        preferForwards: Boolean,
        preferWithReaction: Boolean,
    ): StoryInteractionPageModel? {
        throw UnsupportedOperationException("MTProto story interactions are not configured")
    }
}

internal class MtProtoStoryListRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val stories: MtProtoStoryProjectionStore = NoOpMtProtoStoryProjectionStore,
    private val cloudObjectStager: MtProtoCloudObjectStager = NoOpMtProtoCloudObjectStager,
    private val storyResultStager: MtProtoStoryResultStager = MtProtoStoryResultStager(NoOpMtProtoStoryProjectionStore),
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoStoryListRepository {
    override suspend fun setActiveStoriesList(chatId: Long, listType: StoryListType?): Boolean {
        val hidden = when (listType) {
            StoryListType.MAIN -> false
            StoryListType.ARCHIVE -> true
            null -> throw UnsupportedOperationException("MTProto cannot remove a peer from all active story lists")
        }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        transportFactory.open(accountSlot).use { transport ->
            return transport.execute(TogglePeerStoriesHidden(resolvePeer(scope, chatId), hidden))
        }
    }

    override suspend fun markRead(chatId: Long, storyId: Int) {
        require(storyId > 0) { "MTProto story ID must be positive" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = resolvePeerDescriptor(scope, chatId)
        transportFactory.open(accountSlot).use { transport ->
            transport.execute(ReadStories(resolvePeer(scope, chatId), storyId))
        }
        stories.updateMaxReadStoryId(scope, peerType(peer.type), peer.id, storyId)
    }

    override suspend fun canSend(chatId: Long): StoryPostCapabilityModel {
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val result = transportFactory.open(accountSlot).use { transport ->
            transport.execute(CanSendStory(resolvePeer(scope, chatId)))
        } as? CanSendStoryCount_11d73fe4aa ?: error("Unsupported MTProto story send capability response")
        return if (result.countRemains > 0) {
            StoryPostCapabilityModel.Allowed(result.countRemains)
        } else {
            StoryPostCapabilityModel.ActiveStoryLimitExceeded
        }
    }

    override suspend fun activateStealthMode(): Boolean {
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        transportFactory.open(accountSlot).use { transport ->
            storyResultStager.stageLive(scope, transport.execute(ActivateStealthMode(past = true, future = true)))
        }
        return true
    }

    override suspend fun close(chatId: Long, storyId: Int) {
        require(storyId > 0) { "MTProto story ID must be positive" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        transportFactory.open(accountSlot).use { transport ->
            check(transport.execute(IncrementStoryViews(resolvePeer(scope, chatId), listOf(storyId)))) {
                "MTProto rejected story view acknowledgment"
            }
        }
    }

    override suspend fun delete(chatId: Long, storyId: Int): Boolean {
        require(storyId > 0) { "MTProto story ID must be positive" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = resolvePeerDescriptor(scope, chatId)
        val deleted = transportFactory.open(accountSlot).use { transport ->
            transport.execute(DeleteStories(resolvePeer(scope, chatId), listOf(storyId)))
        }
        if (storyId !in deleted) return false
        stories.upsert(
            scope,
            MtProtoStoryPayload(
                key = MtProtoStoryKey(peerType(peer.type), peer.id, storyId),
                payload = CloudTlObjectCodec.encode(StoryItemDeleted(storyId)),
                isDeleted = true,
            ),
        )
        return true
    }

    override suspend fun getInteractions(
        chatId: Long,
        storyId: Int,
        offset: String,
        limit: Int,
        query: String,
        onlyContacts: Boolean,
        preferForwards: Boolean,
        preferWithReaction: Boolean,
    ): StoryInteractionPageModel? {
        require(storyId > 0) { "MTProto story ID must be positive" }
        require(limit in 1..100) { "MTProto story interaction limit is invalid" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = resolvePeerDescriptor(scope, chatId)
        return transportFactory.open(accountSlot).use { transport ->
            when (peer.type) {
                DialogPeerType.CHANNEL -> {
                    val result = transport.execute(
                        GetStoryReactionsList(
                            forwardsFirst = preferForwards,
                            peer = resolvePeer(scope, chatId),
                            id = storyId,
                            reaction = null,
                            offset = offset.takeIf(String::isNotBlank),
                            limit = limit,
                        ),
                    ) as? StoryReactionsList_423ac03963 ?: return@use null
                    users.upsert(scope, result.users)
                    chats.upsert(scope, result.chats)
                    StoryInteractionPageModel(
                        totalCount = result.count,
                        totalForwardCount = 0,
                        totalReactionCount = result.count,
                        interactions = result.reactions.mapNotNull { reaction ->
                            when (reaction) {
                                is StoryReaction_d79d7ffe4e -> reaction.toInteraction()
                                is StoryReactionPublicRepost -> reaction.toInteraction()
                                else -> null
                            }
                        },
                        nextOffset = result.nextOffset.orEmpty(),
                    )
                }
                else -> {
                    val result = transport.execute(
                        GetStoryViewsList(
                            justContacts = onlyContacts,
                            reactionsFirst = preferWithReaction,
                            forwardsFirst = preferForwards,
                            peer = resolvePeer(scope, chatId),
                            q = query.takeIf(String::isNotBlank),
                            id = storyId,
                            offset = offset,
                            limit = limit,
                        ),
                    ) as? StoryViewsList_3efe2a40ae ?: return@use null
                    users.upsert(scope, result.users)
                    chats.upsert(scope, result.chats)
                    StoryInteractionPageModel(
                        totalCount = result.count,
                        totalForwardCount = result.forwardsCount,
                        totalReactionCount = result.reactionsCount,
                        interactions = result.views.mapNotNull { view ->
                            when (view) {
                                is StoryView_db9a819eaf -> view.toInteraction()
                                is StoryViewPublicRepost -> view.toInteraction()
                                else -> null
                            }
                        },
                        nextOffset = result.nextOffset.orEmpty(),
                    )
                }
            }
        }
    }

    override suspend fun setPostedToChatPage(
        chatId: Long,
        storyId: Int,
        isPostedToChatPage: Boolean,
    ): Boolean {
        require(storyId > 0) { "MTProto story ID must be positive" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val pinnedIds = transportFactory.open(accountSlot).use { transport ->
            transport.execute(TogglePinned(resolvePeer(scope, chatId), listOf(storyId), isPostedToChatPage))
        }
        return storyId in pinnedIds
    }

    override suspend fun setReaction(chatId: Long, storyId: Int, reaction: StoryReactionModel): Boolean {
        require(storyId > 0) { "MTProto story ID must be positive" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val request = SendReaction(
            addToRecent = reaction.emoji != null || reaction.customEmojiId != null,
            peer = resolvePeer(scope, chatId),
            storyId = storyId,
            reaction = reaction.toMtProtoReaction(),
        )
        transportFactory.open(accountSlot).use { transport ->
            cloudObjectStager.stageLive(scope, transport.execute(request))
        }
        return true
    }

    private fun StoryReaction_d79d7ffe4e.toInteraction(): StoryInteractionModel? = peerId.toInteractionActor()?.let { actor ->
        StoryInteractionModel(
            actorId = actor.id,
            actorType = actor.type,
            interactionDate = date,
            type = StoryInteractionTypeModel.VIEW,
            reaction = reaction.toStoryReaction(),
        )
    }

    private fun StoryReactionPublicRepost.toInteraction(): StoryInteractionModel? = peerId.toInteractionActor()?.let { actor ->
        StoryInteractionModel(
            actorId = actor.id,
            actorType = actor.type,
            interactionDate = 0,
            type = StoryInteractionTypeModel.REPOST,
            repostStoryId = story.id(),
        )
    }

    private fun StoryView_db9a819eaf.toInteraction() = StoryInteractionModel(
        actorId = userId,
        actorType = StoryInteractionActorType.USER,
        interactionDate = date,
        type = StoryInteractionTypeModel.VIEW,
        reaction = reaction?.toStoryReaction(),
    )

    private fun StoryViewPublicRepost.toInteraction(): StoryInteractionModel? = peerId.toInteractionActor()?.let { actor ->
        StoryInteractionModel(
            actorId = actor.id,
            actorType = actor.type,
            interactionDate = 0,
            type = StoryInteractionTypeModel.REPOST,
            repostStoryId = story.id(),
        )
    }

    private fun Peer.toInteractionActor(): InteractionActor? = when (this) {
        is PeerUser -> InteractionActor(id = userId, type = StoryInteractionActorType.USER)
        is PeerChat -> InteractionActor(
            id = TelegramPeerChatId.encode(DialogPeerType.BASIC_GROUP, chatId),
            type = StoryInteractionActorType.CHAT,
        )
        is PeerChannel -> InteractionActor(
            id = TelegramPeerChatId.encode(DialogPeerType.CHANNEL, channelId),
            type = StoryInteractionActorType.CHAT,
        )
    }

    private fun Reaction.toStoryReaction(): StoryReactionModel? = when (this) {
        is ReactionEmoji -> StoryReactionModel(emoji = emoticon)
        is ReactionCustomEmoji -> StoryReactionModel(customEmojiId = documentId)
        ReactionPaid -> StoryReactionModel(isPaid = true)
        else -> null
    }

    private fun org.monogram.mtproto.tl.generated.cloud.layer223.StoryItem_7c2143443e.id(): Int? = when (this) {
        is org.monogram.mtproto.tl.generated.cloud.layer223.StoryItem_025493d1a8 -> id
        is StoryItemDeleted -> id
        is org.monogram.mtproto.tl.generated.cloud.layer223.StoryItemSkipped -> id
    }

    private suspend fun resolvePeer(scope: MtProtoAuthKeyScope, chatId: Long): InputPeer {
        val peer = resolvePeerDescriptor(scope, chatId)
        return when (peer.type) {
            DialogPeerType.PRIVATE -> {
                val user = requireNotNull(users.get(scope, peer.id)) { "Missing MTProto user projection: ${peer.id}" }
                InputPeerUser(peer.id, requireNotNull(user.accessHash) { "Missing MTProto user access hash: ${peer.id}" })
            }
            DialogPeerType.BASIC_GROUP -> InputPeerChat(peer.id)
            DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                val chat = requireNotNull(chats.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
                InputPeerChannel(peer.id, requireNotNull(chat.accessHash) { "Missing MTProto channel access hash: ${peer.id}" })
            }
            DialogPeerType.UNKNOWN -> error("MTProto cannot change active stories for an unknown peer")
        }
    }

    private suspend fun resolvePeerDescriptor(
        scope: MtProtoAuthKeyScope,
        chatId: Long,
    ): TelegramPeerChatId.Peer {
        if (chatId > -CHANNEL_OFFSET) return TelegramPeerChatId.decode(chatId)
        val channelId = -(chatId + CHANNEL_OFFSET)
        val chat = requireNotNull(chats.get(scope, channelId)) {
            "Missing MTProto chat projection: $channelId"
        }
        return TelegramPeerChatId.decode(chatId, isChannel = chat.type == MtProtoChatType.CHANNEL)
    }

    private fun StoryReactionModel.toMtProtoReaction(): org.monogram.mtproto.tl.generated.cloud.layer223.Reaction {
        val customEmoji = customEmojiId
        val emoticon = emoji
        return when {
            isPaid && (emoticon != null || customEmoji != null) ->
                throw IllegalArgumentException("MTProto paid story reactions cannot include an emoji selector")
            customEmoji != null && emoticon != null ->
                throw IllegalArgumentException("MTProto story reactions cannot include both emoji selectors")
            isPaid -> ReactionPaid
            customEmoji != null -> ReactionCustomEmoji(customEmoji)
            emoticon != null -> ReactionEmoji(emoticon)
            else -> ReactionEmpty
        }
    }

    private fun peerType(type: DialogPeerType) = when (type) {
        DialogPeerType.PRIVATE -> "USER"
        DialogPeerType.BASIC_GROUP -> "GROUP"
        DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> "CHANNEL"
        DialogPeerType.UNKNOWN -> error("MTProto cannot mark stories read for an unknown peer")
    }

    private data class InteractionActor(val id: Long, val type: StoryInteractionActorType)

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        const val CHANNEL_OFFSET = 1_000_000_000_000L
    }
}
