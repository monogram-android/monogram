package org.monogram.domain.repository

import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.MessageEntity

interface MessageAiRepository {
    val textCompositionStyles: StateFlow<List<TextCompositionStyleModel>>

    suspend fun summarizeMessage(chatId: Long, messageId: Long, toLanguageCode: String = ""): String?

    suspend fun translateMessage(chatId: Long, messageId: Long, toLanguageCode: String): String?

    suspend fun composeTextWithAi(
        text: String,
        entities: List<MessageEntity>,
        translateToLanguageCode: String = "",
        styleName: String = "",
        addEmojis: Boolean = false
    ): FormattedTextResult?

    suspend fun fixTextWithAi(text: String, entities: List<MessageEntity>): FixedTextResult?
}