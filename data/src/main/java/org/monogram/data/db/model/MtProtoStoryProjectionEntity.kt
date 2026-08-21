package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.Index

/** Canonical story payloads are retained so display fields are never reconstructed from guesses. */
@Entity(
    tableName = "mtproto_story_projection",
    primaryKeys = ["accountSlot", "environment", "dcId", "peerType", "peerId", "storyId"],
    indices = [
        Index(value = ["accountSlot", "environment", "dcId", "peerType", "peerId", "storyId"]),
    ],
)
data class MtProtoStoryProjectionEntity(
    val accountSlot: String,
    val environment: String,
    val dcId: Int,
    val peerType: String,
    val peerId: Long,
    val storyId: Int,
    val payload: ByteArray,
    val isDeleted: Boolean,
    val updatedAt: Long,
)

@Entity(
    tableName = "mtproto_story_active_list",
    primaryKeys = ["accountSlot", "environment", "dcId", "listType", "peerType", "peerId", "storyId"],
    indices = [
        Index(value = ["accountSlot", "environment", "dcId", "listType", "orderKey"]),
    ],
)
data class MtProtoStoryActiveListEntity(
    val accountSlot: String,
    val environment: String,
    val dcId: Int,
    val listType: String,
    val peerType: String,
    val peerId: Long,
    val storyId: Int,
    val orderKey: Long,
    val canBeArchived: Boolean,
    val maxReadStoryId: Int,
    val updatedAt: Long,
)

@Entity(
    tableName = "mtproto_story_list_cursor",
    primaryKeys = ["accountSlot", "environment", "dcId", "listType"],
)
data class MtProtoStoryListCursorEntity(
    val accountSlot: String,
    val environment: String,
    val dcId: Int,
    val listType: String,
    val state: String,
    val hasMore: Boolean,
    val totalCount: Int,
    val updatedAt: Long,
)
