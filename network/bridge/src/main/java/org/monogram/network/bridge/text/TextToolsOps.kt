package org.monogram.network.bridge.text

import org.monogram.core.common.Outcome
import org.monogram.core.models.AiTextResult
import org.monogram.core.models.AiTextTone
import org.monogram.core.models.PeerId
import org.monogram.core.models.TextToolText
import org.monogram.core.models.TextToolsAvailability

interface TextToolsOps {
    suspend fun getTextToolsAvailability(chatId: PeerId): Outcome<TextToolsAvailability> =
        Outcome.Err("Text tools unavailable")

    suspend fun translateMessageTexts(
        chatId: PeerId,
        messageIds: List<Int>,
        toLang: String,
    ): Outcome<List<TextToolText>> = Outcome.Err("Message translation unavailable")

    suspend fun translateTexts(
        texts: List<TextToolText>,
        toLang: String,
    ): Outcome<List<TextToolText>> = Outcome.Err("Text translation unavailable")

    suspend fun summarizeText(
        text: TextToolText,
        toLang: String? = null,
        tone: String? = null,
    ): Outcome<TextToolText> = Outcome.Err("Summaries unavailable")

    suspend fun summarizeMessageText(
        chatId: PeerId,
        messageId: Int,
        toLang: String? = null,
        tone: String? = null,
    ): Outcome<TextToolText> = Outcome.Err("Summaries unavailable")

    suspend fun composeTextWithAi(
        text: TextToolText,
        proofread: Boolean = false,
        emojify: Boolean = false,
        toLang: String? = null,
        tone: String? = null,
    ): Outcome<AiTextResult> = Outcome.Err("AI composition unavailable")

    suspend fun getAiTextTones(): Outcome<List<AiTextTone>> = Outcome.Err("AI tones unavailable")

    suspend fun togglePeerTextTranslations(
        chatId: PeerId,
        disabled: Boolean,
    ): Outcome<Unit> = Outcome.Err("Translation preferences unavailable")

    suspend fun createAiTextTone(
        title: String,
        prompt: String,
        emojiId: Long,
        displayAuthor: Boolean,
    ): Outcome<AiTextTone> = Outcome.Err("AI tone creation unavailable")

    suspend fun updateAiTextTone(
        key: String,
        title: String,
        prompt: String,
        emojiId: Long,
        displayAuthor: Boolean,
    ): Outcome<AiTextTone> = Outcome.Err("AI tone editing unavailable")

    suspend fun saveAiTextTone(
        key: String,
        unsave: Boolean,
    ): Outcome<Unit> = Outcome.Err("AI tone saving unavailable")

    suspend fun deleteAiTextTone(key: String): Outcome<Unit> =
        Outcome.Err("AI tone deletion unavailable")

    suspend fun getAiTextTone(key: String): Outcome<AiTextTone> =
        Outcome.Err("AI tone lookup unavailable")

    suspend fun getAiTextToneExample(key: String): Outcome<TextToolText> =
        Outcome.Err("AI tone examples unavailable")
}
