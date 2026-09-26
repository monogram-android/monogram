package org.monogram.feature.dialog

data class InlineBotQuery(
    val username: String,
    val query: String,
)

object InlineBotQueries {
    fun parse(draft: String): InlineBotQuery? {
        val token = ComposerAt.mentionToken(draft) ?: return null
        return ComposerAt.inlineQuery(token)
    }
}
