package org.monogram.presentation.features.chats.currentChat.impl

import android.util.Log
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.monogram.presentation.features.chats.currentChat.DefaultChatComponent

internal fun DefaultChatComponent.handleStickerClick(setId: Long) {
    if (setId == 0L) return
    scope.launch {
        try {
            val stickerSet = stickerRepository.getStickerSet(setId)
            _state.update { it.copy(selectedStickerSet = stickerSet) }
        } catch (e: Exception) {
            Log.e("DefaultChatComponent", "Error getting sticker set", e)
        }
    }
}

internal fun DefaultChatComponent.handleAddToGifs(path: String) {
    scope.launch {
        gifRepository.addSavedGif(path)
    }
}
