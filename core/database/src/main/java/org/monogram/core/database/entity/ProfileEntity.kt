package org.monogram.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Cached shared-media counts for a peer profile. */
@Entity(tableName = "profile_tabs")
data class ProfileTabsEntity(
    @PrimaryKey val peerId: Long,
    val tabsJson: String,
    val updatedAt: Long,
)

/** Cached first page of a peer's member/subscriber list. */
@Entity(tableName = "profile_members", primaryKeys = ["peerId", "userId"])
data class ProfileMemberEntity(
    val peerId: Long,
    val userId: Long,
    val position: Int,
    val title: String,
    val username: String? = null,
    val avatarCacheKey: String? = null,
    val status: String? = null,
    val statusAt: Long? = null,
    val role: String,
    val rank: String? = null,
    /** Server-reported participant total; copied onto every row of the cached page. */
    val totalCount: Int = 0,
)

/** Cached first page of a shared-media tab. Kept out of `messages` so dialog previews stay intact. */
@Entity(tableName = "profile_media", primaryKeys = ["peerId", "tab", "messageId"])
data class ProfileMediaEntity(
    val peerId: Long,
    val tab: String,
    val messageId: Int,
    val position: Int,
    val chatId: Long,
    val text: String? = null,
    val date: Long = 0,
    val outgoing: Boolean = false,
    val mediaCacheKey: String? = null,
    val mediaKind: String? = null,
    val thumbCacheKey: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
)

/** Cached groups/channels in common with a user. */
@Entity(tableName = "profile_common", primaryKeys = ["peerId", "chatId"])
data class ProfileCommonEntity(
    val peerId: Long,
    val chatId: Long,
    val position: Int,
    val title: String,
    val isChannel: Boolean = false,
    val isGroup: Boolean = false,
    val photoCacheKey: String? = null,
)
