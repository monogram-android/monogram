package org.monogram.core.perf

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ChatOpenPerfSessionSnapshot(
    val sessionId: String,
    val source: String,
    val target: String,
    val requestCount: Int,
    val replyFetchCount: Int,
    val persistCount: Int,
    val persistSkippedCount: Int
)

object ChatOpenPerfBridge {
    private data class SessionRecord(
        val sessionId: String,
        var source: String,
        var target: String,
        var requestCount: Int = 0,
        var replyFetchCount: Int = 0,
        var persistCount: Int = 0,
        var persistSkippedCount: Int = 0
    )

    private val sessions = ConcurrentHashMap<String, SessionRecord>()

    fun startSession(
        chatId: Long,
        threadId: Long?,
        source: String,
        target: String
    ): ChatOpenPerfSessionSnapshot {
        val record = SessionRecord(
            sessionId = UUID.randomUUID().toString().take(8),
            source = source,
            target = target
        )
        sessions[key(chatId, threadId)] = record
        return record.snapshot()
    }

    fun updateTarget(chatId: Long, threadId: Long?, target: String): ChatOpenPerfSessionSnapshot? {
        val record = findRecord(chatId, threadId) ?: return null
        synchronized(record) {
            record.target = target
            return record.snapshot()
        }
    }

    fun currentSession(chatId: Long, threadId: Long?): ChatOpenPerfSessionSnapshot? {
        val record = findRecord(chatId, threadId) ?: return null
        synchronized(record) {
            return record.snapshot()
        }
    }

    fun recordHistoryRequest(chatId: Long, threadId: Long?): ChatOpenPerfSessionSnapshot? {
        val record = findRecord(chatId, threadId) ?: return null
        synchronized(record) {
            record.requestCount += 1
            return record.snapshot()
        }
    }

    fun recordReplyFetch(chatId: Long, threadId: Long?): ChatOpenPerfSessionSnapshot? {
        val record = findRecord(chatId, threadId) ?: return null
        synchronized(record) {
            record.requestCount += 1
            record.replyFetchCount += 1
            return record.snapshot()
        }
    }

    fun recordPersist(
        chatId: Long,
        threadId: Long?,
        persistedCount: Int,
        skippedCount: Int
    ): ChatOpenPerfSessionSnapshot? {
        val record = findRecord(chatId, threadId) ?: return null
        synchronized(record) {
            record.persistCount += persistedCount
            record.persistSkippedCount += skippedCount
            return record.snapshot()
        }
    }

    fun clearSession(chatId: Long, threadId: Long?, sessionId: String? = null) {
        val key = key(chatId, threadId)
        val record = sessions[key] ?: return
        if (sessionId == null || record.sessionId == sessionId) {
            sessions.remove(key, record)
        }
    }

    private fun findRecord(chatId: Long, threadId: Long?): SessionRecord? {
        sessions[key(chatId, threadId)]?.let { return it }
        return sessions.entries.firstOrNull { entry ->
            entry.key.startsWith("$chatId:")
        }?.value
    }

    private fun key(chatId: Long, threadId: Long?): String = "$chatId:${threadId ?: 0L}"

    private fun SessionRecord.snapshot(): ChatOpenPerfSessionSnapshot {
        return ChatOpenPerfSessionSnapshot(
            sessionId = sessionId,
            source = source,
            target = target,
            requestCount = requestCount,
            replyFetchCount = replyFetchCount,
            persistCount = persistCount,
            persistSkippedCount = persistSkippedCount
        )
    }
}
