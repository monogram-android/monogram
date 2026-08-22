package org.monogram.data.db.model

import androidx.room.Entity

@Entity(
    tableName = "mtproto_update_state",
    primaryKeys = ["accountSlot", "environment", "dcId"],
)
data class MtProtoUpdateStateEntity(
    val accountSlot: String,
    val environment: String,
    val dcId: Int,
    val pts: Int,
    val qts: Int,
    val date: Int,
    val seq: Int,
    /** Reserved for channel-scoped cursors once channel update mapping is wired. */
    val channelPtsData: String? = null,
)
