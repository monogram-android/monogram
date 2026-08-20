package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.TopicModel
import org.monogram.domain.repository.ForumTopicsRepository

internal class TelegramBackendForumTopicsRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> ForumTopicsRepository,
    scope: CoroutineScope,
    private val accountId: String = "default",
) : ForumTopicsRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)

    init { scope.launch { selectionStore.observe(accountId).collect { selectedBackend.value = it } } }

    override val forumTopicsFlow: Flow<Pair<Long, List<TopicModel>>>
        get() = when (selected()) {
            TelegramBackendKind.LEGACY -> legacy.forumTopicsFlow
            TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
        }

    override suspend fun getForumTopics(chatId: Long, query: String, offsetDate: Int, offsetMessageId: Long, offsetForumTopicId: Int, limit: Int) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getForumTopics(chatId, query, offsetDate, offsetMessageId, offsetForumTopicId, limit)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun markForumTopicAsRead(chatId: Long, topicId: Int) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.markForumTopicAsRead(chatId, topicId)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    private fun selected() = checkNotNull(selectedBackend.value) { "Telegram backend selection is not loaded" }
    private fun unsupported(): Nothing = throw UnsupportedOperationException("MTProto forum topics are not available")
}
