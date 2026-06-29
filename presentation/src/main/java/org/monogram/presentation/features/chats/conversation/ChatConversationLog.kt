package org.monogram.presentation.features.chats.conversation

import android.os.Trace
import android.util.Log
import org.monogram.core.perf.ChatOpenPerfDebug
import org.monogram.presentation.BuildConfig
import org.monogram.presentation.features.chats.conversation.logic.effectiveThreadId
import java.util.concurrent.atomic.AtomicInteger

internal data class ConversationLoadSession(
    val sessionId: String,
    val source: String,
    val target: String,
    val startedAtMs: Long = android.os.SystemClock.elapsedRealtime()
)

internal data class ChatInitialLoadKey(
    val chatId: Long,
    val effectiveThreadId: Long?,
    val initialMessageId: Long?,
    val savedViewportAnchorMessageId: Long?,
    val firstUnreadMessageId: Long?,
    val rootMessageId: Long?
)

internal object ChatConversationLog {
    internal const val TAG = ChatOpenPerfDebug.TAG
    internal const val STREAM_VIEWPORT = "viewport"
    internal const val STREAM_PERF = ChatOpenPerfDebug.STREAM_PERF

    private val componentCounter = AtomicInteger(1)
    private val uiCounter = AtomicInteger(1)

    fun isEnabled(): Boolean = BuildConfig.DEBUG

    inline fun <T> trace(section: String, block: () -> T): T {
        if (!isEnabled()) return block()
        Trace.beginSection(section)
        return try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    suspend inline fun <T> traceSuspend(section: String, crossinline block: suspend () -> T): T {
        if (!isEnabled()) return block()
        Trace.beginSection(section)
        return try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    fun nextComponentInstanceId(chatId: Long): String {
        return "cmp${componentCounter.getAndIncrement()}@$chatId"
    }

    fun nextUiInstanceId(): String {
        return "ui${uiCounter.getAndIncrement()}"
    }

    fun logState(
        stream: String,
        event: String,
        state: ChatComponent.State,
        componentInstanceId: String? = null,
        uiInstanceId: String? = null,
        extra: String? = null
    ) {
        if (!isEnabled()) return
        Log.d(
            TAG,
            buildString {
                append("stream=").append(stream)
                append(" event=").append(event)
                append(" component=").append(componentInstanceId ?: "none")
                append(" ui=").append(uiInstanceId ?: "none")
                append(" chatId=").append(state.chatId)
                append(" threadId=").append(
                    state.currentMessageThreadId ?: state.currentTopicId ?: 0L
                )
                append(" viewport=").append(state.viewportPhase)
                append(" pending=").append(
                    state.pendingScrollCommand?.javaClass?.simpleName ?: "none"
                )
                append(" loading=").append(state.isLoading)
                append(" loadingOlder=").append(state.isLoadingOlder)
                append(" loadingNewer=").append(state.isLoadingNewer)
                append(" messages=").append(state.messages.size)
                append(" topics=").append(state.topics.size)
                append(" atBottom=").append(state.isAtBottom)
                append(" latestLoaded=").append(state.isLatestLoaded)
                append(" oldestLoaded=").append(state.isOldestLoaded)
                append(" scrollToMessageId=").append(state.scrollToMessageId ?: 0L)
                append(" savedAnchor=").append(state.lastSavedViewport?.anchorMessageId ?: 0L)
                append(" currentTopicId=").append(state.currentTopicId ?: 0L)
                append(" rootMessageId=").append(state.rootMessage?.id ?: 0L)
                if (!extra.isNullOrBlank()) {
                    append(" ").append(extra)
                }
            }
        )
    }

    fun logViewportState(
        event: String,
        state: ChatComponent.State,
        componentInstanceId: String? = null,
        uiInstanceId: String? = null,
        extra: String? = null
    ) {
        logState(
            stream = STREAM_VIEWPORT,
            event = event,
            state = state,
            componentInstanceId = componentInstanceId,
            uiInstanceId = uiInstanceId,
            extra = extra
        )
    }

    fun log(
        stream: String,
        chatId: Long,
        threadId: Long?,
        event: String,
        componentInstanceId: String? = null,
        uiInstanceId: String? = null,
        extra: String? = null
    ) {
        if (!isEnabled()) return
        Log.d(
            TAG,
            buildString {
                append("stream=").append(stream)
                append(" event=").append(event)
                append(" component=").append(componentInstanceId ?: "none")
                append(" ui=").append(uiInstanceId ?: "none")
                append(" chatId=").append(chatId)
                append(" threadId=").append(threadId ?: 0L)
                if (!extra.isNullOrBlank()) {
                    append(" ").append(extra)
                }
            }
        )
    }

    fun logViewport(
        chatId: Long,
        threadId: Long?,
        event: String,
        componentInstanceId: String? = null,
        uiInstanceId: String? = null,
        extra: String? = null
    ) {
        log(
            stream = STREAM_VIEWPORT,
            chatId = chatId,
            threadId = threadId,
            event = event,
            componentInstanceId = componentInstanceId,
            uiInstanceId = uiInstanceId,
            extra = extra
        )
    }

    fun logPerf(
        component: DefaultChatComponent,
        phase: String,
        source: String? = null,
        target: String? = null,
        anchorId: Long? = null,
        messagesBefore: Int? = null,
        messagesAfter: Int? = null,
        durationMs: Long? = null
    ) {
        if (!isEnabled()) return
        val state = component.state.value
        val session = component.activeLoadSession
        Log.d(
            TAG,
            ChatOpenPerfDebug.buildLogMessage(
                chatId = component.chatId,
                threadId = state.effectiveThreadId(),
                event = phase,
                componentInstanceId = component.componentInstanceId,
                source = source ?: session?.source,
                target = target ?: session?.target,
                anchorId = anchorId ?: state.lastSavedViewport?.anchorMessageId,
                messagesBefore = messagesBefore ?: state.messages.size,
                messagesAfter = messagesAfter ?: state.messages.size,
                unreadCount = state.unreadSeparatorCount,
                durationMs = durationMs
            )
        )
    }
}
