package org.monogram.root

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduledQueueNavigationTest {
    @Test
    fun queueDestinationRetainsItsPeerAndDialogBackStackAcrossSerialization() {
        val dialog = RootComponent.Config.Dialog(7, threadTopMsgId = 99, isForum = true)
        val queue = RootComponent.Config.ScheduledQueue(7)
        val stack = listOf(RootComponent.Config.Home, dialog, queue)
        val serializer = ListSerializer(RootComponent.Config.serializer())
        val restored = Json.decodeFromString(serializer, Json.encodeToString(serializer, stack))
        assertEquals(stack, restored)
        assertEquals(dialog, restored.dropLast(1).last())
        assertEquals(stack, uniqueStack(restored, queue))
    }
}
