package org.monogram.core.models

private val HIDDEN_CHAT_IDS = setOf(
    -1003640797855L,
    -1003566234286L,
)

fun isHiddenInternalChat(chatId: Long): Boolean = chatId in HIDDEN_CHAT_IDS
