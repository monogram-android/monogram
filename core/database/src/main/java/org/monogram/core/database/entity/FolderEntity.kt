package org.monogram.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: Int,
    val title: String,
    val emoticon: String = "",
    /** Comma-separated peer ids. */
    val chatIdsCsv: String,
    /** Comma-separated `dialogFilter.pinned_peers`. */
    val pinnedChatIdsCsv: String = "",
    val includeContacts: Boolean,
    val includeNonContacts: Boolean,
    val includeGroups: Boolean,
    val includeChannels: Boolean,
    val includeBots: Boolean,
    val excludeChatIdsCsv: String = "",
    val excludeMuted: Boolean = false,
    val excludeRead: Boolean = false,
    val excludeArchived: Boolean = false,
    /** Index in the user's folder order; the cache is its only local copy. */
    val position: Int = 0,
)
