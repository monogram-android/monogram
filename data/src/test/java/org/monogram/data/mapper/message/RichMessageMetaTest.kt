package org.monogram.data.mapper.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType

class RichMessageMetaTest {

    @Test
    fun `checklist payload roundtrips`() {
        val payload = ChecklistPayload(
            titleEntities = listOf(MessageEntity(0, 5, MessageEntityType.Bold)),
            tasks = listOf(
                ChecklistTaskPayload(
                    id = 1,
                    text = "Task",
                    completedById = 42L,
                    completedByName = "Alice",
                    completionDate = 123
                )
            ),
            othersCanAddTasks = true,
            canAddTasks = true,
            othersCanMarkTasksAsDone = false,
            canMarkTasksAsDone = true
        )

        assertEquals(payload, decodeChecklistPayload(payload.encode()))
    }

    @Test
    fun `paid media payload roundtrips`() {
        val payload = PaidMediaPayload(
            starCount = 99L,
            caption = "Caption",
            entities = listOf(MessageEntity(0, 7, MessageEntityType.Italic)),
            showCaptionAboveMedia = true,
            items = listOf(PaidMediaItemPayload.Preview(10, 20, 30))
        )

        assertEquals(payload, decodePaidMediaPayload(payload.encode()))
    }

    @Test
    fun `legacy strings are ignored`() {
        assertNull(decodeChecklistPayload("1|2|3"))
        assertNull(decodePaidMediaPayload("1|2|3"))
    }
}
