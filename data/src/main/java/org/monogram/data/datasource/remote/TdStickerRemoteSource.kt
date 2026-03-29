package org.monogram.data.datasource.remote

import org.monogram.data.core.coRunCatching
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.drinkless.tdlib.TdApi
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.mapper.toApi
import org.monogram.data.mapper.toDomain
import org.monogram.domain.models.GifModel
import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.models.StickerType

class TdStickerRemoteSource(
    private val gateway: TelegramGateway
) : StickerRemoteSource {
    override suspend fun getInstalledStickerSets(type: StickerType): List<StickerSetModel> {
        return coRunCatching {
            gateway.execute(TdApi.GetInstalledStickerSets(type.toApi()))
                .sets
                .mapNotNull { getStickerSet(it.id) }
        }.getOrDefault(emptyList())
    }

    override suspend fun getArchivedStickerSets(type: StickerType): List<StickerSetModel> {
        return coRunCatching {
            gateway.execute(TdApi.GetArchivedStickerSets(type.toApi(), 0, 100))
                .sets
                .mapNotNull { getStickerSet(it.id) }
        }.getOrDefault(emptyList())
    }

    override suspend fun getStickerSet(setId: Long): StickerSetModel? {
        return coRunCatching {
            gateway.execute(TdApi.GetStickerSet(setId)).toDomain()
        }.getOrNull()
    }

    override suspend fun getStickerSetByName(name: String): StickerSetModel? {
        return coRunCatching {
            gateway.execute(TdApi.SearchStickerSet(name, false)).toDomain()
        }.getOrNull()
    }

    override suspend fun getRecentStickers(): List<StickerModel> {
        return coRunCatching {
            gateway.execute(TdApi.GetRecentStickers(false))
                .stickers
                .map { it.toDomain() }
        }.getOrDefault(emptyList())
    }

    override suspend fun toggleStickerSetInstalled(setId: Long, isInstalled: Boolean) {
        coRunCatching { gateway.execute(TdApi.ChangeStickerSet(setId, isInstalled, false)) }
    }

    override suspend fun toggleStickerSetArchived(setId: Long, isArchived: Boolean) {
        coRunCatching { gateway.execute(TdApi.ChangeStickerSet(setId, false, isArchived)) }
    }

    override suspend fun reorderStickerSets(type: StickerType, setIds: List<Long>) {
        coRunCatching {
            gateway.execute(TdApi.ReorderInstalledStickerSets(type.toApi(), setIds.toLongArray()))
        }
    }

    override suspend fun getEmojiCategories(): List<String> {
        val types = listOf(
            TdApi.EmojiCategoryTypeDefault(),
            TdApi.EmojiCategoryTypeRegularStickers(),
            TdApi.EmojiCategoryTypeEmojiStatus(),
            TdApi.EmojiCategoryTypeChatPhoto()
        )
        return coroutineScope {
            types
                .map { type -> async { coRunCatching { gateway.execute(TdApi.GetEmojiCategories(type)) }.getOrNull() } }
                .awaitAll()
                .asSequence()
                .filterNotNull()
                .flatMap { it.categories.asSequence() }
                .mapNotNull { it.source as? TdApi.EmojiCategorySourceSearch }
                .flatMap { it.emojis.asSequence() }
                .toList()
        }
    }

    override suspend fun getMessageAvailableReactions(chatId: Long, messageId: Long): List<String> {
        return coRunCatching {
            val result = gateway.execute(TdApi.GetMessageAvailableReactions(chatId, messageId, 32))
            buildSet {
                result.topReactions.forEach { (it.type as? TdApi.ReactionTypeEmoji)?.let { r -> add(r.emoji) } }
                result.recentReactions.forEach { (it.type as? TdApi.ReactionTypeEmoji)?.let { r -> add(r.emoji) } }
                result.popularReactions.forEach { (it.type as? TdApi.ReactionTypeEmoji)?.let { r -> add(r.emoji) } }
            }.toList()
        }.getOrDefault(emptyList())
    }

    override suspend fun searchEmojis(query: String): List<String> {
        return coRunCatching {
            gateway.execute(TdApi.SearchEmojis(query, emptyArray()))
                .emojiKeywords
                .map { it.emoji }
        }.getOrDefault(emptyList())
    }

    override suspend fun searchCustomEmojis(query: String): List<StickerModel> {
        return coRunCatching {
            gateway.execute(
                TdApi.SearchStickers(TdApi.StickerTypeCustomEmoji(), "", query, emptyArray(), 0, 100)
            ).stickers.map { it.toDomain() }
        }.getOrDefault(emptyList())
    }

    override suspend fun searchStickers(query: String): List<StickerModel> {
        return coRunCatching {
            gateway.execute(
                TdApi.SearchStickers(TdApi.StickerTypeRegular(), "", query, emptyArray(), 0, 100)
            ).stickers.map { it.toDomain() }
        }.getOrDefault(emptyList())
    }

    override suspend fun searchStickerSets(query: String): List<StickerSetModel> {
        return coRunCatching {
            gateway.execute(TdApi.SearchStickerSets(TdApi.StickerTypeRegular(), query))
                .sets
                .mapNotNull { getStickerSet(it.id) }
        }.getOrDefault(emptyList())
    }

    override suspend fun getSavedGifs(): List<GifModel> {
        return coRunCatching {
            val recentGifs = coRunCatching {
                gateway.execute(TdApi.GetSavedAnimations())
                    .animations
                    .map { animation ->
                        GifModel(
                            id = animation.animation.remote?.id?.takeIf { it.isNotEmpty() } ?: animation.animation.id.toString(),
                            fileId = animation.animation.id.toLong(),
                            thumbFileId = animation.thumbnail?.file?.id?.toLong(),
                            width = animation.width,
                            height = animation.height
                        )
                    }
            }.getOrDefault(emptyList())

            when {
                recentGifs.isNotEmpty() -> {
                    return@coRunCatching recentGifs
                }
                else -> return searchGifs("")
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun addSavedGif(path: String) {
        coRunCatching { gateway.execute(TdApi.AddSavedAnimation(TdApi.InputFileLocal(path))) }
    }

    override suspend fun searchGifs(query: String): List<GifModel> {
        return coRunCatching {
            val chat = gateway.execute(TdApi.SearchPublicChat("gif"))
            val type = chat.type as? TdApi.ChatTypePrivate ?: return emptyList()
            gateway.execute(TdApi.GetInlineQueryResults(type.userId, chat.id, null, query, ""))
                .results
                .mapNotNull { item ->
                    if (item !is TdApi.InlineQueryResultAnimation) return@mapNotNull null
                    GifModel(
                        id = item.id,
                        fileId = item.animation.animation.id.toLong(),
                        thumbFileId = item.animation.thumbnail?.file?.id?.toLong(),
                        width = item.animation.width,
                        height = item.animation.height
                    )
                }
        }.getOrDefault(emptyList())
    }

    override suspend fun clearRecentStickers() {
        coRunCatching { gateway.execute(TdApi.ClearRecentStickers()) }
    }
}