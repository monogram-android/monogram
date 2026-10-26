package org.monogram.feature.dialog

import org.monogram.core.models.Checklists
import org.monogram.core.models.CompactJson
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.TextEntities
import org.monogram.core.models.TextEntity

internal data class ChecklistEditState(
    val chatId: Long,
    val threadId: Int,
    val messageId: Int,
    val date: Long,
    val checklistJson: String,
    val itemId: Int?,
    val draft: String,
    val entitiesJson: String?,
    val markdown: Boolean,
) {
    fun message(chat: PeerId, thread: Int): Message? {
        if (chat.value != chatId || thread != threadId || messageId <= 0) return null
        val checklist = Checklists.parse(checklistJson) ?: return null
        if (itemId != null && checklist.items.count { it.id == itemId } != 1) return null
        return Message(
            MessageId(chat, messageId),
            null,
            null,
            date,
            outgoing = true,
            mediaKind = "todo",
            checklist = checklist
        )
    }

    fun encode(): String {
        fun quote(value: String?) = value?.let { "\"${CompactJson.escape(it)}\"" } ?: "null"
        return "{\"v\":1,\"chat\":$chatId,\"thread\":$threadId,\"id\":$messageId,\"date\":$date," + "\"list\":${
            quote(
                checklistJson
            )
        },\"item\":$itemId,\"text\":${quote(draft)},\"entities\":${
            quote(
                entitiesJson
            )
        },\"markdown\":$markdown}"
    }

    companion object {
        fun decode(raw: String?): ChecklistEditState? {
            val row = CompactJson.parse(raw.orEmpty()) as? Map<*, *> ?: return null
            if ((row["v"] as? Number)?.toInt() != 1) return null
            return ChecklistEditState(
                (row["chat"] as? Number)?.toLong() ?: return null,
                (row["thread"] as? Number)?.toInt() ?: return null,
                (row["id"] as? Number)?.toInt() ?: return null,
                (row["date"] as? Number)?.toLong() ?: return null,
                row["list"] as? String ?: return null,
                (row["item"] as? Number)?.toInt(),
                row["text"] as? String ?: return null,
                row["entities"] as? String,
                row["markdown"] == true,
            )
        }

        fun from(state: DialogStore.State): ChecklistEditState? {
            val message = state.editing ?: return null
            if (!message.outgoing || message.pending || message.id.chatId != state.chatId || message.id.id <= 0) return null
            val checklist = Checklists.serialize(message.checklist) ?: return null
            return ChecklistEditState(
                state.chatId.value,
                state.threadTopId,
                message.id.id,
                message.date,
                checklist,
                state.editingChecklistItemId,
                state.draft,
                TextEntities.serialize(state.checklistEditEntities()),
                state.draftFormatting
            )
        }
    }
}

internal fun DialogStore.State.checklistEditEntities(): List<TextEntity> =
    (draftEntities + draftMentions.map {
        TextEntity(
            "mention_name", it.start, it.length, it.peerId.value.toString()
        )
    }).distinct().sortedWith(compareBy<TextEntity> { it.offset }.thenByDescending { it.length })
