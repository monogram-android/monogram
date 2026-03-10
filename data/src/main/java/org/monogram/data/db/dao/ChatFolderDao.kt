package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.monogram.data.db.model.ChatFolderEntity

@Dao
interface ChatFolderDao {
    @Query("SELECT * FROM chat_folders ORDER BY `order` ASC")
    fun getChatFolders(): Flow<List<ChatFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatFolders(folders: List<ChatFolderEntity>)

    @Query("DELETE FROM chat_folders")
    suspend fun clearAll()
}
