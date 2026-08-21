package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.MtProtoStoryActiveListEntity
import org.monogram.data.db.model.MtProtoStoryListCursorEntity
import org.monogram.data.db.model.MtProtoStoryProjectionEntity

@Dao
interface MtProtoStoryProjectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStory(entity: MtProtoStoryProjectionEntity)

    @Query(
        "SELECT * FROM mtproto_story_projection WHERE accountSlot = :accountSlot " +
            "AND environment = :environment AND dcId = :dcId AND peerType = :peerType " +
            "AND peerId = :peerId AND storyId = :storyId LIMIT 1"
    )
    suspend fun getStory(
        accountSlot: String,
        environment: String,
        dcId: Int,
        peerType: String,
        peerId: Long,
        storyId: Int,
    ): MtProtoStoryProjectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertActiveList(entries: List<MtProtoStoryActiveListEntity>)

    @Query(
        "DELETE FROM mtproto_story_active_list WHERE accountSlot = :accountSlot " +
            "AND environment = :environment AND dcId = :dcId AND listType = :listType"
    )
    suspend fun clearActiveList(accountSlot: String, environment: String, dcId: Int, listType: String)

    @Query(
        "SELECT * FROM mtproto_story_active_list WHERE accountSlot = :accountSlot " +
            "AND environment = :environment AND dcId = :dcId AND listType = :listType " +
            "ORDER BY orderKey DESC, peerType ASC, peerId ASC, storyId ASC"
    )
    suspend fun getActiveList(
        accountSlot: String,
        environment: String,
        dcId: Int,
        listType: String,
    ): List<MtProtoStoryActiveListEntity>

    @Query(
        "UPDATE mtproto_story_active_list SET maxReadStoryId = :maxReadStoryId, updatedAt = :updatedAt " +
            "WHERE accountSlot = :accountSlot AND environment = :environment AND dcId = :dcId " +
            "AND peerType = :peerType AND peerId = :peerId"
    )
    suspend fun updateMaxReadStoryId(
        accountSlot: String,
        environment: String,
        dcId: Int,
        peerType: String,
        peerId: Long,
        maxReadStoryId: Int,
        updatedAt: Long,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(entity: MtProtoStoryListCursorEntity)

    @Query(
        "SELECT * FROM mtproto_story_list_cursor WHERE accountSlot = :accountSlot " +
            "AND environment = :environment AND dcId = :dcId AND listType = :listType LIMIT 1"
    )
    suspend fun getCursor(
        accountSlot: String,
        environment: String,
        dcId: Int,
        listType: String,
    ): MtProtoStoryListCursorEntity?

    @Query("DELETE FROM mtproto_story_projection WHERE accountSlot = :accountSlot AND environment = :environment")
    suspend fun deleteStoriesForAccount(accountSlot: String, environment: String)

    @Query("DELETE FROM mtproto_story_active_list WHERE accountSlot = :accountSlot AND environment = :environment")
    suspend fun deleteActiveListsForAccount(accountSlot: String, environment: String)

    @Query("DELETE FROM mtproto_story_list_cursor WHERE accountSlot = :accountSlot AND environment = :environment")
    suspend fun deleteCursorsForAccount(accountSlot: String, environment: String)
}
