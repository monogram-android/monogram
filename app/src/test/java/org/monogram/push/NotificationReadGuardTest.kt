package org.monogram.push

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.push.parsePushPayload
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.Chat
import org.monogram.core.models.PeerId

class NotificationReadGuardTest {
    private class Cache(var inbox: Int = 0, var topic: Int = 0) : OfflineWarmup() {
        override suspend fun chat(chatId: PeerId): Chat =
            Chat(chatId, "Chat", readInboxMaxId = inbox)

        override suspend fun discussionReadMax(chatId: PeerId, topId: Int): Int = topic
    }

    @Test
    fun checksAccountAndTopicCursorsWithoutSuppressingUnknownIds() = runBlocking {
        val first = Cache(inbox = 20, topic = 30)
        val second = Cache(inbox = 5)
        assertTrue(NotificationReadGuard.isRead(first, 1, 20, null))
        assertFalse(NotificationReadGuard.isRead(second, 1, 20, null))
        assertTrue(NotificationReadGuard.isRead(first, 1, 30, 7))
        assertFalse(NotificationReadGuard.isRead(first, 1, 30, null))
        assertFalse(NotificationReadGuard.isRead(first, 1, 0, 7))
        assertFalse(NotificationReadGuard.isRead(first, null, 20, 7))
    }

    @Test
    fun readCursorAppliesOnlyToUnscheduledMessageNotifications() {
        fun payload(key: String, scheduled: Boolean = false) = parsePushPayload(
            """{"loc_key":"$key","custom":{"from_id":1,"msg_id":20,"schedule":$scheduled}}""",
        )
        assertTrue(NotificationReadGuard.appliesTo(payload("MESSAGE_TEXT")))
        assertTrue(NotificationReadGuard.appliesTo(payload("CHAT_MESSAGE_PHOTO")))
        assertTrue(NotificationReadGuard.appliesTo(payload("CHANNEL_MESSAGE_TEXT")))
        assertFalse(NotificationReadGuard.appliesTo(payload("MESSAGE_TEXT", scheduled = true)))
        assertFalse(NotificationReadGuard.appliesTo(payload("MESSAGE_REACTION")))
        assertFalse(NotificationReadGuard.appliesTo(payload("STORY_NOTEXT")))
        assertFalse(NotificationReadGuard.appliesTo(payload("PHONE_CALL_REQUEST")))
        assertFalse(NotificationReadGuard.appliesTo(payload("AUTH_UNKNOWN")))
    }

    @Test
    fun committedReadIsVisibleBeforeWaitingPostAndAccountsAreIndependent() = runBlocking {
        val first = Cache()
        val second = Cache()
        val entered = CompletableDeferred<Unit>()
        val finishCommit = CompletableDeferred<Unit>()
        val commit = async(start = CoroutineStart.UNDISPATCHED) {
            NotificationReadGuard.withAccount(first) {
                entered.complete(Unit)
                finishCommit.await()
                first.inbox = 20
            }
        }
        entered.await()
        val post = async(start = CoroutineStart.UNDISPATCHED) {
            NotificationReadGuard.withAccount(first) {
                !NotificationReadGuard.isRead(first, 1, 20, null)
            }
        }
        assertFalse(post.isCompleted)
        assertTrue(NotificationReadGuard.withAccount(second) { true })
        finishCommit.complete(Unit)
        commit.await()
        assertFalse(post.await())
    }

    @Test
    fun delayedRepaintRechecksReadAfterInitialPost() = runBlocking {
        val cache = Cache()
        val initial = NotificationReadGuard.withAccount(cache) {
            !NotificationReadGuard.isRead(cache, 1, 20, null)
        }
        assertTrue(initial)
        NotificationReadGuard.withAccount(cache) { cache.inbox = 20 }
        val repaint = NotificationReadGuard.withAccount(cache) {
            !NotificationReadGuard.isRead(cache, 1, 20, null)
        }
        assertFalse(repaint)
    }

    @Test
    fun legacyCleanupTargetsOnlyUnownedNotificationChildrenOrSummary() {
        assertTrue(isLegacyNotification(0, true, false))
        assertTrue(isLegacyNotification(0, false, true))
        assertFalse(isLegacyNotification(123, true, false))
        assertFalse(isLegacyNotification(0, false, false))
    }
}
