package org.monogram.app.components

import androidx.compose.runtime.Immutable
import org.monogram.presentation.core.ui.ScreenSwipeBackAction
import org.monogram.presentation.core.ui.ScreenSwipeBackPreview
import org.monogram.presentation.core.ui.ScreenSwipeBackState
import org.monogram.presentation.features.chats.list.ChatListPreviewMode
import org.monogram.presentation.features.chats.list.resolveArchiveReturnFolderId
import org.monogram.presentation.root.RootComponent

@Immutable
internal data class SwipeBackResolution(
    val isSupported: Boolean,
    val isBlocked: Boolean,
    val action: ScreenSwipeBackAction = ScreenSwipeBackAction.StackPop,
    val preview: SwipeBackPreviewDescriptor = SwipeBackPreviewDescriptor.PreviousStackEntry,
)

internal sealed interface SwipeBackPreviewDescriptor {
    data object PreviousStackEntry : SwipeBackPreviewDescriptor
    data object ChatForumList : SwipeBackPreviewDescriptor
    data class ChatListFolder(val folderId: Int) : SwipeBackPreviewDescriptor
}

internal fun resolveSwipeBack(
    child: RootComponent.Child,
    screenSwipeBackState: ScreenSwipeBackState = ScreenSwipeBackState(),
    archivePreviewFolderId: Int? = null,
): SwipeBackResolution {
    return when (child) {
        is RootComponent.Child.ChatDetailChild -> {
            SwipeBackResolution(
                isSupported = screenSwipeBackState.isSupported,
                isBlocked = screenSwipeBackState.isBlocked,
                action = screenSwipeBackState.action,
                preview = when (screenSwipeBackState.preview) {
                    ScreenSwipeBackPreview.ChatForumList -> SwipeBackPreviewDescriptor.ChatForumList
                    ScreenSwipeBackPreview.ChatListFolder -> {
                        archivePreviewFolderId?.let(SwipeBackPreviewDescriptor::ChatListFolder)
                            ?: SwipeBackPreviewDescriptor.PreviousStackEntry
                    }

                    ScreenSwipeBackPreview.PreviousStackEntry -> SwipeBackPreviewDescriptor.PreviousStackEntry
                },
            )
        }

        is RootComponent.Child.ChatsChild -> {
            SwipeBackResolution(
                isSupported = screenSwipeBackState.isSupported,
                isBlocked = screenSwipeBackState.isBlocked,
                action = screenSwipeBackState.action,
                preview = when (screenSwipeBackState.preview) {
                    ScreenSwipeBackPreview.ChatListFolder -> {
                        archivePreviewFolderId?.let(SwipeBackPreviewDescriptor::ChatListFolder)
                            ?: SwipeBackPreviewDescriptor.PreviousStackEntry
                    }

                    ScreenSwipeBackPreview.ChatForumList -> SwipeBackPreviewDescriptor.ChatForumList
                    ScreenSwipeBackPreview.PreviousStackEntry -> SwipeBackPreviewDescriptor.PreviousStackEntry
                },
            )
        }

        else -> SwipeBackResolution(
            isSupported = isGenericSwipeBackSupported(child),
            isBlocked = false,
        )
    }
}

internal fun resolveArchivePreviewMode(
    child: RootComponent.Child,
    showAllChatsFolder: Boolean,
): ChatListPreviewMode {
    val chatsChild = child as? RootComponent.Child.ChatsChild ?: return ChatListPreviewMode.Active
    val state = chatsChild.component.state.value
    val targetFolderId = resolveArchiveReturnFolderId(
        folders = state.folders,
        showAllChatsFolder = showAllChatsFolder,
        lastNonArchiveFolderId = state.lastNonArchiveFolderId,
    )
    return if (state.selectedFolderId == -2 && targetFolderId != -2) {
        ChatListPreviewMode.FolderPreview(targetFolderId)
    } else {
        ChatListPreviewMode.Active
    }
}

private fun isGenericSwipeBackSupported(child: RootComponent.Child): Boolean =
    when (child) {
        is RootComponent.Child.ChatDetailChild,
        is RootComponent.Child.ProfileChild,
        is RootComponent.Child.SettingsChild,
        is RootComponent.Child.EditProfileChild,
        is RootComponent.Child.SessionsChild,
        is RootComponent.Child.FoldersChild,
        is RootComponent.Child.ChatSettingsChild,
        is RootComponent.Child.DataStorageChild,
        is RootComponent.Child.StorageUsageChild,
        is RootComponent.Child.NetworkUsageChild,
        is RootComponent.Child.PremiumChild,
        is RootComponent.Child.PrivacyChild,
        is RootComponent.Child.AdBlockChild,
        is RootComponent.Child.PowerSavingChild,
        is RootComponent.Child.NotificationsChild,
        is RootComponent.Child.ProxyChild,
        is RootComponent.Child.ProfileLogsChild,
        is RootComponent.Child.AdminManageChild,
        is RootComponent.Child.ChatEditChild,
        is RootComponent.Child.MemberListChild,
        is RootComponent.Child.ChatPermissionsChild,
        is RootComponent.Child.StickersChild,
        is RootComponent.Child.AboutChild,
        is RootComponent.Child.NewChatChild,
        is RootComponent.Child.DebugChild -> true

        else -> false
    }
