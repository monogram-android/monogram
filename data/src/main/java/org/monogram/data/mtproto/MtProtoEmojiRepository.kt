package org.monogram.data.mtproto

import android.content.Context
import kotlinx.coroutines.flow.Flow
import org.monogram.data.datasource.cache.StickerLocalDataSource
import org.monogram.data.infra.EmojiLoader
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.models.StickerModel
import org.monogram.domain.repository.EmojiRepository
import org.monogram.domain.models.StickerFormat
import org.monogram.mtproto.tl.generated.cloud.layer223.AvailableReaction_bcdc20ef08
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeCustomEmoji
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeImageSize
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeSticker
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeVideo
import org.monogram.mtproto.tl.generated.cloud.layer223.Document_be725c3b31
import org.monogram.mtproto.tl.generated.cloud.layer223.EmojiList_50973b9ed3
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.AvailableReactions_a572c1b4d2
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.AvailableReactionsNotModified
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetAvailableReactions
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetCustomEmojiDocuments
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SearchCustomEmoji

internal class MtProtoEmojiRepository(
    context: Context?,
    private val localDataSource: StickerLocalDataSource,
    private val transportFactory: MtProtoSessionTransportFactory? = null,
    private val configSource: TelegramMtProtoBootstrapConfigSource? = null,
    private val locations: MtProtoDocumentLocationStore? = null,
    private val accountSlot: String = "default",
    private val fallbackEmojis: () -> List<String> = { EmojiLoader.getSupportedEmojis(requireNotNull(context)) },
) : EmojiRepository {
    private val supportedEmojis = lazy(LazyThreadSafetyMode.NONE, fallbackEmojis)

    override val recentEmojis: Flow<List<RecentEmojiModel>> = localDataSource.getRecentEmojis()

    private var remoteEmojis: List<String>? = null

    override suspend fun getDefaultEmojis(): List<String> = emojis()

    override suspend fun searchEmojis(query: String): List<String> {
        val emojis = emojis()
        val normalized = query.trim()
        if (normalized.isEmpty()) return emojis
        return emojis.filter { it.contains(normalized, ignoreCase = true) }
    }

    override suspend fun searchCustomEmojis(query: String): List<StickerModel> {
        val emoticon = query.trim()
        if (emoticon.isEmpty()) return emptyList()
        val factory = requireNotNull(transportFactory) { "MTProto custom emoji search is not configured" }
        val ids = factory.open(accountSlot).use { transport ->
            (transport.execute(SearchCustomEmoji(emoticon, 0L)) as? EmojiList_50973b9ed3)?.documentId.orEmpty()
        }
        if (ids.isEmpty()) return emptyList()
        val config = requireNotNull(configSource) { "MTProto custom emoji locations are not configured" }
            .createForAccount(accountSlot)
        val store = requireNotNull(locations) { "MTProto custom emoji locations are not configured" }
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val documents = factory.open(accountSlot).use { transport ->
            transport.execute(GetCustomEmojiDocuments(ids)).filterIsInstance<Document_be725c3b31>()
        }
        documents.forEach { store.upsert(scope, it) }
        val byId = documents.associateBy(Document_be725c3b31::id)
        return ids.mapNotNull { byId[it]?.toStickerModel() }
    }

    override suspend fun addRecentEmoji(recentEmoji: RecentEmojiModel) {
        localDataSource.addRecentEmoji(recentEmoji)
    }

    override suspend fun clearRecentEmojis() {
        localDataSource.clearRecentEmojis()
    }

    override suspend fun getMessageAvailableReactions(chatId: Long, messageId: Long): List<String> {
        val factory = transportFactory ?: return supportedEmojis.value
        val reactions = factory.open(accountSlot).use { transport ->
            when (val response = transport.execute(GetAvailableReactions(0))) {
                is AvailableReactions_a572c1b4d2 -> response.reactions
                    .filterIsInstance<AvailableReaction_bcdc20ef08>()
                    .filterNot(AvailableReaction_bcdc20ef08::inactive)
                    .map(AvailableReaction_bcdc20ef08::reaction)
                    .distinct()
                is AvailableReactionsNotModified -> emptyList()
            }
        }
        return reactions.ifEmpty { supportedEmojis.value }
    }

    private suspend fun emojis(): List<String> {
        remoteEmojis?.let { return it }
        val fetched = transportFactory?.open(accountSlot)?.use { transport ->
            when (val response = transport.execute(GetAvailableReactions(0))) {
                is AvailableReactions_a572c1b4d2 -> response.reactions
                    .filterIsInstance<AvailableReaction_bcdc20ef08>()
                    .filterNot(AvailableReaction_bcdc20ef08::inactive)
                    .map(AvailableReaction_bcdc20ef08::reaction)
                    .distinct()
                else -> emptyList()
            }
        }.orEmpty()
        return fetched.takeIf { it.isNotEmpty() }?.also { remoteEmojis = it } ?: supportedEmojis.value
    }

    private fun Document_be725c3b31.toStickerModel(): StickerModel? {
        val customEmoji = attributes.filterIsInstance<DocumentAttributeCustomEmoji>().firstOrNull() ?: return null
        val sticker = attributes.filterIsInstance<DocumentAttributeSticker>().firstOrNull()
        val dimensions = attributes.filterIsInstance<DocumentAttributeImageSize>().firstOrNull()
        val video = attributes.filterIsInstance<DocumentAttributeVideo>().firstOrNull()
        return StickerModel(
            id = id,
            customEmojiId = id,
            width = dimensions?.w ?: video?.w ?: 0,
            height = dimensions?.h ?: video?.h ?: 0,
            emoji = customEmoji.alt.ifBlank { sticker?.alt.orEmpty() },
            path = null,
            format = when {
                video != null || mimeType == "video/webm" -> StickerFormat.VIDEO
                mimeType == "application/x-tgsticker" -> StickerFormat.ANIMATED
                else -> StickerFormat.STATIC
            },
        )
    }
}
