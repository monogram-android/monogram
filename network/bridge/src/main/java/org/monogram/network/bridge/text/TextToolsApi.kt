package org.monogram.network.bridge.text

import kotlinx.coroutines.CancellationException
import org.monogram.core.common.Outcome
import org.monogram.core.common.TextToolsProviderSettings
import org.monogram.core.models.AiTextResult
import org.monogram.core.models.AiTextTone
import org.monogram.core.models.PeerId
import org.monogram.core.models.TextEntities
import org.monogram.core.models.TextToolText
import org.monogram.core.models.TextToolsAvailability
import org.monogram.network.bridge.session.SessionCore
import org.monogram.network.http.OpenAiCompatibleTextProvider
import uniffi.monogram_mtproto.AiTextToneDto
import uniffi.monogram_mtproto.TextToolTextDto
import java.util.Locale

internal class TextToolsApi(
    private val core: SessionCore,
    private val customProvider: OpenAiCompatibleTextProvider = OpenAiCompatibleTextProvider(),
) : TextToolsOps {
    private fun customConfig() =
        core.providerAccountId?.let(TextToolsProviderSettings::load)?.takeIf { it.isCustom }

    private fun providerFailure(error: Exception): Outcome.Err =
        Outcome.Err(error.message ?: "Custom text provider failed")

    override suspend fun getTextToolsAvailability(chatId: PeerId): Outcome<TextToolsAvailability> {
        if (customConfig() != null) return Outcome.Ok(TextToolsAvailability())
        return request("getTextToolsAvailability") { handle ->
            core.native.getTextToolsAvailability(handle, chatId.value).let {
                TextToolsAvailability(
                    it.manualTranslation,
                    it.chatTranslation,
                    it.peerTranslationsDisabled,
                    it.channelAutoTranslation
                )
            }
        }
    }

    override suspend fun translateMessageTexts(
        chatId: PeerId,
        messageIds: List<Int>,
        toLang: String,
    ): Outcome<List<TextToolText>> {
        if (messageIds.isEmpty() || messageIds.size > 20 || messageIds.any { it <= 0 } || messageIds.distinct().size != messageIds.size) {
            return Outcome.Err("Invalid translation message batch")
        }
        if (toLang.isBlank()) return Outcome.Err("Translation language required")
        if (customConfig() != null) return Outcome.Err("Custom provider message translation requires source text")
        val translated = request("translateMessageTexts") { handle ->
            core.native.translateMessageTexts(
                handle, chatId.value, messageIds, normalizeTextToolLanguage(toLang)
            ).map { it.toModel() }
        }
        return when (translated) {
            is Outcome.Err -> {
                translated
            }

            is Outcome.Ok -> {
                if (translated.value.size == messageIds.size) {
                    translated
                } else {
                    Outcome.Err("Unexpected translation result count")
                }
            }
        }
    }

    override suspend fun translateTexts(
        texts: List<TextToolText>,
        toLang: String,
    ): Outcome<List<TextToolText>> {
        if (texts.isEmpty() || texts.size > 20 || texts.sumOf { it.text.length.toLong() } > 25_000L || texts.any { it.text.isBlank() }) {
            return Outcome.Err("Invalid translation text batch")
        }
        if (toLang.isBlank()) return Outcome.Err("Translation language required")
        val config = customConfig()
        if (config != null) {
            return try {
                Outcome.Ok(texts.map {
                    customProvider.translate(
                        it, normalizeTextToolLanguage(toLang), config
                    )
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                providerFailure(error)
            }
        }
        val translated = request("translateTexts") { handle ->
            core.native.translateTexts(
                handle, texts.map { it.toNative() }, normalizeTextToolLanguage(toLang)
            ).map { it.toModel() }
        }
        return when (translated) {
            is Outcome.Err -> translated
            is Outcome.Ok -> if (translated.value.size == texts.size) translated else Outcome.Err("Unexpected translation result count")
        }
    }

    override suspend fun summarizeText(
        text: TextToolText,
        toLang: String?,
        tone: String?,
    ): Outcome<TextToolText> {
        val config = customConfig() ?: return Outcome.Err("Custom provider unavailable")
        return try {
            Outcome.Ok(
                customProvider.summarize(
                    text,
                    toLang?.takeIf { it.isNotBlank() }?.let(::normalizeTextToolLanguage),
                    config,
                    tone?.takeIf { it.isNotBlank() },
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            providerFailure(error)
        }
    }

    override suspend fun summarizeMessageText(
        chatId: PeerId,
        messageId: Int,
        toLang: String?,
        tone: String?,
    ): Outcome<TextToolText> {
        if (messageId <= 0) return Outcome.Err("Message id required")
        if (customConfig() != null) return Outcome.Err("Custom provider message summary requires source text")
        return request("summarizeMessageText") { handle ->
            core.native.summarizeMessageText(
                handle,
                chatId.value,
                messageId,
                toLang?.takeIf {
                    it.isNotBlank()
                }?.let(::normalizeTextToolLanguage),
                tone?.takeIf { it.isNotBlank() },
            ).toModel()
        }
    }

    override suspend fun composeTextWithAi(
        text: TextToolText,
        proofread: Boolean,
        emojify: Boolean,
        toLang: String?,
        tone: String?,
    ): Outcome<AiTextResult> {
        if (text.text.isBlank()) return Outcome.Err("AI text required")
        if (!proofread && !emojify && toLang.isNullOrBlank() && tone.isNullOrBlank()) return Outcome.Err(
            "AI transformation required"
        )
        val config = customConfig()
        if (config != null) {
            return try {
                Outcome.Ok(
                    customProvider.compose(
                        text,
                        proofread,
                        emojify,
                        toLang?.takeIf {
                            it.isNotBlank()
                        }?.let(::normalizeTextToolLanguage),
                        tone?.takeIf { it.isNotBlank() },
                        config,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                providerFailure(error)
            }
        }
        return request("composeTextWithAi") { handle ->
            core.native.composeTextWithAi(
                handle,
                text.toNative(),
                proofread,
                emojify,
                toLang?.takeIf {
                    it.isNotBlank()
                }?.let(::normalizeTextToolLanguage),
                tone?.takeIf { it.isNotBlank() },
            ).let {
                AiTextResult(it.result.toModel(), it.diff?.toModel())
            }
        }
    }

    override suspend fun getAiTextTones(): Outcome<List<AiTextTone>> = if (customConfig() != null) {
        Outcome.Err("AI tones are available only with Telegram")
    } else {
        request("getAiTextTones") { handle ->
            core.native.getAiTextTones(handle).map { it.toModel() }
        }
    }

    override suspend fun togglePeerTextTranslations(
        chatId: PeerId,
        disabled: Boolean,
    ): Outcome<Unit> = if (customConfig() != null) {
        Outcome.Err("Translation preferences are available only with Telegram")
    } else {
        request("togglePeerTextTranslations", write = true) { handle ->
            core.native.togglePeerTextTranslations(handle, chatId.value, disabled)
            Unit
        }
    }

    override suspend fun createAiTextTone(
        title: String,
        prompt: String,
        emojiId: Long,
        displayAuthor: Boolean,
    ): Outcome<AiTextTone> = if (customConfig() != null) {
        Outcome.Err("AI tones are available only with Telegram")
    } else {
        request("createAiTextTone", write = true) { handle ->
            core.native.createAiTextTone(handle, title, prompt, emojiId, displayAuthor).toModel()
        }
    }

    override suspend fun updateAiTextTone(
        key: String,
        title: String,
        prompt: String,
        emojiId: Long,
        displayAuthor: Boolean,
    ): Outcome<AiTextTone> = if (customConfig() != null) {
        Outcome.Err("AI tones are available only with Telegram")
    } else {
        request("updateAiTextTone", write = true) { handle ->
            core.native.updateAiTextTone(handle, key, title, prompt, emojiId, displayAuthor)
                .toModel()
        }
    }

    override suspend fun saveAiTextTone(
        key: String,
        unsave: Boolean,
    ): Outcome<Unit> = if (customConfig() != null) {
        Outcome.Err("AI tones are available only with Telegram")
    } else {
        request("saveAiTextTone", write = true) { handle ->
            core.native.saveAiTextTone(handle, key, unsave)
            Unit
        }
    }

    override suspend fun deleteAiTextTone(key: String): Outcome<Unit> =
        if (customConfig() != null) {
            Outcome.Err("AI tones are available only with Telegram")
        } else {
            request("deleteAiTextTone", write = true) { handle ->
                core.native.deleteAiTextTone(handle, key)
                Unit
            }
        }

    override suspend fun getAiTextTone(key: String): Outcome<AiTextTone> =
        if (customConfig() != null) {
            Outcome.Err("AI tones are available only with Telegram")
        } else {
            request("getAiTextTone") { handle ->
                core.native.getAiTextTone(handle, key).toModel()
            }
        }

    override suspend fun getAiTextToneExample(key: String): Outcome<TextToolText> =
        if (customConfig() != null) {
            Outcome.Err("AI tones are available only with Telegram")
        } else {
            request("getAiTextToneExample") { handle ->
                core.native.getAiTextToneExample(handle, key).toModel()
            }
        }

    private suspend fun <T> request(
        operation: String,
        write: Boolean = false,
        block: suspend (Long) -> T,
    ): Outcome<T> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return if (write) core.rpcWrite(
            "$operation failed", block = block
        ) else core.rpc("$operation failed", block = block)
    }
}

internal fun normalizeTextToolLanguage(language: String): String {
    val normalized = language.replace('_', '-')
    return when {
        normalized.matches(Regex("[A-Za-z]{2}")) -> {
            normalized.lowercase(Locale.ROOT)
        }

        normalized.matches(Regex("[A-Za-z]{2}-[A-Za-z]{2}")) -> {
            val (languagePart, region) = normalized.split('-')
            "${languagePart.lowercase(Locale.ROOT)}-${region.uppercase(Locale.ROOT)}"
        }

        else -> {
            normalized
        }
    }
}

internal fun TextToolText.toNative(): TextToolTextDto =
    TextToolTextDto(text, TextEntities.serialize(entities))

internal fun TextToolTextDto.toModel(): TextToolText =
    TextToolText(text, TextEntities.parse(entitiesJson))

internal fun AiTextToneDto.toModel(): AiTextTone = AiTextTone(key, title, emojiId, creator, prompt)
