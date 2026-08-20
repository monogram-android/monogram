package org.monogram.data.mtproto

import android.content.Context
import kotlinx.coroutines.flow.Flow
import org.monogram.data.datasource.cache.StickerLocalDataSource
import org.monogram.data.infra.EmojiLoader
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.models.StickerModel
import org.monogram.domain.repository.EmojiRepository

internal class MtProtoEmojiRepository(
    context: Context,
    private val localDataSource: StickerLocalDataSource,
) : EmojiRepository {
    private val supportedEmojis = lazy(LazyThreadSafetyMode.NONE) {
        EmojiLoader.getSupportedEmojis(context)
    }

    override val recentEmojis: Flow<List<RecentEmojiModel>> = localDataSource.getRecentEmojis()

    override suspend fun getDefaultEmojis(): List<String> = supportedEmojis.value

    override suspend fun searchEmojis(query: String): List<String> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return supportedEmojis.value
        return supportedEmojis.value.filter { it.contains(normalized, ignoreCase = true) }
    }

    override suspend fun searchCustomEmojis(query: String): List<StickerModel> = unsupported()

    override suspend fun addRecentEmoji(recentEmoji: RecentEmojiModel) {
        localDataSource.addRecentEmoji(recentEmoji)
    }

    override suspend fun clearRecentEmojis() {
        localDataSource.clearRecentEmojis()
    }

    override suspend fun getMessageAvailableReactions(chatId: Long, messageId: Long): List<String> = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "MTProto custom emoji and message reaction lookup are not available"
    )
}
