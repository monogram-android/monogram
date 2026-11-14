package org.monogram.push

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.core.common.push.PushChannelKind
import org.monogram.core.common.push.PushPayload
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.PeerId
import java.util.WeakHashMap

/** Serializes a committed read with the final unread check and notification post. */
internal object NotificationReadGuard {
    private val locks = WeakHashMap<OfflineWarmup, Mutex>()

    suspend fun <T> withAccount(cache: OfflineWarmup, action: suspend () -> T): T {
        val lock = synchronized(locks) { locks.getOrPut(cache) { Mutex() } }
        return lock.withLock { action() }
    }

    fun appliesTo(payload: PushPayload): Boolean =
        !payload.scheduled && payload.channelKind in setOf(
            PushChannelKind.Private, PushChannelKind.Group, PushChannelKind.Channel,
        ) && listOf("MESSAGE_", "CHAT_MESSAGE_", "CHANNEL_MESSAGE_").any(payload.locKey::startsWith)

    suspend fun isRead(
        cache: OfflineWarmup,
        chatId: Long?,
        messageId: Int?,
        topicId: Int?
    ): Boolean {
        if (chatId == null || chatId == 0L || messageId == null || messageId <= 0) return false
        val peer = PeerId(chatId)
        val inbox = cache.chat(peer)?.readInboxMaxId ?: 0
        val discussion = topicId?.takeIf { it > 0 }?.let { cache.discussionReadMax(peer, it) } ?: 0
        return messageId <= maxOf(inbox, discussion)
    }
}
