package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceMessageTest {
    @Test
    fun parseSplitsKindActorExtra() {
        val parts = parseServiceMessage("add\u001FAda\u001FBob, Carol")
        assertEquals("add", parts?.kind)
        assertEquals("Ada", parts?.actor)
        assertEquals("Bob, Carol", parts?.extra)
        assertNull(parseServiceMessage("plain text"))
    }

    @Test
    fun formatUsesCatalogTemplates() {
        val catalog: (String) -> String = { kind ->
            when (kind) {
                "pin" -> "%1\$s pinned a message"
                "add" -> "%1\$s invited %2\$s"
                else -> "Service message"
            }
        }
        assertEquals(
            "Ada pinned a message",
            formatServiceMessage("pin\u001FAda\u001F", "You", false, "Someone", catalog),
        )
        assertEquals(
            "You invited Bob",
            formatServiceMessage("add\u001FAda\u001FBob", "You", true, "Someone", catalog),
        )
    }
}
