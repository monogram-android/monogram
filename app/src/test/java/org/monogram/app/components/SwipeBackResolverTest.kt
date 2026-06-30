package org.monogram.app.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.FolderModel
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.repository.ForwardTarget
import org.monogram.presentation.core.ui.ScreenSwipeBackAction
import org.monogram.presentation.core.ui.ScreenSwipeBackPreview
import org.monogram.presentation.core.ui.ScreenSwipeBackState
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.features.chats.conversation.ChatComponent
import org.monogram.presentation.features.chats.list.ChatListComponent
import org.monogram.presentation.features.chats.list.ChatListPreviewMode
import org.monogram.presentation.root.RootComponent
import java.lang.reflect.Proxy

class SwipeBackResolverTest {
    @Test
    fun `normal chat resolves to stack pop`() {
        val resolution = resolveSwipeBack(
            child = RootComponent.Child.ChatDetailChild(fakeChatComponent()),
            screenSwipeBackState = ScreenSwipeBackState(
                isSupported = true,
                isBlocked = false,
                action = ScreenSwipeBackAction.StackPop,
                preview = ScreenSwipeBackPreview.PreviousStackEntry,
            ),
        )

        assertTrue(resolution.isSupported)
        assertFalse(resolution.isBlocked)
        assertEquals(ScreenSwipeBackAction.StackPop, resolution.action)
        assertEquals(SwipeBackPreviewDescriptor.PreviousStackEntry, resolution.preview)
    }

    @Test
    fun `topic chat resolves to local forum preview`() {
        val resolution = resolveSwipeBack(
            child = RootComponent.Child.ChatDetailChild(fakeChatComponent()),
            screenSwipeBackState = ScreenSwipeBackState(
                isSupported = true,
                isBlocked = false,
                action = ScreenSwipeBackAction.LocalChatTopicClose,
                preview = ScreenSwipeBackPreview.ChatForumList,
            ),
        )

        assertTrue(resolution.isSupported)
        assertEquals(ScreenSwipeBackAction.LocalChatTopicClose, resolution.action)
        assertEquals(SwipeBackPreviewDescriptor.ChatForumList, resolution.preview)
    }

    @Test
    fun `archive chat list resolves to local folder preview`() {
        val resolution = resolveSwipeBack(
            child = RootComponent.Child.ChatsChild(fakeChatListComponent()),
            screenSwipeBackState = ScreenSwipeBackState(
                isSupported = true,
                isBlocked = false,
                action = ScreenSwipeBackAction.LocalChatListArchiveReturn,
                preview = ScreenSwipeBackPreview.ChatListFolder,
            ),
            archivePreviewFolderId = 7,
        )

        assertTrue(resolution.isSupported)
        assertEquals(ScreenSwipeBackAction.LocalChatListArchiveReturn, resolution.action)
        assertEquals(SwipeBackPreviewDescriptor.ChatListFolder(7), resolution.preview)
    }

    @Test
    fun `blocked state disables swipe`() {
        val resolution = resolveSwipeBack(
            child = RootComponent.Child.ChatDetailChild(fakeChatComponent()),
            screenSwipeBackState = ScreenSwipeBackState(
                isSupported = true,
                isBlocked = true,
                action = ScreenSwipeBackAction.StackPop,
                preview = ScreenSwipeBackPreview.PreviousStackEntry,
            ),
        )

        assertTrue(resolution.isSupported)
        assertTrue(resolution.isBlocked)
    }

    @Test
    fun `archive preview mode uses remembered folder`() {
        val previewMode = resolveArchivePreviewMode(
            child = RootComponent.Child.ChatsChild(
                fakeChatListComponent(
                    ChatListComponent.State(
                        folders = listOf(
                            FolderModel(id = -1, title = "All chats"),
                            FolderModel(id = 3, title = "Work"),
                            FolderModel(id = 7, title = "Family"),
                        ),
                        selectedFolderId = -2,
                        lastNonArchiveFolderId = 7,
                    )
                )
            ),
            showAllChatsFolder = true,
        )

        assertEquals(ChatListPreviewMode.FolderPreview(7), previewMode)
    }

    @Test
    fun `blocked archive state disables swipe`() {
        val resolution = resolveSwipeBack(
            child = RootComponent.Child.ChatsChild(
                fakeChatListComponent(
                    ChatListComponent.State(
                        folders = listOf(
                            FolderModel(id = -1, title = "All chats"),
                            FolderModel(id = 3, title = "Work"),
                        ),
                        selectedFolderId = -2,
                        lastNonArchiveFolderId = 3,
                        isSearchActive = true,
                    )
                )
            ),
            screenSwipeBackState = ScreenSwipeBackState(
                isSupported = true,
                isBlocked = true,
                action = ScreenSwipeBackAction.LocalChatListArchiveReturn,
                preview = ScreenSwipeBackPreview.ChatListFolder,
            ),
            archivePreviewFolderId = 3,
        )

        assertTrue(resolution.isSupported)
        assertTrue(resolution.isBlocked)
        assertEquals(SwipeBackPreviewDescriptor.ChatListFolder(3), resolution.preview)
    }

    private fun fakeChatComponent(): ChatComponent {
        return Proxy.newProxyInstance(
            ChatComponent::class.java.classLoader,
            arrayOf(ChatComponent::class.java),
        ) { _, _, _ ->
            throw UnsupportedOperationException("Not used by resolver tests")
        } as ChatComponent
    }

    private fun fakeChatListComponent(
        state: ChatListComponent.State = ChatListComponent.State()
    ): ChatListComponent {
        return object : ChatListComponent {
            override val state: StateFlow<ChatListComponent.State> = MutableStateFlow(state)
            override val uiState: StateFlow<ChatListComponent.UiState> =
                MutableStateFlow(ChatListComponent.UiState())
            override val foldersState: StateFlow<ChatListComponent.FoldersState> =
                MutableStateFlow(ChatListComponent.FoldersState())
            override val chatsState: StateFlow<ChatListComponent.ChatsState> =
                MutableStateFlow(ChatListComponent.ChatsState())
            override val selectionState: StateFlow<ChatListComponent.SelectionState> =
                MutableStateFlow(ChatListComponent.SelectionState())
            override val searchState: StateFlow<ChatListComponent.SearchState> =
                MutableStateFlow(ChatListComponent.SearchState())
            override val appPreferences: AppPreferences
                get() = throw UnsupportedOperationException("Not used by resolver tests")

            override fun onChatClicked(id: Long) = unsupported()
            override fun onProfileClicked(id: Long) = unsupported()
            override fun onMessageClicked(chatId: Long, messageId: Long) = unsupported()
            override fun onSettingsClicked() = unsupported()
            override fun onFolderClicked(id: Int) = unsupported()
            override fun loadMore(folderId: Int?) = unsupported()
            override fun loadMoreMessages() = unsupported()
            override fun onChatLongClicked(id: Long) = unsupported()
            override fun clearSelection() = unsupported()
            override fun retryConnection() = unsupported()
            override fun onSearchToggle() = unsupported()
            override fun onSearchQueryChange(query: String) = unsupported()
            override fun onSetEmojiStatus(customEmojiId: Long, statusPath: String?) = unsupported()
            override fun onClearSearchHistory() = unsupported()
            override fun onRemoveSearchHistoryItem(chatId: Long) = unsupported()
            override fun onMuteSelected(mute: Boolean) = unsupported()
            override fun onArchiveSelected(archive: Boolean) = unsupported()
            override fun onPinSelected() = unsupported()
            override fun onToggleReadSelected() = unsupported()
            override fun onDeleteSelected() = unsupported()
            override fun onMarkCurrentFolderRead() = unsupported()
            override fun onLeaveSelected() = unsupported()
            override fun onClearHistorySelected(revoke: Boolean) = unsupported()
            override fun onReportSelected(reason: String) = unsupported()
            override fun onArchivePinToggle() = unsupported()
            override fun onConfirmForwarding(
                sendCopy: Boolean,
                removeCaption: Boolean,
                commentText: String,
                commentEntities: List<MessageEntity>
            ) = unsupported()

            override fun onShareTopicSelected(chatId: Long, topicId: Int?) = unsupported()
            override fun onDismissShareTopicPicker() = unsupported()
            override fun onForwardTopicSelected(chatId: Long, topicId: Int?) = unsupported()
            override fun onDismissForwardTopicPicker() = unsupported()
            override fun onRemoveForwardTarget(target: ForwardTarget) = unsupported()
            override fun onNewChatClicked() = unsupported()
            override fun onProxySettingsClicked() = unsupported()
            override fun onEditFoldersClicked() = unsupported()
            override fun onDeleteFolder(folderId: Int) = unsupported()
            override fun onEditFolder(folderId: Int) = unsupported()
            override fun onOpenInstantView(url: String) = unsupported()
            override fun onDismissInstantView() = unsupported()
            override fun onOpenWebApp(url: String, botUserId: Long, botName: String) = unsupported()
            override fun onDismissWebApp() = unsupported()
            override fun onOpenWebView(url: String) = unsupported()
            override fun onDismissWebView() = unsupported()
            override fun onUpdateClicked() = unsupported()
            override fun onProjectChannelSubscribe() = unsupported()
            override fun onProjectChannelLater() = unsupported()
            override fun handleBack(): Boolean = unsupported()
            override fun onVisibleChatIdsChanged(ids: List<Long>) = unsupported()
            override fun updateScrollPosition(folderId: Int, index: Int, offset: Int) =
                unsupported()

            private fun unsupported(): Nothing =
                throw UnsupportedOperationException("Not used by resolver tests")
        }
    }
}
