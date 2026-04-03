package org.monogram.domain.repository

import org.monogram.domain.models.MessageEntity

data class FormattedTextResult(
    val text: String,
    val entities: List<MessageEntity>
)

data class FixedTextResult(
    val text: String,
    val entities: List<MessageEntity>
)

data class TextCompositionStyleModel(
    val name: String,
    val customEmojiId: Long,
    val title: String
)
