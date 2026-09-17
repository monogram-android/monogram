package org.monogram.feature.dialog

/** Dialog identity for Compose saveable state; a forum topic shares the chat id. */
fun dialogStateKey(chatId: Long, threadTopMsgId: Int, jumpToMessageId: Int): String =
    "$chatId:$threadTopMsgId:$jumpToMessageId"
