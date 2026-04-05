package org.monogram.domain.repository

import kotlinx.coroutines.flow.Flow
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.models.StickerModel

interface EmojiRepository {
    val recentEmojis: Flow<List<RecentEmojiModel>>

    suspend fun getDefaultEmojis(): List<String>
    suspend fun searchEmojis(query: String): List<String>
    suspend fun searchCustomEmojis(query: String): List<StickerModel>
    suspend fun addRecentEmoji(recentEmoji: RecentEmojiModel)
    suspend fun clearRecentEmojis()
    suspend fun getMessageAvailableReactions(chatId: Long, messageId: Long): List<String>
}