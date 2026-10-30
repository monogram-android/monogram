package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistTest {
    @Test
    fun parseNativeTodoJson() {
        val parsed = Checklists.parse(
            """{"t":"Shop","a":true,"c":true,"i":[{"id":1,"t":"Milk","d":1,"by":5,"at":9},{"id":2,"t":"Eggs"}]}""",
        )
        requireNotNull(parsed)
        assertEquals("Shop", parsed.title)
        assertTrue(parsed.othersCanAppend)
        assertTrue(parsed.othersCanComplete)
        assertEquals(2, parsed.items.size)
        assertTrue(parsed.items[0].done)
        assertEquals(5L, parsed.items[0].completedBy)
        assertFalse(parsed.items[1].done)
    }

    @Test
    fun serializeRoundTrip() {
        val source = Checklist(
            title = "Tasks",
            othersCanComplete = true,
            entities = listOf(TextEntity("italic", 0, 5)),
            items = listOf(
                ChecklistItem(
                    1,
                    "One",
                    done = true,
                    completedBy = 7L,
                    completedAt = 9L,
                    entities = listOf(TextEntity("bold", 0, 3))
                ), ChecklistItem(2, "Two")
            ),
        )
        val parsed = Checklists.parse(Checklists.serialize(source))
        requireNotNull(parsed)
        assertEquals(source.title, parsed.title)
        assertEquals(source.othersCanComplete, parsed.othersCanComplete)
        assertEquals(source.items.map { it.text }, parsed.items.map { it.text })
        assertTrue(parsed.items[0].done)
        assertFalse(parsed.items[1].done)
        assertEquals(source, parsed)
        val pending = ChecklistSendSpec("Tasks", listOf("One")).pendingChecklist()
        assertTrue(runCatching { ChecklistSendSpec.fromPending(pending.copy(entities = source.entities)) }.isFailure)
        assertTrue(runCatching {
            ChecklistSendSpec.fromPending(
                pending.copy(
                    items = listOf(
                        pending.items.single().copy(entities = source.items[0].entities)
                    )
                )
            )
        }.isFailure)
    }
}
