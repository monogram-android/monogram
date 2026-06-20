package org.monogram.presentation.core.ui

import androidx.compose.runtime.Immutable

@Immutable
data class ScreenSwipeBackState(
    val isSupported: Boolean = false,
    val isBlocked: Boolean = false,
    val action: ScreenSwipeBackAction = ScreenSwipeBackAction.StackPop,
    val preview: ScreenSwipeBackPreview = ScreenSwipeBackPreview.PreviousStackEntry,
)

enum class ScreenSwipeBackAction {
    StackPop,
    LocalChatTopicClose,
    LocalChatListArchiveReturn,
}

enum class ScreenSwipeBackPreview {
    PreviousStackEntry,
    ChatForumList,
    ChatListFolder,
}
