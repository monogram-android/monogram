package org.monogram.data.repository

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import org.monogram.data.datasource.cache.StickerLocalDataSource
import org.monogram.data.datasource.remote.EmojiRemoteSource
import org.monogram.data.infra.EmojiLoader
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.models.StickerModel
import org.monogram.domain.repository.CacheProvider
import org.monogram.domain.repository.EmojiRepository

class EmojiRepositoryImpl(
    private val remote: EmojiRemoteSource,
    private val localDataSource: StickerLocalDataSource,
    private val cacheProvider: CacheProvider,
    private val dispatchers: DispatcherProvider,
    private val context: Context,
    scopeProvider: ScopeProvider
) : EmojiRepository {

    private val scope = scopeProvider.appScope

    override val recentEmojis: Flow<List<RecentEmojiModel>> = cacheProvider.recentEmojis

    private var cachedEmojis: List<String>? = null
    private var fallbackEmojisCache: List<String>? = null

    init {
        scope.launch {
            localDataSource.getRecentEmojis().collect { cacheProvider.setRecentEmojis(it) }
        }
    }

    override suspend fun getDefaultEmojis(): List<String> {
        cachedEmojis?.let { return it }

        val fetched = remote.getEmojiCategories().toMutableSet()
        if (fetched.size < MIN_REMOTE_EMOJIS) {
            fetched.addAll(getFallbackEmojis())
        }

        return fetched.toList().also { cachedEmojis = it }
    }

    override suspend fun searchEmojis(query: String): List<String> {
        return remote.searchEmojis(query)
    }

    override suspend fun searchCustomEmojis(query: String): List<StickerModel> {
        return remote.searchCustomEmojis(query)
    }

    override suspend fun addRecentEmoji(recentEmoji: RecentEmojiModel) {
        cacheProvider.addRecentEmoji(recentEmoji)
        localDataSource.addRecentEmoji(recentEmoji)
    }

    override suspend fun clearRecentEmojis() {
        cacheProvider.clearRecentEmojis()
        localDataSource.clearRecentEmojis()
    }

    override suspend fun getMessageAvailableReactions(chatId: Long, messageId: Long): List<String> {
        return remote.getMessageAvailableReactions(chatId, messageId)
    }

    private suspend fun getFallbackEmojis(): List<String> = withContext(dispatchers.default) {
        fallbackEmojisCache?.let { return@withContext it }
        EmojiLoader.getSupportedEmojis(context).also { fallbackEmojisCache = it }
    }

    companion object {
        private const val MIN_REMOTE_EMOJIS = 100
    }
}