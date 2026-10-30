package org.monogram.core.database.entity

import androidx.room.Embedded
import androidx.room.Entity

// MessageEntity schema changes must also migrate the embedded outbox columns.
@Entity(tableName = "outgoing_text_sends", primaryKeys = ["randomId"])
data class OutgoingTextSendEntity(
    val randomId: Long,
    @Embedded(prefix = "message_") val message: MessageEntity,
    val silent: Boolean,
    val noWebpage: Boolean,
    val scheduleDate: Int?,
    val webpageUrl: String?,
    val phase: OutgoingSendPhase = OutgoingSendPhase.READY,
)

enum class OutgoingSendPhase { READY, IN_FLIGHT, UNCERTAIN, FAILED }
