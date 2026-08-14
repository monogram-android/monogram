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
    val persistSkippedCount: Int,
    val firstContentLatencyMs: Long? = null,
    val settledLatencyMs: Long? = null,
    val shadowMismatchCount: Int = 0
)

data class ChatOpenPerfReportSnapshot(
    val completedSessions: List<ChatOpenPerfSessionSnapshot>
) {
    val sessionCount: Int get() = completedSessions.size
    val shadowMismatchCount: Int get() = completedSessions.sumOf { it.shadowMismatchCount }
}

object ChatOpenPerfBridge {
    private data class SessionRecord(
        val sessionId: String,
        var source: String,
        var target: String,
        var requestCount: Int = 0,
        var replyFetchCount: Int = 0,
        var persistCount: Int = 0,
        var persistSkippedCount: Int = 0,
        val startedAtMs: Long = nowMs(),
        var firstContentLatencyMs: Long? = null,
        var settledLatencyMs: Long? = null,
        var shadowMismatchCount: Int = 0
    )

    private val sessions = ConcurrentHashMap<String, SessionRecord>()
    private val completed = ArrayDeque<ChatOpenPerfSessionSnapshot>()

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

    fun markFirstContent(chatId: Long, threadId: Long?): ChatOpenPerfSessionSnapshot? =
        markSession(chatId, threadId) { record ->
            if (record.firstContentLatencyMs == null) {
                record.firstContentLatencyMs = elapsedSince(record.startedAtMs)
            }
        }

    fun markSettled(chatId: Long, threadId: Long?): ChatOpenPerfSessionSnapshot? =
        markSession(chatId, threadId) { record ->
            if (record.settledLatencyMs == null) {
                record.settledLatencyMs = elapsedSince(record.startedAtMs)
            }
        }

    fun recordShadowMismatch(chatId: Long, threadId: Long?): ChatOpenPerfSessionSnapshot? =
        markSession(chatId, threadId) { it.shadowMismatchCount += 1 }

    /** Returns a bounded process-local report for device/reference-corpus harnesses. */
    fun report(): ChatOpenPerfReportSnapshot = synchronized(completed) {
        ChatOpenPerfReportSnapshot(completed.toList())
    }

    fun resetReport() = synchronized(completed) { completed.clear() }

    fun clearSession(chatId: Long, threadId: Long?, sessionId: String? = null) {
        val key = key(chatId, threadId)
        val record = sessions[key] ?: return
        if (sessionId == null || record.sessionId == sessionId) {
            if (sessions.remove(key, record)) {
                synchronized(record) {
                    synchronized(completed) {
                        if (completed.size == MAX_COMPLETED_SESSIONS) completed.removeFirst()
                        completed.addLast(record.snapshot())
                    }
                }
            }
        }
    }

    private fun markSession(
        chatId: Long,
        threadId: Long?,
        block: (SessionRecord) -> Unit
    ): ChatOpenPerfSessionSnapshot? {
        val record = findRecord(chatId, threadId) ?: return null
        synchronized(record) {
            block(record)
            return record.snapshot()
        }
    }

    private fun elapsedSince(startedAtMs: Long): Long = (nowMs() - startedAtMs).coerceAtLeast(0L)

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

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
            persistSkippedCount = persistSkippedCount,
            firstContentLatencyMs = firstContentLatencyMs,
            settledLatencyMs = settledLatencyMs,
            shadowMismatchCount = shadowMismatchCount
        )
    }

    private const val MAX_COMPLETED_SESSIONS = 256
}
