package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.monogram.data.db.model.TopicEntity

@Dao
interface TopicDao {
    @Query("SELECT * FROM topics WHERE chatId = :chatId ORDER BY `order` DESC")
    fun getTopicsForChat(chatId: Long): Flow<List<TopicEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTopic(topic: TopicEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTopics(topics: List<TopicEntity>)

    @Query("DELETE FROM topics WHERE chatId = :chatId AND id = :topicId")
    suspend fun deleteTopic(chatId: Long, topicId: Int)

    @Query("DELETE FROM topics WHERE chatId = :chatId")
    suspend fun clearTopicsForChat(chatId: Long)

    @Query("DELETE FROM topics")
    suspend fun clearAll()

    @Query("DELETE FROM topics WHERE createdAt < :timestamp")
    suspend fun deleteExpired(timestamp: Long)
}