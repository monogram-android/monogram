package org.monogram.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.core.database.entity.ProfileCommonEntity
import org.monogram.core.database.entity.ProfileMediaEntity
import org.monogram.core.database.entity.ProfileMemberEntity
import org.monogram.core.database.entity.ProfileTabsEntity

@Dao
interface ProfileTabsDao {
    @Query("SELECT * FROM profile_tabs WHERE peerId = :peerId LIMIT 1")
    suspend fun get(peerId: Long): ProfileTabsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProfileTabsEntity)

    @Query("DELETE FROM profile_tabs WHERE peerId = :peerId")
    suspend fun delete(peerId: Long)

    @Query("DELETE FROM profile_tabs")
    suspend fun clear()
}

@Dao
interface ProfileMembersDao {
    @Query("SELECT * FROM profile_members WHERE peerId = :peerId ORDER BY position ASC")
    suspend fun list(peerId: Long): List<ProfileMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(members: List<ProfileMemberEntity>)

    @Query("DELETE FROM profile_members WHERE peerId = :peerId")
    suspend fun delete(peerId: Long)

    @Query("DELETE FROM profile_members")
    suspend fun clear()
}

@Dao
interface ProfileMediaDao {
    @Query("SELECT * FROM profile_media WHERE peerId = :peerId ORDER BY tab ASC, position ASC")
    suspend fun list(peerId: Long): List<ProfileMediaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ProfileMediaEntity>)

    @Query("DELETE FROM profile_media WHERE peerId = :peerId AND tab = :tab")
    suspend fun deleteTab(peerId: Long, tab: String)

    @Query("DELETE FROM profile_media")
    suspend fun clear()
}

@Dao
interface ProfileCommonDao {
    @Query("SELECT * FROM profile_common WHERE peerId = :peerId ORDER BY position ASC")
    suspend fun list(peerId: Long): List<ProfileCommonEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ProfileCommonEntity>)

    @Query("DELETE FROM profile_common WHERE peerId = :peerId")
    suspend fun delete(peerId: Long)

    @Query("DELETE FROM profile_common")
    suspend fun clear()
}
