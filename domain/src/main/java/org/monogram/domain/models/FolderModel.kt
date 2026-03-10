package org.monogram.domain.models

import kotlinx.serialization.Serializable


@Serializable
data class FolderModel(
    val id: Int,
    val title: String,
    val iconName: String? = null,
    val unreadCount: Int = 0,
    val includedChatIds: List<Long> = emptyList(),
    val pinnedChatIds: List<Long> = emptyList()
)