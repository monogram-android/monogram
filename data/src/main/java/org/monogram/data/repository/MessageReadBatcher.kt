package org.monogram.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

internal class MessageReadBatcher(
    private val scope: CoroutineScope,
    private val context: CoroutineContext,
    private val flushDelayMs: Long = DEFAULT_FLUSH_DELAY_MS,
    private val flush: suspend (chatId: Long, threadId: Long?, messageIds: LongArray) -> Unit
) {
    private data class ReadRequest(
        val chatId: Long,
        val threadId: Long?,
        val messageIds: List<Long>
    )

    private val requests = Channel<ReadRequest>(Channel.UNLIMITED)

    init {
        scope.launch(context) {
            val pendingIdsByScope = mutableMapOf<Pair<Long, Long?>, LinkedHashSet<Long>>()
            for (first in requests) {
                val scopeKey = first.chatId to first.threadId
                pendingIdsByScope.getOrPut(scopeKey) { linkedSetOf() }.addAll(first.messageIds)
                while (true) {
                    val request = withTimeoutOrNull(flushDelayMs) {
                        requests.receive()
                    } ?: break
                    pendingIdsByScope.getOrPut(request.chatId to request.threadId) { linkedSetOf() }
                        .addAll(request.messageIds)
                }
                val batches = pendingIdsByScope.toMap()
                pendingIdsByScope.clear()
                batches.forEach { (scopeKey, ids) ->
                    ids.chunked(MAX_BATCH_SIZE).forEach { batch ->
                        flush(scopeKey.first, scopeKey.second, batch.toLongArray())
                    }
                }
            }
        }
    }

    suspend fun enqueue(chatId: Long, messageIds: Collection<Long>, threadId: Long? = null) {
        val validIds = messageIds.asSequence().filter { it != 0L }.toList()
        if (validIds.isEmpty()) return

        check(requests.trySend(ReadRequest(chatId, threadId, validIds)).isSuccess) {
            "Message read batcher is closed"
        }
    }

    private companion object {
        const val DEFAULT_FLUSH_DELAY_MS = 150L
        const val MAX_BATCH_SIZE = 100
    }
}
