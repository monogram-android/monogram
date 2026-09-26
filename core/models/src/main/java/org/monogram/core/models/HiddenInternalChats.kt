package org.monogram.core.models

import java.util.concurrent.ConcurrentHashMap

private val STATIC_HIDDEN_CHAT_IDS = setOf(
    -1003640797855L,
    -1003566234286L,
)

private val rememberedHiddenChatIds = ConcurrentHashMap.newKeySet<Long>()

fun isHiddenInternalChat(chatId: Long): Boolean =
    chatId in STATIC_HIDDEN_CHAT_IDS || chatId in rememberedHiddenChatIds

fun rememberHiddenInternalChat(chatId: Long) {
    if (chatId != 0L) rememberedHiddenChatIds.add(chatId)
}

fun clearRememberedHiddenInternalChats() {
    rememberedHiddenChatIds.clear()
}
