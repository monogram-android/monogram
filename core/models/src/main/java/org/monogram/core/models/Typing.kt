package org.monogram.core.models

enum class ChatActionKind(val wire: String) {
    Typing("typing"),
    RecordAudio("record_audio"),
    UploadAudio("upload_audio"),
    RecordVideo("record_video"),
    UploadVideo("upload_video"),
    UploadPhoto("upload_photo"),
    UploadDocument("upload_document"),
    Geo("geo"),
    Contact("contact"),
    Game("game"),
    RecordRound("record_round"),
    UploadRound("upload_round"),
    ChooseSticker("choose_sticker"),
    Speaking("speaking"),
    WatchingEmoji("watching_emoji"),
    ;

    companion object {
        fun fromWire(value: String?): ChatActionKind =
            entries.firstOrNull { it.wire == value } ?: Typing
    }
}

data class TypingPresence(
    val name: String,
    val action: String = ChatActionKind.Typing.wire,
)

data class DisplayedChatAction(
    val names: List<String>,
    val action: String,
)

sealed class TypingLabel {
    data object Generic : TypingLabel()
    data class One(val name: String) : TypingLabel()
    data class Two(val first: String, val second: String) : TypingLabel()
    data class Many(val count: Int) : TypingLabel()
}

fun typingLabel(names: List<String>): TypingLabel {
    val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return when (cleaned.size) {
        0 -> TypingLabel.Generic
        1 -> TypingLabel.One(cleaned[0])
        2 -> TypingLabel.Two(cleaned[0], cleaned[1])
        else -> TypingLabel.Many(cleaned.size)
    }
}

fun displayedChatAction(
    users: Collection<TypingPresence>,
    named: Boolean,
): DisplayedChatAction? {
    if (users.isEmpty()) return null
    val action = users.last().action.ifBlank { ChatActionKind.Typing.wire }
    val names = if (!named) {
        emptyList()
    } else {
        users.filter { it.action == action }.map { it.name }
    }
    return DisplayedChatAction(names, action)
}

fun packTypingNames(names: List<String>): String? {
    val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return cleaned.takeIf { it.isNotEmpty() }?.joinToString("\u001f")
}

fun unpackTypingNames(packed: String?): List<String> =
    packed?.split('\u001f')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
