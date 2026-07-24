package org.monogram.domain.models

import kotlinx.serialization.Serializable

@Serializable
data class ChatViewportCacheEntry(
    val anchorMessageId: Long? = null,
    val anchorAliasIds: List<Long> = emptyList(),
    val anchorOffsetPx: Int = 0,
    val atBottom: Boolean = true,
    val readFully: Boolean = atBottom,
    val topEndMessageId: Long? = null,
    val returnToMessageIds: List<Long> = emptyList(),
    val anchorChatId: Long? = null
)
