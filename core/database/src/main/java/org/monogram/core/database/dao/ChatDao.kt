package org.monogram.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.monogram.core.database.entity.ChatEntity

data class ChatReadState(
    val id: Long,
    val readInboxMaxId: Int,
    val unreadCount: Int,
    val unreadMentionsCount: Int? = null,
    val unreadReactionsCount: Int? = null,
)

@Dao
interface ChatDao {
    @Query("UPDATE chats SET readOutboxMaxId = :maxId WHERE id = :chatId AND readOutboxMaxId < :maxId")
    suspend fun updateOutboxRead(
        chatId: Long,
        maxId: Int,
    )

    @Query(
        "SELECT id, readInboxMaxId, unreadCount, unreadMentionsCount, unreadReactionsCount FROM chats",
    )
    fun observeReadStates(): Flow<List<ChatReadState>>

    @Query(
        "UPDATE chats SET readInboxMaxId = :maxId, unreadCount = :unread WHERE id = :chatId AND readInboxMaxId <= :maxId AND (readInboxMaxId != :maxId OR unreadCount != :unread)",
    )
    suspend fun updateInboxRead(
        chatId: Long,
        maxId: Int,
        unread: Int,
    )

    @Query("UPDATE chats SET unreadMentionsCount = :count WHERE id = :chatId AND unreadMentionsCount != :count")
    suspend fun updateUnreadMentions(
        chatId: Long,
        count: Int,
    )

    @Query("UPDATE chats SET unreadReactionsCount = :count WHERE id = :chatId AND unreadReactionsCount != :count")
    suspend fun updateUnreadReactions(
        chatId: Long,
        count: Int,
    )

    @Query(
        "UPDATE chats SET unreadMentionsCount = MAX(0, unreadMentionsCount + :delta) WHERE id = :chatId AND unreadMentionsCount != MAX(0, unreadMentionsCount + :delta)",
    )
    suspend fun addUnreadMentions(
        chatId: Long,
        delta: Int,
    )

    @Query(
        "UPDATE chats SET unreadReactionsCount = MAX(0, unreadReactionsCount + :delta) WHERE id = :chatId AND unreadReactionsCount != MAX(0, unreadReactionsCount + :delta)",
    )
    suspend fun addUnreadReactions(
        chatId: Long,
        delta: Int,
    )

    @Query("SELECT * FROM chats ORDER BY pinned DESC, pinnedOrder ASC, lastMessageDate DESC, id DESC")
    suspend fun observeAll(): List<ChatEntity>

    @Query(
        """
        SELECT * FROM chats
        WHERE `left` = 0 AND archived = 0
        ORDER BY pinned DESC, pinnedOrder ASC, lastMessageDate DESC, id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun mainListPage(
        limit: Int,
        offset: Int,
    ): List<ChatEntity>

    @Query(
        """
        SELECT * FROM chats
        WHERE `left` = 0 AND archived = 1
        ORDER BY pinned DESC, pinnedOrder ASC, lastMessageDate DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun archivePreview(limit: Int): List<ChatEntity>

    @Query(
        "SELECT * FROM chats WHERE lastMessageId IN (:ids) AND (:chatId IS NULL OR id = :chatId) AND (:chatId IS NOT NULL OR id > -1000000000000)",
    )
    suspend fun withLastMessageIds(
        chatId: Long?,
        ids: List<Int>,
    ): List<ChatEntity>

    @Query("SELECT COUNT(*) FROM chats WHERE `left` = 0 AND archived = 0")
    suspend fun mainListCount(): Int

    @Query(
        """
        SELECT * FROM chats
        WHERE `left` = 0 AND archived = :archived
        ORDER BY pinned ASC, pinnedOrder DESC, lastMessageDate ASC, id ASC
        LIMIT 1
        """,
    )
    suspend fun oldestListed(archived: Boolean): ChatEntity?

    @Query(
        """
        SELECT * FROM chats
        WHERE `left` = 0 AND archived = :archived AND (
            pinned < :pinned OR
            (pinned = :pinned AND pinnedOrder > :pinnedOrder) OR
            (pinned = :pinned AND pinnedOrder = :pinnedOrder AND
                (:date IS NOT NULL AND (lastMessageDate < :date OR lastMessageDate IS NULL))) OR
            (pinned = :pinned AND pinnedOrder = :pinnedOrder AND
                lastMessageDate IS :date AND id < :id)
        )
        ORDER BY pinned DESC, pinnedOrder ASC, lastMessageDate DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun afterCursor(
        archived: Boolean,
        pinned: Boolean,
        pinnedOrder: Int,
        date: Long?,
        id: Long,
        limit: Int,
    ): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): ChatEntity?

    @Query("SELECT * FROM chats WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ChatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chats: List<ChatEntity>)

    @Query("DELETE FROM chats")
    suspend fun clear()

    @Query("UPDATE chats SET dialogScrollMessageId = :messageId WHERE id = :chatId AND dialogScrollMessageId IS NOT :messageId")
    suspend fun updateDialogScroll(
        chatId: Long,
        messageId: Int,
    )

    @Query(
        "UPDATE chats SET peerStatus = :status, peerStatusAt = :at WHERE id = :chatId AND (peerStatus IS NOT :status OR peerStatusAt IS NOT :at)",
    )
    suspend fun updatePeerStatus(
        chatId: Long,
        status: String?,
        at: Long?,
    )

    @Query("UPDATE chats SET emojiStatusDocumentId = :documentId WHERE id = :chatId AND emojiStatusDocumentId IS NOT :documentId")
    suspend fun updateEmojiStatus(
        chatId: Long,
        documentId: Long?,
    )
}
