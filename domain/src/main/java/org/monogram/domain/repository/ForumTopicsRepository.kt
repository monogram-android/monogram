package org.monogram.domain.repository

import kotlinx.coroutines.flow.Flow
import org.monogram.domain.models.TopicModel

interface ForumTopicsRepository {
    val forumTopicsFlow: Flow<Pair<Long, List<TopicModel>>>

    suspend fun getForumTopics(
        chatId: Long,
        query: String = "",
        offsetDate: Int = 0,
        offsetMessageId: Long = 0,
        offsetForumTopicId: Int = 0,
        limit: Int = 20
    ): List<TopicModel>
}