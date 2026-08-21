package org.monogram.data.db.model

import androidx.room.Entity

/** Bounded cache coverage for one chat history scope. Missing rows mean unknown coverage. */
@Entity(
    tableName = "message_windows",
    primaryKeys = ["chatId", "scopeType", "scopeId"]
)
data class MessageWindowEntity(
    val chatId: Long,
    val scopeType: String,
    val scopeId: Long,
    val oldestMessageId: Long?,
    val newestMessageId: Long?,
    val olderBoundaryReached: Boolean,
    val newerBoundaryReached: Boolean,
    val lastNetworkSyncAt: Long,
    val generation: Long,
    val protectedMessageId: Long? = null
)
