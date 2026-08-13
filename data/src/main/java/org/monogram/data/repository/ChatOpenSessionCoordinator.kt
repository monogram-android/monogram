package org.monogram.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

internal class ChatOpenSessionCoordinator(
    private val scope: CoroutineScope,
    private val context: CoroutineContext,
    private val closeGraceMs: Long = DEFAULT_CLOSE_GRACE_MS,
    private val openRemote: suspend (chatId: Long, ownerTag: String, owners: Set<String>) -> Boolean,
    private val onOpened: (chatId: Long) -> Unit,
    private val closeRemote: suspend (chatId: Long) -> Unit,
    private val onClosed: (chatId: Long) -> Unit
) {
    private val mutex = Mutex()
    private val registry = ChatOpenOwnershipRegistry()
    private val pendingCloseJobs = mutableMapOf<Long, Job>()

    suspend fun open(chatId: Long, ownerTag: String): ChatOpenOwnershipChange = mutex.withLock {
        val retainedOpenChat = pendingCloseJobs.remove(chatId)?.let { closeJob ->
            closeJob.cancel()
            true
        } ?: false
        val change = registry.acquire(chatId, ownerTag)
        if (change.shouldOpen && !retainedOpenChat) {
            if (openRemote(chatId, ownerTag, change.owners)) {
                onOpened(chatId)
            } else {
                registry.release(chatId, ownerTag)
            }
        }
        change
    }

    suspend fun close(chatId: Long, ownerTag: String): ChatOpenOwnershipChange = mutex.withLock {
        val change = registry.release(chatId, ownerTag)
        if (change.shouldClose) {
            pendingCloseJobs.remove(chatId)?.cancel()
            pendingCloseJobs[chatId] = scope.launch(context) {
                delay(closeGraceMs)
                mutex.withLock {
                    pendingCloseJobs.remove(chatId)
                    if (!registry.hasOwners(chatId)) {
                        runCatching { closeRemote(chatId) }
                            .onSuccess { onClosed(chatId) }
                    }
                }
            }
        }
        change
    }

    internal suspend fun hasOwners(chatId: Long): Boolean = mutex.withLock {
        registry.hasOwners(chatId)
    }

    private companion object {
        const val DEFAULT_CLOSE_GRACE_MS = 750L
    }
}
