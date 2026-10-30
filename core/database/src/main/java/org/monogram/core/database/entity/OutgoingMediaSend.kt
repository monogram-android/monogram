package org.monogram.core.database.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Relation
import org.monogram.core.database.RetainedMediaSource

@Entity(tableName = "outgoing_media_batches", primaryKeys = ["batchId"])
data class OutgoingMediaBatchEntity(
    val batchId: Long,
    val chatId: Long,
    val createdAt: Long,
    val silent: Boolean,
    val scheduleDate: Int?,
    val album: Boolean,
    val phase: OutgoingSendPhase = OutgoingSendPhase.READY,
)

// MessageEntity schema changes must also migrate the embedded media outbox columns.
@Entity(
    tableName = "outgoing_media_items", primaryKeys = ["randomId"],
    foreignKeys = [ForeignKey(
        entity = OutgoingMediaBatchEntity::class, parentColumns = ["batchId"],
        childColumns = ["batchId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["batchId", "position"], unique = true), Index(
        value = ["source_name"],
        unique = true
    )]
)
data class OutgoingMediaItemEntity(
    val randomId: Long, val batchId: Long, val position: Int, val mimeType: String,
    @Embedded(prefix = "source_") val source: RetainedMediaSource,
    @Embedded(prefix = "message_") val message: MessageEntity,
)

data class OutgoingMediaSend(
    @Embedded val batch: OutgoingMediaBatchEntity,
    @Relation(
        parentColumn = "batchId",
        entityColumn = "batchId"
    ) val items: List<OutgoingMediaItemEntity>,
) {
    fun requireValid() {
        require(batch.batchId != 0L && batch.chatId != 0L && batch.phase == OutgoingSendPhase.READY)
        require(batch.scheduleDate == null || (batch.scheduleDate > 0 && batch.scheduleDate != Int.MAX_VALUE))
        require(items.size in 1..10 && (batch.album || items.size == 1))
        require(items.singleOrNull { it.position == 0 }?.randomId == batch.batchId)
        require(items.map { it.randomId }
            .toSet().size == items.size && items.none { it.randomId == 0L })
        require(items.map { it.position }.sorted() == items.indices.toList())
        require(items.map { it.source.name }.toSet().size == items.size)
        require(items.map { it.message.id }.toSet().size == items.size)
        val first = items.first().message
        items.forEach { item ->
            val message = item.message
            require(item.batchId == batch.batchId && message.chatId == batch.chatId && message.randomId == item.randomId)
            require(message.id < 0 && message.pending && message.outgoing)
            require(message.mediaKind in setOf("photo", "video", "document"))
            require(message.checklistJson == null && message.replyMarkupJson == null && message.fwdFrom == null && message.fwdFromId == null)
            require(message.replyToMsgId == first.replyToMsgId && message.replyToTopId == first.replyToTopId && message.forumTopic == first.forumTopic)
            require(item.source.size > 0 && item.source.sha256.matches(Regex("[0-9a-f]{64}")))
            val id = item.source.name.removeSuffix(".bin")
            require(
                item.source.name.endsWith(".bin") && java.util.UUID.fromString(id).toString() == id
            )
        }
        if (batch.album) {
            val kinds = items.map { it.message.mediaKind }.toSet()
            require(kinds.all { it == "photo" || it == "video" } || kinds == setOf("document"))
        }
    }
}
