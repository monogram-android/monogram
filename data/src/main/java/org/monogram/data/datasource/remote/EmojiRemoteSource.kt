package org.monogram.data.datasource.remote

import org.monogram.domain.models.StickerModel

interface EmojiRemoteSource {
    suspend fun getEmojiCategories(): List<String>
    suspend fun getMessageAvailableReactions(chatId: Long, messageId: Long): List<String>
    suspend fun searchEmojis(query: String): List<String>
    suspend fun searchCustomEmojis(query: String): List<StickerModel>
}