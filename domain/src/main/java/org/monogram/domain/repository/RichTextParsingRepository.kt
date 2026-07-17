package org.monogram.domain.repository

interface RichTextParsingRepository {
    suspend fun parseTextEntities(
        text: String,
        mode: RichTextParseMode
    ): FormattedTextResult
}

enum class RichTextParseMode {
    Markdown,
    Html
}
