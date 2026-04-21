package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi

internal fun encodeChatPositions(positions: Array<TdApi.ChatPosition>): String? {
    if (positions.isEmpty()) return null

    val encoded = positions.mapNotNull { position ->
        if (position.order == 0L) return@mapNotNull null
        val pinned = if (position.isPinned) 1 else 0
        when (position.list) {
            is TdApi.ChatListMain -> "m:${position.order}:$pinned"
            is TdApi.ChatListArchive -> "a:${position.order}:$pinned"
            is TdApi.ChatListFolder -> null
            else -> null
        }
    }

    return if (encoded.isEmpty()) null else encoded.joinToString("|")
}