package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoChatInfoRepository
import org.monogram.domain.models.ChatFullInfoModel
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.GroupMemberModel
import org.monogram.domain.repository.ChatInfoRepository
import org.monogram.domain.repository.ChatMemberStatus
import org.monogram.domain.repository.ChatMembersFilter

internal class TelegramBackendChatInfoRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> ChatInfoRepository,
    private val mtProto: MtProtoChatInfoRepository,
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : ChatInfoRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    override suspend fun getChatFullInfo(chatId: Long): ChatFullInfoModel? = selected().getChatFullInfo(chatId)
    override suspend fun searchPublicChat(username: String): ChatModel? = selected().searchPublicChat(username)
    override suspend fun getSimilarChatIds(chatId: Long): List<Long> = selected().getSimilarChatIds(chatId)
    override suspend fun getChatMembers(chatId: Long, offset: Int, limit: Int, filter: ChatMembersFilter): List<GroupMemberModel> = selected().getChatMembers(chatId, offset, limit, filter)
    override suspend fun getChatMember(chatId: Long, userId: Long): GroupMemberModel? = selected().getChatMember(chatId, userId)
    override suspend fun setChatMemberStatus(chatId: Long, userId: Long, status: ChatMemberStatus) = selected().setChatMemberStatus(chatId, userId, status)

    private fun selected(): ChatInfoRepository = when (selectedBackend.value) {
        TelegramBackendKind.LEGACY -> legacy
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto
        null -> error("Telegram backend selection is not loaded")
    }

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
