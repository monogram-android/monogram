package org.monogram.core.perf

object ChatOpenPerfDebug {
    const val TAG = "ChatConversation"
    const val STREAM_PERF = "perf"

    fun buildLogMessage(
        chatId: Long,
        threadId: Long?,
        event: String,
        componentInstanceId: String? = null,
        uiInstanceId: String? = null,
        source: String? = null,
        target: String? = null,
        anchorId: Long? = null,
        messagesBefore: Int? = null,
        messagesAfter: Int? = null,
        unreadCount: Int? = null,
        requestCount: Int? = null,
        replyFetchCount: Int? = null,
        persistCount: Int? = null,
        persistSkippedCount: Int? = null,
        durationMs: Long? = null
    ): String {
        val session = ChatOpenPerfBridge.currentSession(chatId, threadId)
        return buildString {
            append("stream=").append(STREAM_PERF)
            append(" event=").append(event)
            append(" component=").append(componentInstanceId ?: "none")
            append(" ui=").append(uiInstanceId ?: "none")
            append(" chatId=").append(chatId)
            append(" threadId=").append(threadId ?: 0L)
            append(" sessionId=").append(session?.sessionId ?: "none")
            append(" source=").append(source ?: session?.source ?: "none")
            append(" target=").append(target ?: session?.target ?: "none")
            append(" messagesBefore=").append(messagesBefore ?: 0)
            append(" messagesAfter=").append(messagesAfter ?: 0)
            append(" anchorId=").append(anchorId ?: 0L)
            append(" unreadCount=").append(unreadCount ?: 0)
            append(" requestCount=").append(requestCount ?: session?.requestCount ?: 0)
            append(" replyFetchCount=").append(replyFetchCount ?: session?.replyFetchCount ?: 0)
            append(" persistCount=").append(persistCount ?: session?.persistCount ?: 0)
            append(" persistSkippedCount=").append(
                persistSkippedCount ?: session?.persistSkippedCount ?: 0
            )
            append(" durationMs=").append(durationMs ?: 0L)
        }
    }
}
