package org.monogram.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.core.database.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

data class ChatReadState(
    val id: Long,
    val readInboxMaxId: Int,
    val unreadCount: Int,
    val unreadMentionsCount: Int? = null,
    val unreadReactionsCount: Int? = null,
)

@Dao
interface ChatDao {
    @Query("UPDATE chats SET readOutboxMaxId = MAX(readOutboxMaxId, :maxId) WHERE id = :chatId")
    suspend fun updateOutboxRead(chatId: Long, maxId: Int)
    @Query(
        "SELECT id, readInboxMaxId, unreadCount, unreadMentionsCount, unreadReactionsCount FROM chats",
    )
    fun observeReadStates(): Flow<List<ChatReadState>>

    @Query("UPDATE chats SET readInboxMaxId = :maxId, unreadCount = :unread WHERE id = :chatId AND readInboxMaxId <= :maxId")
    suspend fun updateInboxRead(chatId: Long, maxId: Int, unread: Int)

    @Query("UPDATE chats SET unreadMentionsCount = :count WHERE id = :chatId")
    suspend fun updateUnreadMentions(chatId: Long, count: Int)

    @Query("UPDATE chats SET unreadReactionsCount = :count WHERE id = :chatId")
    suspend fun updateUnreadReactions(chatId: Long, count: Int)

    @Query("UPDATE chats SET unreadMentionsCount = MAX(0, unreadMentionsCount + :delta) WHERE id = :chatId")
    suspend fun addUnreadMentions(chatId: Long, delta: Int)

    @Query("UPDATE chats SET unreadReactionsCount = MAX(0, unreadReactionsCount + :delta) WHERE id = :chatId")
    suspend fun addUnreadReactions(chatId: Long, delta: Int)

    @Query("SELECT * FROM chats ORDER BY pinned DESC, pinnedOrder ASC, lastMessageDate DESC")
    suspend fun observeAll(): List<ChatEntity>

    @Query(
        """
        SELECT * FROM chats
        WHERE `left` = 0 AND archived = 0
        ORDER BY pinned DESC, pinnedOrder ASC, lastMessageDate DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun mainListPage(limit: Int, offset: Int): List<ChatEntity>

    @Query(
        """
        SELECT * FROM chats
        WHERE `left` = 0 AND archived = 1
        ORDER BY lastMessageDate DESC
        LIMIT :limit
        """,
    )
    suspend fun archivePreview(limit: Int): List<ChatEntity>

    @Query("SELECT COUNT(*) FROM chats WHERE `left` = 0 AND archived = 0")
    suspend fun mainListCount(): Int

    @Query(
        """
        SELECT * FROM chats
        WHERE `left` = 0 AND archived = 0 AND id NOT IN (:excludeIds)
        ORDER BY pinned DESC, pinnedOrder ASC, lastMessageDate DESC
        LIMIT :limit
        """,
    )
    suspend fun mainListExcluding(excludeIds: List<Long>, limit: Int): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): ChatEntity?

    @Query("SELECT * FROM chats WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ChatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chats: List<ChatEntity>)

    @Query("DELETE FROM chats")
    suspend fun clear()

    @Query("UPDATE chats SET dialogScrollMessageId = :messageId WHERE id = :chatId")
    suspend fun updateDialogScroll(chatId: Long, messageId: Int)

    @Query("UPDATE chats SET peerStatus = :status, peerStatusAt = :at WHERE id = :chatId")
    suspend fun updatePeerStatus(chatId: Long, status: String?, at: Long?)

    @Query("UPDATE chats SET emojiStatusDocumentId = :documentId WHERE id = :chatId")
    suspend fun updateEmojiStatus(chatId: Long, documentId: Long?)
}
