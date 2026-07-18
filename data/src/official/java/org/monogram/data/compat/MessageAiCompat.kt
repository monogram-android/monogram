package org.monogram.data.compat

import org.drinkless.tdlib.TdApi
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.mapper.toDomainRichMessage
import org.monogram.domain.models.webapp.toEditorPlainText
import org.monogram.domain.repository.FormattedTextResult

internal fun isPromptBasedAiSupported(): Boolean = true

internal suspend fun TelegramGateway.generateTextWithAi(
    prompt: String,
    languageCode: String,
    addEmojis: Boolean
): FormattedTextResult? {
    if (prompt.isBlank()) return null

    return when (val result =
        execute(TdApi.CreateRichMessageWithAi(prompt, languageCode, addEmojis))) {
        is TdApi.RichMessage -> {
            val text = result.toDomainRichMessage(0L, 0L).blocks.toEditorPlainText()
            if (text.isBlank()) null else FormattedTextResult(text = text, entities = emptyList())
        }

        else -> null
    }
}
