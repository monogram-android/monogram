package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.models.stories.ActiveStoryListModel
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.models.stories.StorySummaryModel
import org.monogram.mtproto.codec.CloudTlObjectCodec
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaVideoStream
import org.monogram.mtproto.tl.generated.cloud.layer223.StoryItem_025493d1a8

internal fun interface MtProtoStoryActiveListReader {
    suspend fun refreshAndRead(): Map<StoryListType, List<ActiveStoryListModel>>
}

internal class MtProtoStoryActiveListReaderImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val refresh: MtProtoStoryRefreshRepository,
    private val stories: MtProtoStoryProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoStoryActiveListReader {
    override suspend fun refreshAndRead(): Map<StoryListType, List<ActiveStoryListModel>> {
        refresh.refreshInitialLists()
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        return StoryListType.entries.associateWith { type -> read(scope, type) }
    }

    private suspend fun read(scope: MtProtoAuthKeyScope, type: StoryListType): List<ActiveStoryListModel> =
        stories.activeList(scope, type.name)
            .groupBy { it.key.peerType to it.key.peerId }
            .mapNotNull { (peer, entries) ->
                val chatId = chatId(scope, peer.first, peer.second) ?: return@mapNotNull null
                val summaries = entries.mapNotNull { entry ->
                    val payload = stories.get(scope, entry.key) ?: return@mapNotNull null
                    val story = runCatching { CloudTlObjectCodec.decode(payload.payload) as? StoryItem_025493d1a8 }.getOrNull()
                        ?: return@mapNotNull null
                    StorySummaryModel(story.id, story.date, story.closeFriends, story.media is MessageMediaVideoStream, story.id <= entry.maxReadStoryId)
                }
                summaries.takeIf { it.isNotEmpty() }?.let {
                    ActiveStoryListModel(chatId, type, entries.maxOf { item -> item.orderKey }, null, entries.maxOf { item -> item.maxReadStoryId }, it)
                }
            }
            .sortedByDescending { it.order }

    private suspend fun chatId(scope: MtProtoAuthKeyScope, type: String, id: Long): Long? = when (type) {
        "USER" -> TelegramPeerChatId.encode(DialogPeerType.PRIVATE, id)
        "GROUP" -> TelegramPeerChatId.encode(DialogPeerType.BASIC_GROUP, id)
        "CHANNEL" -> chats.get(scope, id)?.let { TelegramPeerChatId.encode(if (it.type == MtProtoChatType.CHANNEL) DialogPeerType.CHANNEL else DialogPeerType.SUPERGROUP, id) }
        else -> null
    }

    private companion object { const val DEFAULT_ACCOUNT_SLOT = "default" }
}
