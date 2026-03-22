package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.UserFullInfoEntity

@Dao
interface UserFullInfoDao {
    @Query("SELECT * FROM user_full_info WHERE userId = :userId")
    suspend fun getUserFullInfo(userId: Long): UserFullInfoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserFullInfo(info: UserFullInfoEntity)

    @Query("DELETE FROM user_full_info WHERE userId = :userId")
    suspend fun deleteUserFullInfo(userId: Long)

    @Query("DELETE FROM user_full_info")
    suspend fun clearAll()

    @Query("DELETE FROM user_full_info WHERE createdAt < :timestamp")
    suspend fun deleteExpired(timestamp: Long)
}