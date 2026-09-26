package org.monogram.feature.dialog

import org.monogram.core.models.PeerId

data class MentionCandidate(
    val peerId: PeerId,
    val title: String,
    val username: String? = null,
    val avatarCacheKey: String? = null,
    val isBot: Boolean = false,
)

data class DraftMention(
    val peerId: PeerId,
    val start: Int,
    val length: Int,
)

data class ComposerAtToken(
    val atIndex: Int,
    val handle: String,
    val rest: String,
    val hasSpace: Boolean,
    val atMessageStart: Boolean,
)

object ComposerAt {
    private val usernamePattern = Regex("^[A-Za-z][A-Za-z0-9_]{2,31}$")

    fun mentionToken(draft: String): ComposerAtToken? {
        var index = draft.length - 1
        while (index >= 0) {
            if (draft[index] == '@' && (index == 0 || draft[index - 1].isWhitespace())) {
                val after = draft.substring(index + 1)
                val space = after.indexOfFirst { it.isWhitespace() }
                val handle = if (space < 0) after else after.substring(0, space)
                if (handle.any { !it.isLetterOrDigit() && it != '_' }) {
                    index--
                    continue
                }
                val rest = if (space < 0) "" else after.substring(space + 1)
                return ComposerAtToken(
                    atIndex = index,
                    handle = handle,
                    rest = rest,
                    hasSpace = space >= 0,
                    atMessageStart = draft.substring(0, index).isBlank(),
                )
            }
            index--
        }
        return null
    }

    fun isUsername(handle: String): Boolean = usernamePattern.matches(handle)

    fun inlineQuery(token: ComposerAtToken): InlineBotQuery? {
        if (!token.atMessageStart || !isUsername(token.handle)) return null
        return InlineBotQuery(username = token.handle.lowercase(), query = token.rest)
    }

    fun replaceHandle(draft: String, token: ComposerAtToken, insertion: String): String {
        val handleEnd = token.atIndex + 1 + token.handle.length
        return draft.substring(0, token.atIndex) + insertion + draft.substring(handleEnd)
    }

    fun remapMentions(
        previous: String,
        next: String,
        mentions: List<DraftMention>,
    ): List<DraftMention> {
        if (next.isEmpty() || mentions.isEmpty()) return emptyList()
        return mentions.filter { mention ->
            val end = mention.start + mention.length
            end <= previous.length &&
                end <= next.length &&
                mention.start >= 0 &&
                previous.substring(mention.start, end) == next.substring(mention.start, end)
        }
    }
}
