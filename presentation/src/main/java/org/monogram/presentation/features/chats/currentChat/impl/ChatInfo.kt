package org.monogram.presentation.features.chats.currentChat.impl

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.ChatType
import org.monogram.domain.models.UserStatusType
import org.monogram.domain.models.UserTypeEnum
import org.monogram.domain.repository.ChatMemberStatus
import org.monogram.presentation.features.chats.currentChat.DefaultChatComponent

internal fun DefaultChatComponent.loadChatInfo() {
    scope.launch {
        val chat = chatListRepository.getChatById(chatId)
        if (chat != null) {
            updateChatState(chat)
            if (chat.viewAsTopics && _state.value.topics.isEmpty()) {
                loadTopics()
            }

            val isBot = chat.type == ChatType.PRIVATE && chat.isBot
            if (isBot) {
                val botInfo = botRepository.getBotInfo(chatId)
                if (botInfo != null) {
                    _state.update {
                        it.copy(
                        botCommands = botInfo.commands,
                        botMenuButton = botInfo.menuButton,
                        isBot = true
                        )
                    }
                }
            }
        }
    }

    chatListRepository.chatListFlow
        .map { chats -> chats.find { it.id == chatId } }
        .filterNotNull()
        .distinctUntilChanged { old, new ->
            old.viewAsTopics == new.viewAsTopics &&
                    old.title == new.title &&
                    old.avatarPath == new.avatarPath &&
                    old.personalAvatarPath == new.personalAvatarPath &&
                    old.isVerified == new.isVerified &&
                    old.isSponsor == new.isSponsor &&
                    old.emojiStatusPath == new.emojiStatusPath &&
                    old.isMuted == new.isMuted &&
                    old.permissions == new.permissions &&
                    old.unreadCount == new.unreadCount &&
                    old.unreadMentionCount == new.unreadMentionCount &&
                    old.unreadReactionCount == new.unreadReactionCount &&
                    old.isMember == new.isMember
        }
        .onEach { chat ->
            val wasTopics = _state.value.viewAsTopics
            updateChatState(chat)
            if (chat.viewAsTopics) {
                if (_state.value.topics.isEmpty()) {
                    loadTopics()
                }
            } else if (wasTopics) {
                loadMessages()
            }
        }
        .launchIn(scope)

    forumTopicsRepository.forumTopicsFlow
        .filter { it.first == chatId }
        .onEach { (_, topics) ->
            _state.update { it.copy(topics = topics) }
        }
        .launchIn(scope)
}

internal fun DefaultChatComponent.loadTopics() {
    if (_state.value.isLoadingTopics) return
    scope.launch {
        _state.update { it.copy(isLoadingTopics = true) }
        try {
            val topics = forumTopicsRepository.getForumTopics(chatId)
            _state.update { it.copy(topics = topics) }
        } finally {
            _state.update { it.copy(isLoadingTopics = false) }
        }
    }
}

internal fun DefaultChatComponent.observeUserUpdates() {
    if (_state.value.isGroup || _state.value.isChannel) return
    scope.launch {
        userRepository.getUserFlow(chatId).collectLatest { user ->
            if (user != null) {
                val isBot = user.type == UserTypeEnum.BOT
                _state.update {
                    it.copy(
                    isOnline = !isBot && user.userStatus == UserStatusType.ONLINE,
                    isVerified = user.isVerified,
                        isSponsor = user.isSponsor,
                    userStatus = user.userStatus.toString(), 
                    chatPersonalAvatar = user.personalAvatarPath,
                    otherUser = user
                    )
                }
            }
        }
    }
}

internal fun DefaultChatComponent.updateChatState(chat: ChatModel) {
    _state.update { currentState ->
        val isDetailedInfoMissing = (chat.isGroup || chat.isChannel) && chat.memberCount == 0
        val canWrite = if (chat.isAdmin) true else chat.permissions.canSendBasicMessages

        currentState.copy(
            chatTitle = chat.title,
            chatAvatar = chat.avatarPath,
            chatPersonalAvatar = chat.personalAvatarPath,
            chatEmojiStatus = chat.emojiStatusPath,
            isGroup = chat.isGroup,
            isChannel = chat.isChannel,
            isSecretChat = chat.type == ChatType.SECRET,
            isVerified = if (chat.isGroup || chat.isChannel) chat.isVerified else (chat.isVerified || currentState.isVerified),
            isSponsor = if (chat.isGroup || chat.isChannel) false else (chat.isSponsor || currentState.isSponsor),
            canWrite = canWrite,
            isAdmin = chat.isAdmin,
            memberCount = if (!isDetailedInfoMissing) chat.memberCount else currentState.memberCount,
            onlineCount = if (!isDetailedInfoMissing) chat.onlineCount else currentState.onlineCount,
            unreadCount = chat.unreadCount,
            unreadMentionCount = chat.unreadMentionCount,
            unreadReactionCount = chat.unreadReactionCount,
            userStatus = chat.userStatus,
            typingAction = chat.typingAction,
            viewAsTopics = chat.viewAsTopics,
            isMuted = chat.isMuted,
            permissions = chat.permissions,
            isMember = chat.isMember
        )
    }
}

internal fun DefaultChatComponent.handleToggleMute() {
    chatOperationsRepository.toggleMuteChats(setOf(chatId), !_state.value.isMuted)
}

internal fun DefaultChatComponent.handleAddToAdBlockWhitelist() {
    val current = appPreferences.adBlockWhitelistedChannels.value
    if (chatId in current) return

    appPreferences.setAdBlockWhitelistedChannels(current + chatId)
    loadMessages(force = true)
}

internal fun DefaultChatComponent.handleRemoveFromAdBlockWhitelist() {
    val current = appPreferences.adBlockWhitelistedChannels.value
    if (chatId !in current) return

    appPreferences.setAdBlockWhitelistedChannels(current - chatId)
    loadMessages(force = true)
}

internal fun DefaultChatComponent.handleClearHistory() {
    chatOperationsRepository.clearChatHistory(chatId, true)
}

internal fun DefaultChatComponent.handleDeleteChat() {
    chatOperationsRepository.deleteChats(setOf(chatId))
    onBackClicked()
}

internal fun DefaultChatComponent.handleJoinChat() {
    scope.launch {
        chatInfoRepository.setChatMemberStatus(chatId, userRepository.getMe().id, ChatMemberStatus.Member)
    }
}

internal fun DefaultChatComponent.handleBlockUser(userId: Long) {
    scope.launch {
        if (_state.value.isGroup || _state.value.isChannel) {
            chatInfoRepository.setChatMemberStatus(chatId, userId, ChatMemberStatus.Banned())
        } else {
            privacyRepository.blockUser(userId)
        }
    }
}

internal fun DefaultChatComponent.handleUnblockUser(userId: Long) {
    scope.launch {
        privacyRepository.unblockUser(userId)
    }
}

internal fun DefaultChatComponent.handleConfirmRestrict(
    permissions: org.monogram.domain.models.ChatPermissionsModel,
    untilDate: Int
) {
    val userId = _state.value.restrictUserId ?: return
    scope.launch {
        chatInfoRepository.setChatMemberStatus(
            chatId,
            userId,
            ChatMemberStatus.Restricted(isMember = true, restrictedUntilDate = untilDate, permissions = permissions)
        )
        _state.update { it.copy(restrictUserId = null) }
    }
}