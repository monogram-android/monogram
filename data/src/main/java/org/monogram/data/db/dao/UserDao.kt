package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.UserEntity

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getUser(userId: Long): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Query("SELECT * FROM users")
    suspend fun getAllUsers(): List<UserEntity>

    @Query("SELECT * FROM users WHERE id IN (:userIds)")
    suspend fun getUsersByIds(userIds: List<Long>): List<UserEntity>

    @Query("DELETE FROM users WHERE id = :userId")
    suspend fun deleteUser(userId: Long)

    @Query("DELETE FROM users")
    suspend fun clearAll()

    @Query("DELETE FROM users WHERE createdAt < :timestamp")
    suspend fun deleteExpired(timestamp: Long)

    @Query("UPDATE users SET avatarPath = NULL, personalAvatarPath = NULL WHERE avatarPath IS NOT NULL OR personalAvatarPath IS NOT NULL")
    suspend fun clearAvatarPaths()
}
