package org.monogram.core.models

const val PEER_LIST_FILTER_ALL = "all"

data class PeerListFilterChip(
    val key: String,
    val count: Int,
    val emoticon: String? = null,
    val documentId: Long? = null,
    val label: String? = null,
)

fun reactionChoiceKey(emoticon: String?, documentId: Long?): String {
    val document = documentId ?: 0L
    return if (document != 0L) "d:$document" else "e:${emoticon.orEmpty()}"
}

fun pollChoiceKey(optionHex: String): String = "p:$optionHex"

fun peerListFilterChips(
    users: List<MessageViewer>,
    kind: String?,
    poll: Poll? = null,
): List<PeerListFilterChip> {
    val choices = if (kind == "poll") {
        pollFilterChips(users, poll)
    } else {
        reactionFilterChips(users)
    }
    if (choices.isEmpty()) return emptyList()
    return listOf(PeerListFilterChip(key = PEER_LIST_FILTER_ALL, count = users.size)) + choices
}

fun filterPeerList(
    users: List<MessageViewer>,
    selectedKey: String?,
): List<MessageViewer> {
    if (selectedKey.isNullOrBlank() || selectedKey == PEER_LIST_FILTER_ALL) return users
    return users.filter { user ->
        if (selectedKey.startsWith("p:")) {
            val hex = selectedKey.removePrefix("p:")
            user.pollOptionHex.any { it.equals(hex, ignoreCase = true) }
        } else {
            reactionChoiceKey(user.emoticon, user.documentId) == selectedKey
        }
    }
}

fun pollAnswersLabel(user: MessageViewer, poll: Poll?): String? {
    if (user.pollOptionHex.isEmpty()) return null
    val answers = poll?.answers.orEmpty()
    val labels = user.pollOptionHex.map { hex ->
        answers.firstOrNull { pollOptionHex(it.option).equals(hex, ignoreCase = true) }?.text
            ?: hex
    }
    return labels.joinToString(", ").ifBlank { null }
}

private fun reactionFilterChips(users: List<MessageViewer>): List<PeerListFilterChip> {
    val grouped = LinkedHashMap<String, PeerListFilterChip>()
    for (user in users) {
        if (user.emoticon.isNullOrBlank() && (user.documentId ?: 0L) == 0L) continue
        val key = reactionChoiceKey(user.emoticon, user.documentId)
        val existing = grouped[key]
        grouped[key] = if (existing == null) {
            PeerListFilterChip(
                key = key,
                count = 1,
                emoticon = user.emoticon,
                documentId = user.documentId,
            )
        } else {
            existing.copy(count = existing.count + 1)
        }
    }
    return grouped.values.toList()
}

private fun pollFilterChips(users: List<MessageViewer>, poll: Poll?): List<PeerListFilterChip> {
    val answers = poll?.answers.orEmpty()
    if (answers.isNotEmpty()) {
        return answers.map { answer ->
            val hex = pollOptionHex(answer.option)
            PeerListFilterChip(
                key = pollChoiceKey(hex),
                count = users.count { viewer ->
                    viewer.pollOptionHex.any { it.equals(hex, ignoreCase = true) }
                },
                label = answer.text,
            )
        }
    }
    return users.flatMap { it.pollOptionHex }
        .distinct()
        .map { hex ->
            PeerListFilterChip(
                key = pollChoiceKey(hex),
                count = users.count { viewer ->
                    viewer.pollOptionHex.any { it.equals(hex, ignoreCase = true) }
                },
                label = hex,
            )
        }
}
