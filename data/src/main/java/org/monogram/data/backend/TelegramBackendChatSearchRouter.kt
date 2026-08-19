package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import org.monogram.domain.models.ChatModel
import org.monogram.domain.repository.ChatSearchRepository
import org.monogram.domain.repository.SearchMessagesResult

/** Prevents unsupported MTProto search calls from falling through to the TDLib repository. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TelegramBackendChatSearchRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> ChatSearchRepository,
    private val mtProtoFactory: () -> ChatSearchRepository = {
        throw UnsupportedOperationException("MTProto chat search is not available")
    },
    scope: CoroutineScope,
    accountId: String = DEFAULT_ACCOUNT_ID,
) : ChatSearchRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    override val searchHistory: Flow<List<ChatModel>> = selectedBackend.flatMapLatest { backend ->
        when (backend) {
            TelegramBackendKind.LEGACY -> legacy.searchHistory
            TelegramBackendKind.KOTLIN_MTPROTO,
            null -> emptyFlow()
        }
    }

    override suspend fun searchChats(query: String): List<ChatModel> = selected().searchChats(query)

    override suspend fun searchPublicChats(query: String): List<ChatModel> = selected().searchPublicChats(query)

    override suspend fun searchMessages(query: String, offset: String, limit: Int): SearchMessagesResult =
        selected().searchMessages(query, offset, limit)

    override fun addSearchChatId(chatId: Long) = selectedOrThrow().addSearchChatId(chatId)

    override fun removeSearchChatId(chatId: Long) = selectedOrThrow().removeSearchChatId(chatId)

    override fun clearSearchHistory() = selectedOrThrow().clearSearchHistory()

    private suspend fun selected(): ChatSearchRepository = when (selectedBackend.value) {
        TelegramBackendKind.LEGACY -> legacy
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto
        null -> error("Telegram backend selection is not loaded")
    }

    private fun selectedOrThrow(): ChatSearchRepository = when (selectedBackend.value) {
        TelegramBackendKind.LEGACY -> legacy
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto
        null -> error("Telegram backend selection is not loaded")
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "MTProto chat search is not available"
    )

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
    }
}
