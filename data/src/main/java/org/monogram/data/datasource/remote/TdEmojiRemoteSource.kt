package org.monogram.data.datasource.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.drinkless.tdlib.TdApi
import org.monogram.data.core.coRunCatching
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.mapper.toDomain
import org.monogram.domain.models.StickerModel

class TdEmojiRemoteSource(
    private val gateway: TelegramGateway
) : EmojiRemoteSource {
    override suspend fun getEmojiCategories(): List<String> {
        val types = listOf(
            TdApi.EmojiCategoryTypeDefault(),
            TdApi.EmojiCategoryTypeRegularStickers(),
            TdApi.EmojiCategoryTypeEmojiStatus(),
            TdApi.EmojiCategoryTypeChatPhoto()
        )

        return coroutineScope {
            types
                .map { type ->
                    async {
                        coRunCatching {
                            gateway.execute(TdApi.GetEmojiCategories(type))
                        }.getOrNull()
                    }
                }
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
}