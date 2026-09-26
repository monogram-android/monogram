package org.monogram.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val isChannel: Boolean,
    val isGroup: Boolean,
    val isForum: Boolean = false,
    val left: Boolean = false,
    val unreadCount: Int,
    val lastMessagePreview: String?,
    val lastMessageOutgoing: Boolean = false,
    val lastMessageSenderName: String? = null,
    val lastMessageMediaKind: String? = null,
    val lastMessageDate: Long?,
    val photoCacheKey: String?,
    val archived: Boolean = false,
    val muted: Boolean = false,
    /** The dialog carries its own mute setting instead of inheriting the peer type default. */
    val muteOverride: Boolean = false,
    /** `dialog.unread_mark`: manually marked unread, independently of [unreadCount]. */
    val unreadMark: Boolean = false,
    val unreadMentionsCount: Int = 0,
    val unreadReactionsCount: Int = 0,
    val isContact: Boolean = false,
    val isBot: Boolean = false,
    val isVerified: Boolean = false,
    val pinned: Boolean = false,
    val pinnedOrder: Int = Int.MAX_VALUE,
    val readInboxMaxId: Int = 0,
    val readOutboxMaxId: Int = 0,
    val peerStatus: String? = null,
    val peerStatusAt: Long? = null,
    val lastMediaThumbCacheKey: String? = null,
    val lastMessageId: Int = 0,
    val dialogScrollMessageId: Int? = null,
    val canView: Boolean = true,
    val canSendPlain: Boolean = true,
    val canSendPhotos: Boolean = true,
    val canForward: Boolean = true,
    val canDeleteOthers: Boolean = false,
    val canManageTopics: Boolean = false,
    val emojiStatusDocumentId: Long? = null,
)
