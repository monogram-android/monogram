package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.MtProtoMessageProjectionEntity

@Dao
interface MtProtoMessageProjectionDao {
    @Query(
        "SELECT * FROM mtproto_message_projection " +
            "WHERE accountSlot = :accountSlot AND environment = :environment AND dcId = :dcId " +
            "AND peerType = :peerType AND peerId = :peerId AND messageId = :messageId LIMIT 1"
    )
    suspend fun get(
        accountSlot: String,
        environment: String,
        dcId: Int,
        peerType: String,
        peerId: Long,
        messageId: Int,
    ): MtProtoMessageProjectionEntity?

    @Query(
        "SELECT * FROM mtproto_message_projection " +
            "WHERE accountSlot = :accountSlot AND environment = :environment AND dcId = :dcId " +
            "AND peerType = :peerType AND peerId = :peerId ORDER BY date DESC, messageId DESC"
    )
    suspend fun getAll(
        accountSlot: String,
        environment: String,
        dcId: Int,
        peerType: String,
        peerId: Long,
    ): List<MtProtoMessageProjectionEntity>

    @Query(
        "SELECT * FROM mtproto_message_projection " +
            "WHERE accountSlot = :accountSlot AND environment = :environment AND dcId = :dcId " +
            "AND peerType = :peerType AND peerId = :peerId " +
            "AND (:beforeDate IS NULL OR date < :beforeDate " +
            "OR (date = :beforeDate AND messageId < :beforeMessageId)) " +
            "ORDER BY date DESC, messageId DESC LIMIT :limit"
    )
    suspend fun getPage(
        accountSlot: String,
        environment: String,
        dcId: Int,
        peerType: String,
        peerId: Long,
        beforeDate: Int?,
        beforeMessageId: Int?,
        limit: Int,
    ): List<MtProtoMessageProjectionEntity>

    @Query(
        "SELECT * FROM mtproto_message_projection " +
            "WHERE accountSlot = :accountSlot AND environment = :environment AND dcId = :dcId " +
            "AND isDeleted = 0 AND text IS NOT NULL AND lower(text) LIKE '%' || lower(:query) || '%' " +
            "ORDER BY date DESC, messageId DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun search(
        accountSlot: String,
        environment: String,
        dcId: Int,
        query: String,
        limit: Int,
        offset: Int,
    ): List<MtProtoMessageProjectionEntity>

    @Query(
        "SELECT * FROM mtproto_message_projection AS message " +
            "WHERE message.accountSlot = :accountSlot AND message.environment = :environment " +
            "AND message.dcId = :dcId AND NOT EXISTS (" +
            "SELECT 1 FROM mtproto_message_projection AS newer " +
            "WHERE newer.accountSlot = message.accountSlot AND newer.environment = message.environment " +
            "AND newer.dcId = message.dcId AND newer.peerType = message.peerType AND newer.peerId = message.peerId " +
            "AND (newer.date > message.date OR (newer.date = message.date AND newer.messageId > message.messageId))) " +
            "ORDER BY message.date DESC, message.messageId DESC, message.peerType ASC, message.peerId ASC"
    )
    suspend fun getLatestByPeer(
        accountSlot: String,
        environment: String,
        dcId: Int,
    ): List<MtProtoMessageProjectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MtProtoMessageProjectionEntity)

    @Query(
        "UPDATE mtproto_message_projection SET isDeleted = 1, updatedAt = :updatedAt " +
            "WHERE accountSlot = :accountSlot AND environment = :environment AND dcId = :dcId " +
            "AND peerType != 'CHANNEL' AND messageId IN (:messageIds)"
    )
    suspend fun markDeletedNonChannel(
        accountSlot: String,
        environment: String,
        dcId: Int,
        messageIds: List<Int>,
        updatedAt: Long,
    )

    @Query(
        "UPDATE mtproto_message_projection SET isDeleted = 1, updatedAt = :updatedAt " +
            "WHERE accountSlot = :accountSlot AND environment = :environment AND dcId = :dcId " +
            "AND peerType = 'CHANNEL' AND peerId = :peerId AND messageId IN (:messageIds)"
    )
    suspend fun markDeletedChannel(
        accountSlot: String,
        environment: String,
        dcId: Int,
        peerId: Long,
        messageIds: List<Int>,
        updatedAt: Long,
    )

    @Query("DELETE FROM mtproto_message_projection WHERE accountSlot = :accountSlot AND environment = :environment")
    suspend fun deleteAccount(accountSlot: String, environment: String)
}
