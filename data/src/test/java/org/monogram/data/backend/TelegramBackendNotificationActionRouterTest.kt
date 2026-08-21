package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.service.NotificationActionManager

class TelegramBackendNotificationActionRouterTest {
    @Test
    fun `selected MTProto suppresses stale notification actions without creating legacy manager`() {
        var created = false
        val router = TelegramBackendNotificationActionRouter(
            selectionStore = SelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = {
                created = true
                RecordingManager()
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertFalse(router.consumeNotificationAction("read", 7L, 11))
        router.clearHistory(7L)
        router.removeNotification(7L, 11)

        assertFalse(created)
    }

    @Test
    fun `legacy forwards notification actions and constructs manager on demand`() {
        var created = false
        val manager = RecordingManager()
        val router = TelegramBackendNotificationActionRouter(
            selectionStore = SelectionStore(TelegramBackendKind.LEGACY),
            legacyFactory = {
                created = true
                manager
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertFalse(created)
        assertTrue(router.consumeNotificationAction("dismiss", 7L, 11))
        router.clearHistory(7L)
        router.removeNotification(7L, 11)

        assertTrue(created)
        assertEquals(listOf("dismiss:7:11"), manager.actions)
        assertEquals(7L, manager.clearedChatId)
        assertEquals(7L to 11, manager.removedNotification)
    }

    private class RecordingManager : NotificationActionManager {
        val actions = mutableListOf<String>()
        var clearedChatId: Long? = null
        var removedNotification: Pair<Long, Int>? = null

        override fun consumeNotificationAction(action: String, chatId: Long, notificationId: Int): Boolean {
            actions += "$action:$chatId:$notificationId"
            return true
        }

        override fun clearHistory(chatId: Long) {
            clearedChatId = chatId
        }

        override fun removeNotification(chatId: Long, notificationId: Int) {
            removedNotification = chatId to notificationId
        }
    }

    private class SelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): StateFlow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) {
            state.value = backend
        }
        override suspend fun reset(accountId: String) {
            state.value = TelegramBackendKind.LEGACY
        }
    }
}
