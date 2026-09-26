package org.monogram.core.models

data class MessageReaction(
    val emoticon: String? = null,
    val documentId: Long? = null,
    val count: Int,
    val chosen: Boolean = false,
)

data class SavedGif(
    val documentId: Long,
    val cacheKey: String,
    val thumbCacheKey: String? = null,
)

data class DiscussionRef(
    val chatId: PeerId,
    val messageId: Int,
)

data class ReactionChoice(
    val emoticon: String = "",
    val documentId: Long = 0L,
)

val DEFAULT_QUICK_REACTIONS = listOf("👍", "❤", "🔥", "🥰", "👏", "😁", "🤔", "🎉")

fun reactionPickerChoices(
    recent: List<ReactionChoice>,
    fallbackEmoticons: List<String> = DEFAULT_QUICK_REACTIONS,
): List<ReactionChoice> {
    val seen = mutableSetOf<String>()
    val out = ArrayList<ReactionChoice>()
    fun add(choice: ReactionChoice) {
        if (choice.emoticon.isBlank() && choice.documentId == 0L) return
        val key = if (choice.documentId != 0L) "d:${choice.documentId}" else "e:${choice.emoticon}"
        if (seen.add(key)) out += choice
    }
    recent.forEach(::add)
    if (out.isEmpty()) {
        fallbackEmoticons.forEach { add(ReactionChoice(emoticon = it)) }
    }
    return out
}

fun encodeReactionsJson(rows: List<MessageReaction>): String =
    rows.joinToString(separator = ",", prefix = "[", postfix = "]") { row ->
        buildString {
            append('{')
            if (row.emoticon != null) append("\"e\":\"${row.emoticon}\",")
            if (row.documentId != null) append("\"d\":${row.documentId},")
            append("\"c\":${row.count},\"me\":${row.chosen}")
            append('}')
        }
    }

fun toggleChosenReaction(raw: String?, emoticon: String, documentId: Long): String {
    val rows = parseReactionsJson(raw).toMutableList()
    val index = rows.indexOfFirst { row ->
        if (documentId != 0L) row.documentId == documentId else row.emoticon == emoticon
    }
    if (index >= 0) {
        val row = rows[index]
        if (row.chosen) {
            val next = row.count - 1
            if (next <= 0) rows.removeAt(index) else rows[index] = row.copy(count = next, chosen = false)
        } else {
            rows[index] = row.copy(count = row.count + 1, chosen = true)
        }
    } else {
        rows += MessageReaction(
            emoticon = emoticon.takeIf { it.isNotBlank() },
            documentId = documentId.takeIf { it != 0L },
            count = 1,
            chosen = true,
        )
    }
    return encodeReactionsJson(rows)
}

fun parseReactionsJson(raw: String?): List<MessageReaction> {
    if (raw.isNullOrBlank()) return emptyList()
    val body = raw.trim().removePrefix("[").removeSuffix("]")
    if (body.isBlank()) return emptyList()
    return body.split("},{").mapNotNull { chunk ->
        val obj = chunk.trim().trimStart('{').trimEnd('}')
        if (obj.isBlank()) return@mapNotNull null
        val emoji = field(obj, "e")
        val document = field(obj, "d")?.toLongOrNull()
        val count = field(obj, "c")?.toIntOrNull() ?: 0
        val chosen = field(obj, "me") == "true"
        if (emoji == null && document == null) return@mapNotNull null
        MessageReaction(
            emoticon = emoji,
            documentId = document,
            count = count,
            chosen = chosen,
        )
    }
}

private fun field(obj: String, key: String): String? {
    val needle = "\"$key\":"
    val at = obj.indexOf(needle)
    if (at < 0) return null
    var i = at + needle.length
    while (i < obj.length && obj[i].isWhitespace()) i++
    if (i >= obj.length) return null
    return if (obj[i] == '"') {
        val end = obj.indexOf('"', i + 1)
        if (end < 0) null else obj.substring(i + 1, end)
    } else {
        val end = obj.indexOf(',', i).let { if (it < 0) obj.indexOf('}', i) else it }
            .let { if (it < 0) obj.length else it }
        obj.substring(i, end).trim().trimEnd('}')
    }
}
