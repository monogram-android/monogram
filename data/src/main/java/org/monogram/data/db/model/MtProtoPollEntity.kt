package org.monogram.data.db.model

import androidx.room.Entity

@Entity(
    tableName = "mtproto_poll",
    primaryKeys = ["accountSlot", "environment", "pollId"],
)
data class MtProtoPollEntity(
    val accountSlot: String,
    val environment: String,
    val pollId: Long,
    val question: String,
    /** JSON array of option labels in server order. */
    val optionsJson: String,
    val totalVoters: Int,
    val isClosed: Boolean,
    val isAnonymous: Boolean,
    val updatedAt: Long,
)
