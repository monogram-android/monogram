package org.monogram.presentation.core.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.monogram.domain.models.AttachMenuBotModel
import org.monogram.domain.models.ChatViewportCacheEntry
import org.monogram.domain.models.FolderModel
import org.monogram.domain.models.GifModel
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.repository.CacheProvider

class CachePreferences(private val context: Context) : CacheProvider {
    private val prefs: SharedPreferences = context.getSharedPreferences("monogram_cache", Context.MODE_PRIVATE)

    private val _recentEmojis = MutableStateFlow<List<RecentEmojiModel>>(emptyList())
    override val recentEmojis: StateFlow<List<RecentEmojiModel>> = _recentEmojis

    private val _searchHistory = MutableStateFlow<List<Long>>(emptyList())
    override val searchHistory: StateFlow<List<Long>> = _searchHistory

    private val _chatFolders = MutableStateFlow<List<FolderModel>>(emptyList())
    override val chatFolders: StateFlow<List<FolderModel>> = _chatFolders

    private val _attachBots = MutableStateFlow<List<AttachMenuBotModel>>(emptyList())
    override val attachBots: StateFlow<List<AttachMenuBotModel>> = _attachBots

    private val _cachedSimCountryIso = MutableStateFlow<String?>(null)
    override val cachedSimCountryIso: StateFlow<String?> = _cachedSimCountryIso

    private val _savedGifs = MutableStateFlow<List<GifModel>>(emptyList())
    override val savedGifs: StateFlow<List<GifModel>> = _savedGifs

    private val _installedStickerSets = MutableStateFlow<List<StickerSetModel>>(emptyList())
    override val installedStickerSets: StateFlow<List<StickerSetModel>> = _installedStickerSets

    private val _customEmojiStickerSets = MutableStateFlow<List<StickerSetModel>>(emptyList())
    override val customEmojiStickerSets: StateFlow<List<StickerSetModel>> = _customEmojiStickerSets

    init {
        clearLegacySavedGifsCache()
    }

    override fun addRecentEmoji(recentEmoji: RecentEmojiModel) {
        val current = _recentEmojis.value.toMutableList()
        current.removeIf { it.emoji == recentEmoji.emoji && it.sticker?.id == recentEmoji.sticker?.id }
        current.add(0, recentEmoji)
        if (current.size > 50) {
            current.removeAt(current.lastIndex)
        }
        _recentEmojis.value = current
    }

    override fun clearRecentEmojis() {
        _recentEmojis.value = emptyList()
    }

    override fun setRecentEmojis(emojis: List<RecentEmojiModel>) {
        _recentEmojis.value = emojis
    }

    override fun addSearchChatId(chatId: Long) {
        val current = _searchHistory.value.toMutableList()
        current.remove(chatId)
        current.add(0, chatId)
        if (current.size > 40) {
            current.removeAt(current.lastIndex)
        }
        _searchHistory.value = current
    }

    override fun removeSearchChatId(chatId: Long) {
        val current = _searchHistory.value.toMutableList()
        if (current.remove(chatId)) {
            _searchHistory.value = current
        }
    }

    override fun clearSearchHistory() {
        _searchHistory.value = emptyList()
    }

    override fun setSearchHistory(history: List<Long>) {
        _searchHistory.value = history
    }

    override fun setChatFolders(folders: List<FolderModel>) {
        _chatFolders.value = folders
    }

    override fun setAttachBots(bots: List<AttachMenuBotModel>) {
        _attachBots.value = bots
    }

    override fun setCachedSimCountryIso(iso: String?) {
        _cachedSimCountryIso.value = iso
    }

    override fun saveChatScrollPosition(chatId: Long, messageId: Long) {
        prefs.edit().putLong("chat_scroll_$chatId", messageId).apply()
    }

    override fun getChatScrollPosition(chatId: Long): Long {
        return prefs.getLong("chat_scroll_$chatId", 0L)
    }

    override fun saveChatViewport(chatId: Long, threadId: Long?, viewport: ChatViewportCacheEntry) {
        prefs.edit()
            .putString(chatViewportKey(chatId, threadId), Json.encodeToString(viewport))
            .apply()
    }

    override fun getChatViewport(chatId: Long, threadId: Long?): ChatViewportCacheEntry? {
        val directJson = prefs.getString(chatViewportKey(chatId, threadId), null)
        if (!directJson.isNullOrBlank()) {
            return try {
                Json.decodeFromString<ChatViewportCacheEntry>(directJson)
            } catch (_: Exception) {
                null
            }
        }

        if (threadId != null) return null
        if (!prefs.contains("chat_scroll_$chatId")) return null

        val legacy = prefs.getLong("chat_scroll_$chatId", 0L)
        return if (legacy == 0L) {
            ChatViewportCacheEntry(atBottom = true, readFully = true)
        } else {
            ChatViewportCacheEntry(
                anchorMessageId = legacy,
                anchorOffsetPx = 0,
                atBottom = false,
                readFully = false
            )
        }
    }

    override fun saveChatListScrollPosition(folderId: Int, index: Int, offset: Int) {
        prefs.edit()
            .putString(
                chatListScrollKey(folderId),
                Json.encodeToString(ChatListScrollPosition(index, offset))
            )
            .apply()
    }

    override fun getChatListScrollPosition(folderId: Int): Pair<Int, Int>? {
        val raw = prefs.getString(chatListScrollKey(folderId), null) ?: return null
        val decoded = runCatching {
            Json.decodeFromString<ChatListScrollPosition>(raw)
        }.getOrNull() ?: return null
        return decoded.index to decoded.offset
    }

    override fun getChatListScrollPositions(): Map<Int, Pair<Int, Int>> {
        return prefs.all.entries.mapNotNull { (key, value) ->
            if (!key.startsWith(CHAT_LIST_SCROLL_PREFIX)) return@mapNotNull null
            val folderId =
                key.removePrefix(CHAT_LIST_SCROLL_PREFIX).toIntOrNull() ?: return@mapNotNull null
            val raw = value as? String ?: return@mapNotNull null
            val decoded = runCatching {
                Json.decodeFromString<ChatListScrollPosition>(raw)
            }.getOrNull() ?: return@mapNotNull null
            folderId to (decoded.index to decoded.offset)
        }.toMap()
    }

    override fun setSavedGifs(gifs: List<GifModel>) {
        _savedGifs.value = gifs
    }

    override fun setInstalledStickerSets(sets: List<StickerSetModel>) {
        _installedStickerSets.value = sets
    }

    override fun setCustomEmojiStickerSets(sets: List<StickerSetModel>) {
        _customEmojiStickerSets.value = sets
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
        _recentEmojis.value = emptyList()
        _searchHistory.value = emptyList()
        _chatFolders.value = emptyList()
        _attachBots.value = emptyList()
        _cachedSimCountryIso.value = null
        _savedGifs.value = emptyList()
        _installedStickerSets.value = emptyList()
        _customEmojiStickerSets.value = emptyList()
    }

    private fun clearLegacySavedGifsCache() {
        if (prefs.contains(KEY_SAVED_GIFS)) {
            prefs.edit().remove(KEY_SAVED_GIFS).apply()
        }
    }

    companion object {
        private const val KEY_SAVED_GIFS = "saved_gifs"
        private const val CHAT_LIST_SCROLL_PREFIX = "chat_list_scroll_"

        private fun chatViewportKey(chatId: Long, threadId: Long?): String {
            return "chat_viewport_${chatId}_${threadId ?: 0L}"
        }

        private fun chatListScrollKey(folderId: Int): String = "$CHAT_LIST_SCROLL_PREFIX$folderId"
    }
}

@Serializable
private data class ChatListScrollPosition(
    val index: Int,
    val offset: Int
)
