package org.monogram.feature.dialog.store

import kotlinx.coroutines.launch
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.feature.dialog.ComposerPanels
import org.monogram.feature.dialog.PickerDisk
import org.monogram.feature.dialog.SavedGifMemory
import org.monogram.feature.dialog.StickerCatalogMemory
import org.monogram.feature.dialog.StickerCatalogSnapshot
import org.monogram.feature.dialog.StickerPackMemory

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
    val hasPreviews = cached != null && cached.previewDocumentIds.isNotEmpty()
    // A process restart keeps preview ids on disk but drops the native document
    // locations. Show the cached pack, then still fetch it once so downloads work.
    if (cached != null && hasPreviews && StickerPackMemory.isNativeReady(setId)) {
        emit(Msg.StickerPackLoaded(cached))
        return
    }
    if (!loadingStickerPacks.add(setId)) return
    if (!hasPreviews) emit(Msg.StickerPackLoading(setId))
    work.launch {
        try {
            var known = cached
            if (known == null) {
                val store = sessionStore
                val disk = store?.let { PickerDisk.readPack(it, setId) }
                if (disk != null) {
                    val dropped = StickerPackMemory.put(disk)
                    if (dropped != null) PickerDisk.deletePack(store, dropped)
                    emit(Msg.StickerPackLoaded(disk))
                    known = disk
                }
            } else {
                val already = known
                if (already != null) emit(Msg.StickerPackLoaded(already))
            }
            val shown = known
            if (shown != null &&
                shown.previewDocumentIds.isNotEmpty() &&
                StickerPackMemory.isNativeReady(setId)
            ) {
                return@launch
            }
            stickerPackRequests.acquire()
            try {
                when (val result = client.getStickerSet(setId, accessHash)) {
                    is Outcome.Ok -> {
                        val dropped = StickerPackMemory.put(result.value)
                        StickerPackMemory.markNativeReady(setId)
                        sessionStore?.let {
                            if (dropped != null) PickerDisk.deletePack(it, dropped)
                            PickerDisk.writePack(it, result.value)
                        }
                        emit(Msg.StickerPackLoaded(result.value))
                    }
                    is Outcome.Err -> {
                        handleError(result.telegramError, false)
                        if (shown == null || shown.previewDocumentIds.isEmpty()) {
                            emit(Msg.StickerPackFailed(setId))
                        }
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
