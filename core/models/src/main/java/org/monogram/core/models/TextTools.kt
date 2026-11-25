package org.monogram.core.models

data class TextToolText(
    val text: String,
    val entities: List<TextEntity> = emptyList(),
)

data class AiTextResult(
    val result: TextToolText,
    val diff: TextToolText? = null,
)

data class AiTextTone(
    val key: String,
    val title: String,
    val emojiId: Long = 0,
    val creator: Boolean = false,
    val prompt: String? = null,
)

data class TextToolsAvailability(
    val manualTranslation: Boolean = true,
    val chatTranslation: Boolean = true,
    val peerTranslationsDisabled: Boolean = false,
    val channelAutoTranslation: Boolean = false,
    val peerLookupError: String? = null,
)
