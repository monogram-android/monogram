package org.monogram.presentation.features.chats.currentChat.impl

import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.ChatType
import org.monogram.domain.models.UserStatusType
import org.monogram.domain.models.UserTypeEnum
import org.monogram.domain.repository.ChatMemberStatus
import org.monogram.presentation.features.chats.currentChat.DefaultChatComponent

internal fun DefaultChatComponent.loadChatInfo() {
    scope.launch {
        val baseChat = chatListRepository.getChatById(chatId)
        if (baseChat != null) {
            updateBaseChatState(baseChat)
            if (baseChat.viewAsTopics && _state.value.topics.isEmpty()) {
                loadTopics()
            }

            val isBot = baseChat.type == ChatType.PRIVATE && baseChat.isBot
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

        val effectiveChatId = _state.value.effectiveThreadChatId(chatId)
        chatListRepository.getChatById(effectiveChatId)?.let(::updateEffectiveChatState)
        refreshEffectiveChatDetails(effectiveChatId)
        refreshCurrentUserRestrictionState()
    }

    if (chatInfoObserversStarted) return
    chatInfoObserversStarted = true

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
            updateBaseChatState(chat)
            if (chat.viewAsTopics) {
                if (_state.value.topics.isEmpty()) {
                    loadTopics()
                }
            } else if (wasTopics) {
                loadMessages()
            }
        }
        .launchIn(scope)

    _state
        .map { it.effectiveThreadChatId(chatId) }
        .distinctUntilChanged()
        .onEach { effectiveChatId ->
            chatListRepository.getChatById(effectiveChatId)?.let(::updateEffectiveChatState)
            refreshEffectiveChatDetails(effectiveChatId)
            refreshCurrentUserRestrictionState()
        }
        .launchIn(scope)

    chatListRepository.chatListFlow
        .map { chats -> chats.find { it.id == _state.value.effectiveThreadChatId(chatId) } }
        .filterNotNull()
        .map { chat -> chat.permissions to chat.isMember }
        .distinctUntilChanged()
        .onEach { (_, _) ->
            chatListRepository.getChatById(_state.value.effectiveThreadChatId(chatId))
                ?.let(::updateEffectiveChatState)
            refreshCurrentUserRestrictionState()
        }
        .launchIn(scope)

    chatListRepository.chatListFlow
        .map { chats -> chats.find { it.id == _state.value.effectiveThreadChatId(chatId) } }
        .filterNotNull()
        .distinctUntilChanged { old, new ->
            old.permissions == new.permissions &&
                    old.isMember == new.isMember &&
                    old.isAdmin == new.isAdmin &&
                    old.memberCount == new.memberCount &&
                    old.onlineCount == new.onlineCount
        }
        .onEach(::updateEffectiveChatState)
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

private fun DefaultChatComponent.updateBaseChatState(chat: ChatModel) {
    _state.update { currentState ->
        val isDetailedInfoMissing = (chat.isGroup || chat.isChannel) && chat.memberCount == 0
        val unreadSeparatorCount = when {
            currentState.unreadSeparatorCount > 0 -> currentState.unreadSeparatorCount
            chat.unreadCount > 0 -> chat.unreadCount
            else -> 0
        }
        val unreadSeparatorLastReadInboxMessageId = when {
            currentState.unreadSeparatorCount > 0 -> currentState.unreadSeparatorLastReadInboxMessageId
            chat.unreadCount > 0 -> chat.lastReadInboxMessageId
            else -> 0L
        }

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
            memberCount = if (!isDetailedInfoMissing) chat.memberCount else currentState.memberCount,
            onlineCount = if (!isDetailedInfoMissing) chat.onlineCount else currentState.onlineCount,
            unreadCount = chat.unreadCount,
            unreadSeparatorCount = unreadSeparatorCount,
            unreadMentionCount = chat.unreadMentionCount,
            unreadReactionCount = chat.unreadReactionCount,
            userStatus = chat.userStatus,
            typingAction = chat.typingAction,
            viewAsTopics = chat.viewAsTopics,
            isMuted = chat.isMuted,
            lastReadInboxMessageId = chat.lastReadInboxMessageId,
            unreadSeparatorLastReadInboxMessageId = unreadSeparatorLastReadInboxMessageId,
        )
    }
}

private fun DefaultChatComponent.updateEffectiveChatState(chat: ChatModel) {
    _state.update { currentState ->
        val canWrite = if (chat.isAdmin) true else chat.permissions.canSendBasicMessages
        val isDetailedInfoMissing = (chat.isGroup || chat.isChannel) && chat.memberCount == 0

        currentState.copy(
            canWrite = canWrite,
            isAdmin = chat.isAdmin,
            permissions = chat.permissions,
            isMember = chat.isMember,
            memberCount = if (!isDetailedInfoMissing) chat.memberCount else currentState.memberCount,
            onlineCount = if (!isDetailedInfoMissing) chat.onlineCount else currentState.onlineCount
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
        val effectiveChatId = _state.value.effectiveThreadChatId(chatId)
        chatInfoRepository.setChatMemberStatus(
            effectiveChatId,
            userRepository.getMe().id,
            ChatMemberStatus.Member
        )
        chatListRepository.getChatById(effectiveChatId)?.let(::updateEffectiveChatState)
        refreshEffectiveChatDetails(effectiveChatId)
        refreshCurrentUserRestrictionState()
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

private suspend fun DefaultChatComponent.refreshCurrentUserRestrictionState() {
    val me = runCatching { userRepository.getMe() }.getOrNull() ?: return
    val effectiveChatId = _state.value.effectiveThreadChatId(chatId)
    val status =
        runCatching { chatInfoRepository.getChatMember(effectiveChatId, me.id)?.status }.getOrNull()
    val restrictedStatus = status as? ChatMemberStatus.Restricted

    _state.update {
        it.copy(
            isCurrentUserRestricted = restrictedStatus != null,
            restrictedUntilDate = restrictedStatus?.restrictedUntilDate ?: 0,
            effectiveInputPermissions = restrictedStatus?.permissions
        )
    }
}

private suspend fun DefaultChatComponent.refreshEffectiveChatDetails(effectiveChatId: Long) {
    runCatching { chatInfoRepository.getChatFullInfo(effectiveChatId) }
        .getOrNull()
        ?.let { fullInfo ->
            _state.update {
                it.copy(
                    slowModeDelay = fullInfo.slowModeDelay,
                    slowModeDelayExpiresIn = fullInfo.slowModeDelayExpiresIn
                )
            }
        }
}
