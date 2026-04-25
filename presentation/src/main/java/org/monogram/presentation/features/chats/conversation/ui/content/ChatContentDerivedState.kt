package org.monogram.presentation.features.chats.conversation.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.monogram.domain.models.UserModel
import org.monogram.presentation.features.chats.conversation.ChatComponent

@Immutable
internal data class ChatContentPermissionState(
    val canWriteText: Boolean,
    val canSendAnything: Boolean
)

@Immutable
internal data class ChatContentSearchUiState(
    val canLoadMoreSearchResults: Boolean,
    val searchSenderCandidates: List<UserModel>,
    val hasSearchFiltersApplied: Boolean
)

@Immutable
internal data class ChatContentChromeState(
    val showInputBar: Boolean,
    val showJoinButton: Boolean,
    val isCustomBackHandlingEnabled: Boolean,
    val selectedCount: Int,
    val canRevokeSelected: Boolean
)

@Composable
internal fun rememberChatContentPermissionState(
    state: ChatComponent.State
): ChatContentPermissionState {
    val canWriteText by remember(state.isAdmin, state.permissions.canSendBasicMessages) {
        derivedStateOf { state.isAdmin || state.permissions.canSendBasicMessages }
    }
    val canSendPhotos by remember(state.isAdmin, state.permissions.canSendPhotos) {
        derivedStateOf { state.isAdmin || state.permissions.canSendPhotos }
    }
    val canSendVideos by remember(state.isAdmin, state.permissions.canSendVideos) {
        derivedStateOf { state.isAdmin || state.permissions.canSendVideos }
    }
    val canSendDocuments by remember(state.isAdmin, state.permissions.canSendDocuments) {
        derivedStateOf { state.isAdmin || state.permissions.canSendDocuments }
    }
    val canSendAudios by remember(state.isAdmin, state.permissions.canSendAudios) {
        derivedStateOf { state.isAdmin || state.permissions.canSendAudios }
    }
    val canUseMediaPicker by remember(canSendPhotos, canSendVideos) {
        derivedStateOf { canSendPhotos || canSendVideos }
    }
    val canUseDocumentPicker by remember(canSendDocuments, canSendAudios) {
        derivedStateOf { canSendDocuments || canSendAudios }
    }
    val canSendPolls by remember(state.isAdmin, state.permissions.canSendPolls) {
        derivedStateOf { state.isAdmin || state.permissions.canSendPolls }
    }
    val canOpenAttachSheet by remember(
        canUseMediaPicker,
        canUseDocumentPicker,
        canSendPolls,
        state.attachMenuBots
    ) {
        derivedStateOf {
            canUseMediaPicker || canUseDocumentPicker || canSendPolls || state.attachMenuBots.isNotEmpty()
        }
    }
    val canSendStickers by remember(state.isAdmin, state.permissions.canSendOtherMessages) {
        derivedStateOf { state.isAdmin || state.permissions.canSendOtherMessages }
    }
    val canSendVoice by remember(state.isAdmin, state.permissions.canSendVoiceNotes) {
        derivedStateOf { state.isAdmin || state.permissions.canSendVoiceNotes }
    }
    val canSendVideoNotes by remember(state.isAdmin, state.permissions.canSendVideoNotes) {
        derivedStateOf { state.isAdmin || state.permissions.canSendVideoNotes }
    }
    val canSendAnything by remember(
        canWriteText,
        canOpenAttachSheet,
        canSendStickers,
        canSendVoice,
        canSendVideoNotes,
        canSendPolls
    ) {
        derivedStateOf {
            canWriteText || canOpenAttachSheet || canSendStickers || canSendVoice || canSendVideoNotes || canSendPolls
        }
    }

    return remember(canWriteText, canSendAnything) {
        ChatContentPermissionState(
            canWriteText = canWriteText,
            canSendAnything = canSendAnything
        )
    }
}

@Composable
internal fun rememberChatMessageListState(
    state: ChatComponent.State,
    displayMessages: List<org.monogram.domain.models.MessageModel>,
    canSendAnything: Boolean,
    showInitialLoading: Boolean
): ChatMessageListUiState {
    return remember(
        state.chatId,
        state.currentTopicId,
        displayMessages,
        state.selectedMessageIds,
        state.unreadSeparatorCount,
        state.unreadSeparatorLastReadInboxMessageId,
        state.viewAsTopics,
        state.topics,
        state.rootMessage,
        state.isLoading,
        state.isLoadingOlder,
        state.isLoadingNewer,
        state.isAtBottom,
        state.isLatestLoaded,
        state.isOldestLoaded,
        state.isGroup,
        state.isChannel,
        state.isAdmin,
        state.canWrite,
        canSendAnything,
        state.highlightRequest,
        state.fontSize,
        state.letterSpacing,
        state.bubbleRadius,
        state.stickerSize,
        state.autoDownloadMobile,
        state.autoDownloadWifi,
        state.autoDownloadRoaming,
        state.autoDownloadFiles,
        state.autoplayGifs,
        state.autoplayVideos,
        state.showLinkPreviews,
        state.isChatAnimationsEnabled,
        showInitialLoading,
        state.pendingScrollCommand
    ) {
        ChatMessageListUiState(
            chatId = state.chatId,
            currentTopicId = state.currentTopicId,
            messages = displayMessages,
            selectedMessageIds = state.selectedMessageIds,
            unreadSeparatorCount = state.unreadSeparatorCount,
            unreadSeparatorLastReadInboxMessageId = state.unreadSeparatorLastReadInboxMessageId,
            viewAsTopics = state.viewAsTopics,
            topics = state.topics,
            rootMessage = state.rootMessage,
            isLoading = state.isLoading,
            isLoadingOlder = state.isLoadingOlder,
            isLoadingNewer = state.isLoadingNewer,
            isAtBottom = state.isAtBottom,
            isLatestLoaded = state.isLatestLoaded,
            isOldestLoaded = state.isOldestLoaded,
            isGroup = state.isGroup,
            isChannel = state.isChannel,
            isAdmin = state.isAdmin,
            canWrite = state.canWrite,
            canSendAnything = canSendAnything,
            highlightRequest = state.highlightRequest,
            fontSize = state.fontSize,
            letterSpacing = state.letterSpacing,
            bubbleRadius = state.bubbleRadius,
            stickerSize = state.stickerSize,
            autoDownloadMobile = state.autoDownloadMobile,
            autoDownloadWifi = state.autoDownloadWifi,
            autoDownloadRoaming = state.autoDownloadRoaming,
            autoDownloadFiles = state.autoDownloadFiles,
            autoplayGifs = state.autoplayGifs,
            autoplayVideos = state.autoplayVideos,
            showLinkPreviews = state.showLinkPreviews,
            isChatAnimationsEnabled = state.isChatAnimationsEnabled,
            suppressEntryAnimations = showInitialLoading || state.pendingScrollCommand != null
        )
    }
}

@Composable
internal fun rememberChatTopBarUiState(
    state: ChatComponent.State
): ChatContentTopBarUiState {
    return remember(
        state.currentTopicId,
        state.rootMessage,
        state.isGroup,
        state.isChannel,
        state.isAdmin,
        state.permissions,
        state.otherUser,
        state.currentUser,
        state.typingAction,
        state.memberCount,
        state.onlineCount,
        state.topics,
        state.chatTitle,
        state.chatAvatar,
        state.chatPersonalAvatar,
        state.chatEmojiStatus,
        state.isOnline,
        state.isVerified,
        state.isSponsor,
        state.isWhitelistedInAdBlock,
        state.isInstalledFromGooglePlay,
        state.isMuted,
        state.isSearchActive,
        state.searchQuery,
        state.pinnedMessage,
        state.pinnedMessageCount
    ) {
        ChatContentTopBarUiState(
            currentTopicId = state.currentTopicId,
            rootMessage = state.rootMessage,
            isGroup = state.isGroup,
            isChannel = state.isChannel,
            isAdmin = state.isAdmin,
            permissions = state.permissions,
            otherUser = state.otherUser,
            currentUser = state.currentUser,
            typingAction = state.typingAction,
            memberCount = state.memberCount,
            onlineCount = state.onlineCount,
            topics = state.topics,
            chatTitle = state.chatTitle,
            chatAvatar = state.chatAvatar,
            chatPersonalAvatar = state.chatPersonalAvatar,
            chatEmojiStatus = state.chatEmojiStatus,
            isOnline = state.isOnline,
            isVerified = state.isVerified,
            isSponsor = state.isSponsor,
            isWhitelistedInAdBlock = state.isWhitelistedInAdBlock,
            isInstalledFromGooglePlay = state.isInstalledFromGooglePlay,
            isMuted = state.isMuted,
            isSearchActive = state.isSearchActive,
            searchQuery = state.searchQuery,
            pinnedMessage = if (state.isSearchActive) null else state.pinnedMessage,
            pinnedMessageCount = if (state.isSearchActive) 0 else state.pinnedMessageCount
        )
    }
}

@Composable
internal fun rememberChatSearchUiState(
    state: ChatComponent.State
): ChatContentSearchUiState {
    val canLoadMoreSearchResults by remember(
        state.searchNextFromMessageId,
        state.searchResults.size,
        state.searchResultsTotalCount
    ) {
        derivedStateOf {
            state.searchResults.size < state.searchResultsTotalCount ||
                    state.searchNextFromMessageId != 0L
        }
    }
    val searchSenderCandidates by remember(state.searchAvailableSenders, state.otherUser) {
        derivedStateOf {
            buildList {
                addAll(state.searchAvailableSenders)
                state.otherUser?.let(::add)
            }.distinctBy(UserModel::id)
        }
    }
    val hasSearchFiltersApplied by remember(
        state.searchSender,
        state.searchDateFromEpochSeconds,
        state.searchDateToEpochSeconds
    ) {
        derivedStateOf {
            state.searchSender != null ||
                    state.searchDateFromEpochSeconds != null ||
                    state.searchDateToEpochSeconds != null
        }
    }

    return remember(
        canLoadMoreSearchResults,
        searchSenderCandidates,
        hasSearchFiltersApplied
    ) {
        ChatContentSearchUiState(
            canLoadMoreSearchResults = canLoadMoreSearchResults,
            searchSenderCandidates = searchSenderCandidates,
            hasSearchFiltersApplied = hasSearchFiltersApplied
        )
    }
}

@Composable
internal fun rememberChatChromeState(
    state: ChatComponent.State,
    isRecordingVideo: Boolean,
    editingPhotoPath: String?,
    editingVideoPath: String?,
    selectedMessageId: Long?
): ChatContentChromeState {
    val showInputBar by remember(
        state.isChannel,
        state.isGroup,
        state.canWrite,
        state.isCurrentUserRestricted,
        state.currentTopicId,
        state.selectedMessageIds,
        state.viewAsTopics,
        state.isSearchActive,
        isRecordingVideo
    ) {
        derivedStateOf {
            (state.canWrite || state.isCurrentUserRestricted) &&
                    !isRecordingVideo &&
                    !state.isSearchActive &&
                    state.selectedMessageIds.isEmpty() &&
                    (!state.viewAsTopics || state.currentTopicId != null)
        }
    }

    val showJoinButton by remember(
        showInputBar,
        state.isMember,
        state.isChannel,
        state.isGroup,
        state.canWrite,
        state.isCurrentUserRestricted,
        state.selectedMessageIds,
        state.viewAsTopics,
        state.currentTopicId,
        state.isSearchActive,
        isRecordingVideo
    ) {
        derivedStateOf {
            !showInputBar &&
                    !state.isSearchActive &&
                    !state.isMember &&
                    (state.isChannel || state.isGroup) &&
                    !state.canWrite &&
                    !state.isCurrentUserRestricted &&
                    !isRecordingVideo &&
                    state.selectedMessageIds.isEmpty() &&
                    (!state.viewAsTopics || state.currentTopicId != null)
        }
    }

    val isCustomBackHandlingEnabled by remember(
        editingPhotoPath,
        editingVideoPath,
        selectedMessageId,
        state.selectedMessageIds,
        state.currentTopicId,
        state.showBotCommands,
        state.restrictUserId,
        state.showPinnedMessagesList,
        state.fullScreenImages,
        state.fullScreenVideoPath,
        state.fullScreenVideoMessageId,
        state.miniAppUrl,
        state.webViewUrl,
        state.instantViewUrl,
        state.youtubeUrl,
        state.isSearchActive
    ) {
        derivedStateOf {
            editingPhotoPath != null ||
                    editingVideoPath != null ||
                    selectedMessageId != null ||
                    state.selectedMessageIds.isNotEmpty() ||
                    state.currentTopicId != null ||
                    state.showBotCommands ||
                    state.restrictUserId != null ||
                    state.showPinnedMessagesList ||
                    state.fullScreenImages != null ||
                    state.fullScreenVideoPath != null ||
                    state.fullScreenVideoMessageId != null ||
                    state.miniAppUrl != null ||
                    state.webViewUrl != null ||
                    state.instantViewUrl != null ||
                    state.youtubeUrl != null ||
                    state.isSearchActive
        }
    }

    val selectedCount = state.selectedMessageIds.size
    val selectedMessageIdSet by remember(state.selectedMessageIds) {
        derivedStateOf { state.selectedMessageIds.toHashSet() }
    }
    val canRevokeSelected by remember(state.messages, selectedMessageIdSet) {
        derivedStateOf {
            if (selectedMessageIdSet.isEmpty()) {
                false
            } else {
                state.messages.any { it.id in selectedMessageIdSet && it.canBeDeletedForAllUsers }
            }
        }
    }

    return remember(
        showInputBar,
        showJoinButton,
        isCustomBackHandlingEnabled,
        selectedCount,
        canRevokeSelected
    ) {
        ChatContentChromeState(
            showInputBar = showInputBar,
            showJoinButton = showJoinButton,
            isCustomBackHandlingEnabled = isCustomBackHandlingEnabled,
            selectedCount = selectedCount,
            canRevokeSelected = canRevokeSelected
        )
    }
}

