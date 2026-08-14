package org.monogram.presentation.features.chats.conversation

internal object ConversationViewportReducer {
    fun beginLoad(
        state: ChatComponent.State,
        legacyOwnsLoadingState: Boolean
    ): ChatComponent.State = initialize(
        state.copy(
            isLoading = if (legacyOwnsLoadingState) true else state.isLoading,
            isOldestLoaded = if (legacyOwnsLoadingState) false else state.isOldestLoaded,
            isLatestLoaded = if (legacyOwnsLoadingState) false else state.isLatestLoaded
        )
    )

    fun initialize(state: ChatComponent.State): ChatComponent.State = state.copy(
        pendingScrollCommand = null,
        viewportPhase = ChatViewportPhase.Initializing,
        highlightRequest = null
    )

    fun contentReady(
        state: ChatComponent.State,
        isAtBottom: Boolean,
        legacyOldestLoaded: Boolean? = null,
        legacyLatestLoaded: Boolean? = null
    ): ChatComponent.State = state.copy(
        isAtBottom = isAtBottom,
        isOldestLoaded = legacyOldestLoaded ?: state.isOldestLoaded,
        isLatestLoaded = legacyLatestLoaded ?: state.isLatestLoaded,
        scrollToMessageId = null,
        highlightRequest = null
    )

    fun restore(
        state: ChatComponent.State,
        command: ChatScrollCommand
    ): ChatComponent.State = state.copy(
        pendingScrollCommand = command,
        viewportPhase = ChatViewportPhase.Restoring,
        highlightRequest = null
    )

    fun enqueue(
        state: ChatComponent.State,
        command: ChatScrollCommand
    ): ChatComponent.State = state.copy(
        pendingScrollCommand = command,
        highlightRequest = null
    )

    fun consumeScrollCommand(state: ChatComponent.State): ChatComponent.State = state.copy(
        scrollToMessageId = null,
        pendingScrollCommand = null
    )

    fun settle(state: ChatComponent.State): ChatComponent.State =
        if (state.viewportPhase == ChatViewportPhase.Settled) state
        else state.copy(viewportPhase = ChatViewportPhase.Settled)

    fun requestHighlight(
        state: ChatComponent.State,
        messageId: Long
    ): ChatComponent.State {
        val token = state.highlightRequestToken + 1L
        return state.copy(
            highlightRequest = MessageHighlightRequest(messageId, token),
            highlightRequestToken = token
        )
    }

    fun consumeHighlight(state: ChatComponent.State): ChatComponent.State =
        if (state.highlightRequest == null) state else state.copy(highlightRequest = null)
}
