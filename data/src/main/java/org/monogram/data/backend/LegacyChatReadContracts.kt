package org.monogram.data.backend

import org.monogram.domain.repository.ChatFolderRepository
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.ChatOperationsRepository

internal class LegacyChatReadContracts(
    chatListRepository: ChatListRepository,
    chatFolderRepository: ChatFolderRepository,
    chatOperationsRepository: ChatOperationsRepository,
) : TelegramBackendChatReadRouter.ChatReadContracts,
    ChatListRepository by chatListRepository,
    ChatFolderRepository by chatFolderRepository,
    ChatOperationsRepository by chatOperationsRepository
