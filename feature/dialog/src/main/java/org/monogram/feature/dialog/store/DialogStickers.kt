package org.monogram.feature.dialog.store

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.PerfLog
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.Chat
import org.monogram.core.models.ChatActionKind
import org.monogram.core.models.ForumIo
import org.monogram.core.models.ForumTopic
import org.monogram.core.models.InlineBotResult
import org.monogram.core.models.InlineBotResults
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.MessageViewers
import org.monogram.core.models.OutboxReadState
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.ReactionChoice
import org.monogram.core.models.ReadReceiptConfig
import org.monogram.core.models.ReplyButton
import org.monogram.core.models.ReplyButtonType
import org.monogram.core.models.ReplyMarkups
import org.monogram.core.models.SavedGif
import org.monogram.core.models.StickerPack
import org.monogram.core.models.StyledText
import org.monogram.core.models.TextEntities
import org.monogram.core.models.TextEntity
import org.monogram.core.models.TypingPresence
import org.monogram.core.models.UploadItem
import org.monogram.core.models.canShowMessageViewers
import org.monogram.core.models.canShowOutboxReadDate
import org.monogram.core.models.geoPlace
import org.monogram.core.models.isPlaceholderPeerTitle
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.models.playedMediaKind
import org.monogram.core.models.preferredPeerTitle
import org.monogram.feature.dialog.ComposerAt
import org.monogram.feature.dialog.ComposerAtToken
import org.monogram.feature.dialog.ComposerPanels
import org.monogram.feature.dialog.DialogStore
import org.monogram.feature.dialog.DraftMention
import org.monogram.feature.dialog.InlineBotQuery
import org.monogram.feature.dialog.MentionCandidate
import org.monogram.feature.dialog.PickerDisk
import org.monogram.feature.dialog.PinnedBarMemory
import org.monogram.feature.dialog.SEARCH_DEBOUNCE_MS
import org.monogram.feature.dialog.SavedGifMemory
import org.monogram.feature.dialog.StickerCatalogSnapshot
import org.monogram.feature.dialog.SenderTagMemory
import org.monogram.feature.dialog.StickerCatalogMemory
import org.monogram.feature.dialog.StickerPackMemory
import org.monogram.feature.dialog.applyMessageEdit
import org.monogram.feature.dialog.historyPagingAllowed
import org.monogram.feature.dialog.isTransientSendFailure
import org.monogram.feature.dialog.jumpNeedsFetch
import org.monogram.feature.dialog.localMediaCacheKey
import org.monogram.feature.dialog.mergeSenderTags
import org.monogram.feature.dialog.parseUpdateMessageId
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import kotlin.time.Duration.Companion.milliseconds

internal fun DialogExecutor.warmPickerCatalogs() {
    warmStickerCatalog(emoji = false)
    warmStickerCatalog(emoji = true)
    warmSavedGifs()
}

private fun DialogExecutor.warmStickerCatalog(emoji: Boolean) {
    StickerCatalogMemory.get(emoji)?.let { cached ->
        if (emoji) {
            if (snapshot().emojiSets.isEmpty()) emit(Msg.EmojiSets(cached.sets))
        } else if (snapshot().stickerSets.isEmpty()) {
            emit(Msg.StickerSets(cached.sets))
        }
        if (!StickerCatalogMemory.isLive(emoji)) refreshStickerCatalog(emoji, cached.hash)
        return
    }
    val store = sessionStore ?: return
    work.launch {
        val cached = PickerDisk.readCatalog(store, emoji) ?: return@launch
        if (StickerCatalogMemory.get(emoji) != null) return@launch
        StickerCatalogMemory.put(emoji, cached)
        if (emoji) emit(Msg.EmojiSets(cached.sets)) else emit(Msg.StickerSets(cached.sets))
        refreshStickerCatalog(emoji, cached.hash)
    }
}

private fun DialogExecutor.warmSavedGifs() {
    SavedGifMemory.get()?.let {
        if (!snapshot().savedGifsLoaded) emit(Msg.SavedGifs(it))
        if (!SavedGifMemory.isLive()) refreshSavedGifs()
        return
    }
    val store = sessionStore ?: return
    work.launch {
        val cached = PickerDisk.readGifs(store) ?: return@launch
        if (SavedGifMemory.get() != null) return@launch
        SavedGifMemory.put(cached)
        emit(Msg.SavedGifs(cached))
        refreshSavedGifs()
    }
}

internal fun DialogExecutor.loadSavedGifs() {
    SavedGifMemory.get()?.let {
        emit(Msg.SavedGifs(it))
        if (!SavedGifMemory.isLive()) refreshSavedGifs()
        return
    }
    work.launch {
        sessionStore?.let { PickerDisk.readGifs(it) }?.let { cached ->
            SavedGifMemory.put(cached)
            emit(Msg.SavedGifs(cached))
        }
        refreshSavedGifs()
    }
}

internal fun DialogExecutor.refreshSavedGifs() {
    work.launch {
        when (val result = client.getSavedGifs()) {
            is Outcome.Ok -> {
                SavedGifMemory.put(result.value)
                SavedGifMemory.markLive()
                sessionStore?.let { PickerDisk.writeGifs(it, result.value) }
                emit(Msg.SavedGifs(result.value))
            }
            is Outcome.Err -> {
                AppLog.api("dialog", "getSavedGifs ${result.telegramError}")
                if (snapshot().savedGifs.isEmpty()) {
                    emit(Msg.SavedGifs(emptyList(), error = true))
                }
            }
        }
    }
}

internal fun DialogExecutor.openEmojiTab(tab: String) {
    emit(Msg.ComposerPanel(ComposerPanels.EMOJI))
    emit(Msg.EmojiTab(tab))
    emit(Msg.GifPicker(tab == ComposerPanels.TAB_GIFS))
    when (tab) {
        ComposerPanels.TAB_GIFS ->
            if (!snapshot().savedGifsLoaded || snapshot().savedGifsError) loadSavedGifs()
        ComposerPanels.TAB_STICKERS ->
            if (snapshot().stickerSets.isEmpty() || snapshot().stickerSetsError) {
                loadStickerCatalog(emoji = false)
            }
        ComposerPanels.TAB_EMOJI ->
            if (snapshot().emojiSets.isEmpty() || snapshot().emojiSetsError) {
                loadStickerCatalog(emoji = true)
            }
    }
}

internal fun DialogExecutor.loadStickerCatalog(emoji: Boolean) {
    StickerCatalogMemory.get(emoji)?.let { cached ->
        if (emoji) emit(Msg.EmojiSets(cached.sets)) else emit(Msg.StickerSets(cached.sets))
        if (!StickerCatalogMemory.isLive(emoji)) refreshStickerCatalog(emoji, cached.hash)
        return
    }
    work.launch {
        sessionStore?.let { PickerDisk.readCatalog(it, emoji) }?.let { cached ->
            StickerCatalogMemory.put(emoji, cached)
            if (emoji) emit(Msg.EmojiSets(cached.sets)) else emit(Msg.StickerSets(cached.sets))
            refreshStickerCatalog(emoji, cached.hash)
            return@launch
        }
        refreshStickerCatalog(emoji, 0L)
    }
}

internal fun DialogExecutor.refreshStickerCatalog(emoji: Boolean, hash: Long) {
    work.launch {
        val result = if (emoji) client.getEmojiStickers(hash) else client.getAllStickers(hash)
        when (result) {
            is Outcome.Ok -> {
                StickerCatalogMemory.markLive(emoji)
                if (result.value.notModified) {
                    val cached = StickerCatalogMemory.get(emoji)?.sets.orEmpty()
                    if (emoji) emit(Msg.EmojiSets(cached)) else emit(Msg.StickerSets(cached))
                    return@launch
                }
                val snapshot = StickerCatalogSnapshot(result.value.hash, result.value.sets)
                StickerCatalogMemory.put(emoji, snapshot)
                sessionStore?.let { PickerDisk.writeCatalog(it, emoji, snapshot) }
                if (emoji) {
                    emit(Msg.EmojiSets(result.value.sets))
                } else {
                    emit(Msg.StickerSets(result.value.sets))
                }
            }
            is Outcome.Err -> {
                handleError(result.telegramError, false)
                val empty = if (emoji) snapshot().emojiSets.isEmpty() else snapshot().stickerSets.isEmpty()
                if (empty) {
                    if (emoji) {
                        emit(Msg.EmojiSets(emptyList(), error = true))
                    } else {
                        emit(Msg.StickerSets(emptyList(), error = true))
                    }
                }
            }
        }
    }
}

internal fun DialogExecutor.loadStickerPack(setId: Long, accessHash: Long) {
    val cached = StickerPackMemory.get(setId) ?: snapshot().loadedStickerPacks[setId]
    if (cached != null && cached.previewDocumentIds.isNotEmpty()) {
        emit(Msg.StickerPackLoaded(cached))
        return
    }
    if (!loadingStickerPacks.add(setId)) return
    emit(Msg.StickerPackLoading(setId))
    work.launch {
        try {
            if (cached == null) {
                sessionStore?.let { PickerDisk.readPack(it, setId) }?.let { disk ->
                    StickerPackMemory.put(disk)
                    emit(Msg.StickerPackLoaded(disk))
                    if (disk.previewDocumentIds.isNotEmpty()) return@launch
                }
            }
            stickerPackRequests.acquire()
            try {
                when (val result = client.getStickerSet(setId, accessHash)) {
                    is Outcome.Ok -> {
                        StickerPackMemory.put(result.value)
                        sessionStore?.let { PickerDisk.writePack(it, result.value) }
                        emit(Msg.StickerPackLoaded(result.value))
                    }
                    is Outcome.Err -> {
                        handleError(result.telegramError, false)
                        emit(Msg.StickerPackFailed(setId))
                    }
                }
            } finally {
                stickerPackRequests.release()
            }
        } finally {
            loadingStickerPacks.remove(setId)
        }
    }
}
