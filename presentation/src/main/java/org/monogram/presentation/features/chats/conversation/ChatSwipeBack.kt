package org.monogram.presentation.features.chats.conversation

import org.monogram.presentation.core.ui.ScreenSwipeBackAction
import org.monogram.presentation.core.ui.ScreenSwipeBackPreview
import org.monogram.presentation.core.ui.ScreenSwipeBackState

internal fun resolveChatSwipeBackState(
    state: ChatComponent.State,
    hasTransientBlockingOverlay: Boolean = false,
): ScreenSwipeBackState {
    val isBlocked = isChatSwipeBackBlocked(state, hasTransientBlockingOverlay)
    return if (state.currentTopicId != null) {
        ScreenSwipeBackState(
            isSupported = true,
            isBlocked = isBlocked,
            action = ScreenSwipeBackAction.LocalChatTopicClose,
            preview = ScreenSwipeBackPreview.ChatForumList,
        )
    } else {
        ScreenSwipeBackState(
            isSupported = true,
            isBlocked = isBlocked,
            action = ScreenSwipeBackAction.StackPop,
            preview = ScreenSwipeBackPreview.PreviousStackEntry,
        )
    }
}

internal fun isChatSwipeBackBlocked(
    state: ChatComponent.State,
    hasTransientBlockingOverlay: Boolean = false,
): Boolean {
    return hasTransientBlockingOverlay ||
            state.fullScreenImages != null ||
            state.fullScreenVideoPath != null ||
            state.fullScreenVideoMessageId != null ||
            state.youtubeUrl != null ||
            state.instantViewUrl != null ||
            state.miniAppUrl != null ||
            state.webViewUrl != null
}
