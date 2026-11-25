package org.monogram.network.bridge

import org.monogram.core.models.Chat

/** A non-null [nextOffset] allows another request even when [chats] is empty. */
data class DialogPage(
    val chats: List<Chat>,
    val nextOffset: DialogOffset?,
)

data class DialogOffset(
    val date: Int,
    val id: Int,
    val peerId: Long,
)