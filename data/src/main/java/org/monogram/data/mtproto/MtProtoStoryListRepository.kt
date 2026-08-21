package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.models.stories.StoryListType
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.ReadStories
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.TogglePeerStoriesHidden

internal interface MtProtoStoryListRepository {
    suspend fun setActiveStoriesList(chatId: Long, listType: StoryListType?): Boolean
    suspend fun markRead(chatId: Long, storyId: Int) {
        throw UnsupportedOperationException("MTProto story read marking is not configured")
    }
}

internal class MtProtoStoryListRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val stories: MtProtoStoryProjectionStore = NoOpMtProtoStoryProjectionStore,
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
        val peer = TelegramPeerChatId.decode(chatId)
        transportFactory.open(accountSlot).use { transport ->
            transport.execute(ReadStories(resolvePeer(scope, chatId), storyId))
        }
        stories.updateMaxReadStoryId(scope, peerType(peer.type), peer.id, storyId)
    }

    private suspend fun resolvePeer(scope: MtProtoAuthKeyScope, chatId: Long): InputPeer {
        val peer = TelegramPeerChatId.decode(chatId)
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

    private fun peerType(type: DialogPeerType) = when (type) {
        DialogPeerType.PRIVATE -> "USER"
        DialogPeerType.BASIC_GROUP -> "GROUP"
        DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> "CHANNEL"
        DialogPeerType.UNKNOWN -> error("MTProto cannot mark stories read for an unknown peer")
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
