package org.monogram.data.mtproto

import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.Peer
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerStories_9de86f4fe6
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.StoryItemSkipped
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.AllStoriesNotModified
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.AllStories_75ae93d8cd
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.GetAllStories
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.GetStoriesById
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.Stories_e08ba69811

/** Refreshes canonical story projections without exposing an incomplete domain mapping. */
internal fun interface MtProtoStoryRefreshRepository {
    suspend fun refreshInitialLists()
}

internal object NoOpMtProtoStoryRefreshRepository : MtProtoStoryRefreshRepository {
    override suspend fun refreshInitialLists() = Unit
}

internal class MtProtoStoryRefreshRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val stories: MtProtoStoryProjectionStore,
    private val resultStager: MtProtoStoryResultStager,
    private val users: MtProtoUserProjectionStore = NoOpMtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore = NoOpMtProtoChatProjectionStore,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoStoryRefreshRepository {
    override suspend fun refreshInitialLists() {
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        transportFactory.open(accountSlot).use { transport ->
            refreshInitialList(transport, scope, LIST_MAIN, hidden = false)
            refreshInitialList(transport, scope, LIST_ARCHIVE, hidden = true)
        }
    }

    private suspend fun refreshInitialList(
        transport: org.monogram.mtproto.transport.MtProtoRpcTransport,
        scope: MtProtoAuthKeyScope,
        listType: String,
        hidden: Boolean,
    ) {
        val previous = stories.cursor(scope, listType)
        var result = transport.execute(GetAllStories(next = false, hidden = hidden, state = previous?.state))
        when (result) {
            is AllStories_75ae93d8cd -> stageAndHydrate(transport, scope, listType, result)
            is AllStoriesNotModified -> {
                val cursor = requireNotNull(previous) {
                    "MTProto story list was not modified before it was initialized: $listType"
                }
                stories.replaceActiveList(scope, listType, stories.activeList(scope, listType), cursor.copy(state = result.state))
                return
            }
        }
        while (result is AllStories_75ae93d8cd && result.hasMore) {
            result = transport.execute(GetAllStories(next = true, hidden = hidden, state = result.state))
            when (result) {
                is AllStories_75ae93d8cd -> stageAndHydrate(transport, scope, listType, result, append = true)
                is AllStoriesNotModified -> return
            }
        }
    }

    private suspend fun stageAndHydrate(
        transport: org.monogram.mtproto.transport.MtProtoRpcTransport,
        scope: MtProtoAuthKeyScope,
        listType: String,
        result: AllStories_75ae93d8cd,
        append: Boolean = false,
    ) {
        resultStager.stageAllStories(scope, listType, result, append)
        result.peerStories.filterIsInstance<PeerStories_9de86f4fe6>().forEach { peerStories ->
            val skippedIds = peerStories.stories.filterIsInstance<StoryItemSkipped>().map { it.id }
            if (skippedIds.isEmpty()) return@forEach
            val hydrated = transport.execute(GetStoriesById(resolvePeer(scope, peerStories.peer), skippedIds))
            if (hydrated is Stories_e08ba69811) resultStager.stageStories(scope, peerStories.peer, hydrated)
        }
    }

    private suspend fun resolvePeer(scope: MtProtoAuthKeyScope, peer: Peer): InputPeer = when (peer) {
        is PeerUser -> {
            val user = requireNotNull(users.get(scope, peer.userId)) { "Missing MTProto user projection: ${peer.userId}" }
            InputPeerUser(peer.userId, requireNotNull(user.accessHash) { "Missing MTProto user access hash: ${peer.userId}" })
        }
        is PeerChat -> InputPeerChat(peer.chatId)
        is PeerChannel -> {
            val chat = requireNotNull(chats.get(scope, peer.channelId)) { "Missing MTProto chat projection: ${peer.channelId}" }
            InputPeerChannel(peer.channelId, requireNotNull(chat.accessHash) { "Missing MTProto channel access hash: ${peer.channelId}" })
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        const val LIST_MAIN = "MAIN"
        const val LIST_ARCHIVE = "ARCHIVE"
    }
}
