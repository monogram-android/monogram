package org.monogram.data.db.model

import androidx.room.Entity

@Entity(
    tableName = "mtproto_secret_chat_state",
    primaryKeys = ["accountSlot", "environment", "chatId"],
)
data class MtProtoSecretChatStateEntity(
    val accountSlot: String,
    val environment: String,
    val chatId: Int,
    val accessHash: Long,
    val adminId: Long,
    val participantId: Long,
    val authKey: ByteArray,
    val keyFingerprint: Long,
    val maxInSeq: Int,
    val maxOutSeq: Int,
    /** In-flight re-key exchange id; 0 means no exchange is pending. */
    val exchangeId: Long = 0L,
    val futureAuthKey: ByteArray? = null,
    val futureKeyFingerprint: Long? = null,
    val keyCreateDateSeconds: Long? = null,
    val keyUseCountIn: Int = 0,
    val keyUseCountOut: Int = 0,
    val updatedAt: Long,
)
