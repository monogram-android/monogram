package org.monogram.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.monogram.core.database.entity.OutgoingSendPhase
import org.monogram.core.database.entity.OutgoingTextSendEntity

@Dao
abstract class OutgoingTextSendDao {
    @Query("SELECT * FROM outgoing_text_sends ORDER BY message_date, randomId")
    abstract fun observeAll(): Flow<List<OutgoingTextSendEntity>>

    @Query("SELECT * FROM outgoing_text_sends ORDER BY message_date, randomId")
    abstract suspend fun all(): List<OutgoingTextSendEntity>

    @Query("SELECT * FROM outgoing_text_sends WHERE message_chatId = :chatId ORDER BY message_date, randomId")
    abstract fun observe(chatId: Long): Flow<List<OutgoingTextSendEntity>>

    @Query("SELECT * FROM outgoing_text_sends WHERE randomId = :randomId")
    abstract suspend fun get(randomId: Long): OutgoingTextSendEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insert(row: OutgoingTextSendEntity)

    @Transaction
    open suspend fun enqueue(row: OutgoingTextSendEntity) {
        require(row.randomId != 0L && row.message.randomId == row.randomId)
        require(row.message.chatId != 0L && row.message.id < 0 && row.message.pending && row.message.outgoing)
        require(row.phase == OutgoingSendPhase.READY)
        if (row.message.mediaKind == "geo") {
            val place = org.monogram.core.database.decodeLocationSend(row.message.fileName)
            require(
                row.message.text.isNullOrBlank() && row.message.entitiesJson.isNullOrBlank() &&
                        row.webpageUrl == null && !row.noWebpage && row.message.mediaCacheKey == null && (!place.live || row.scheduleDate == null)
            )
        } else if (row.message.mediaKind == "contact") {
            val spec = org.monogram.core.database.decodeContactSend(row.message.fileName)
            require(
                row.message.text == spec.displayName && row.message.entitiesJson.isNullOrBlank() &&
                        row.webpageUrl == null && !row.noWebpage && row.message.mediaCacheKey == null
            )
        } else if (row.message.mediaKind == "todo") {
            val list =
                requireNotNull(org.monogram.core.models.Checklists.parse(row.message.checklistJson))
            val spec = org.monogram.core.models.ChecklistSendSpec.fromPending(list)
            require(
                row.message.text == spec.title && row.message.entitiesJson.isNullOrBlank() &&
                        row.message.fileName == null && row.webpageUrl == null && !row.noWebpage && row.message.mediaCacheKey == null
            )
        } else if (row.message.mediaKind == "poll") {
            val spec = org.monogram.core.database.decodePollSend(row.message.fileName)
            require(
                row.message.text == spec.question && row.message.entitiesJson.isNullOrBlank() &&
                        row.webpageUrl == null && !row.noWebpage && row.message.mediaCacheKey == null
            )
        } else if (row.message.mediaKind == "dice") {
            val dice = org.monogram.core.models.ServiceMedia.dice(row.message.fileName)
            require(dice != null && dice.value == 0 && dice.emoticon in org.monogram.core.models.Dice.supportedEmoticons)
            require(
                row.message.text.isNullOrBlank() && row.message.entitiesJson.isNullOrBlank() &&
                        row.webpageUrl == null && !row.noWebpage && row.message.mediaCacheKey == null
            )
        } else {
            require(row.message.mediaKind == null || (row.message.mediaKind == "webpage" && !row.webpageUrl.isNullOrBlank()))
        }
        require((row.message.mediaKind == "todo" || row.message.checklistJson == null) && row.message.replyMarkupJson == null && row.message.groupedId == null)
        require(row.message.fwdFrom == null && row.message.fwdFromId == null)
        require(row.scheduleDate == null || (row.scheduleDate > 0 && row.scheduleDate != Int.MAX_VALUE))
        val previous = get(row.randomId)
        if (previous != null) {
            require(previous.copy(phase = row.phase) == row)
            return
        }
        insert(row)
    }

    @Query("UPDATE outgoing_text_sends SET phase = 'IN_FLIGHT' WHERE randomId = :randomId AND phase = 'READY'")
    abstract suspend fun claim(randomId: Long): Int

    @Query("UPDATE outgoing_text_sends SET phase = 'UNCERTAIN' WHERE phase = 'IN_FLIGHT'")
    abstract suspend fun recoverInterrupted(): Int

    @Query("UPDATE outgoing_text_sends SET phase = 'UNCERTAIN' WHERE randomId = :randomId AND phase = 'IN_FLIGHT'")
    abstract suspend fun markUncertain(randomId: Long): Int

    @Query("UPDATE outgoing_text_sends SET phase = 'FAILED' WHERE randomId = :randomId AND phase = 'IN_FLIGHT'")
    abstract suspend fun markFailed(randomId: Long): Int

    @Query("UPDATE outgoing_text_sends SET phase = 'READY' WHERE randomId = :randomId AND phase IN ('UNCERTAIN', 'FAILED')")
    abstract suspend fun retry(randomId: Long): Int

    @Query("DELETE FROM outgoing_text_sends WHERE randomId = :randomId AND message_chatId = :chatId")
    protected abstract suspend fun deleteMatched(randomId: Long, chatId: Long)

    @Transaction
    open suspend fun acknowledge(randomId: Long, chatId: Long) {
        val row = get(randomId) ?: return
        require(row.message.chatId == chatId)
        deleteMatched(randomId, chatId)
    }

    @Query("DELETE FROM outgoing_text_sends")
    abstract suspend fun clear()
}
