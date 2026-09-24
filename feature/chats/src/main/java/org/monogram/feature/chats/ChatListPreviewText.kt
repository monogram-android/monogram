package org.monogram.feature.chats

import org.monogram.core.models.Chat
import org.monogram.core.models.displayPreview

/** Last-message media kinds that get a leading icon (and a label) in a chat row. */
enum class ChatPreviewMedia {
    Photo,
    Video,
    Gif,
    Sticker,
    Voice,
    Audio,
    Document,
    Link,
    Checklist,
}

fun chatPreviewMedia(kind: String?): ChatPreviewMedia? = when (kind) {
    "photo" -> ChatPreviewMedia.Photo
    "video" -> ChatPreviewMedia.Video
    "gif" -> ChatPreviewMedia.Gif
    "sticker", "sticker_animated", "sticker_video" -> ChatPreviewMedia.Sticker
    "voice" -> ChatPreviewMedia.Voice
    "video_note" -> ChatPreviewMedia.Video
    "audio" -> ChatPreviewMedia.Audio
    "document" -> ChatPreviewMedia.Document
    "webpage" -> ChatPreviewMedia.Link
    "todo" -> ChatPreviewMedia.Checklist
    else -> null
}

/**
 * Chat-list preview line.
 *
 * A link message whose whole body is the URL itself shows the media label only (`Link`), never the
 * raw URL. Callers pass localized labels so the formatting stays testable and resource-free.
 */
fun chatListPreviewText(
    chat: Chat,
    youLabel: String,
    someoneLabel: String,
    mediaLabel: (String) -> String?,
    serviceTemplate: ((String) -> String)?,
): String {
    val body = chat.lastMessagePreview?.trim().orEmpty()
    val source = if (chat.lastMessageMediaKind == "webpage" && body.isNakedUrl()) {
        chat.copy(lastMessagePreview = null)
    } else {
        chat
    }
    return source.displayPreview(
        youLabel = youLabel,
        mediaLabel = mediaLabel,
        someoneLabel = someoneLabel,
        serviceTemplate = serviceTemplate,
    )
}

/**
 * Splits the `You: ` prefix off a preview so the row can draw the sender label lighter than the
 * message body. Sender names inside groups stay part of the body.
 */
fun splitPreviewSender(preview: String, youLabel: String): Pair<String?, String> {
    val prefix = "$youLabel:"
    if (preview.length <= prefix.length) return null to preview
    if (!preview.startsWith(prefix, ignoreCase = true)) return null to preview
    val rest = preview.substring(prefix.length)
    if (rest.isNotEmpty() && !rest.first().isWhitespace()) return null to preview
    return prefix to rest.trimStart()
}

/**
 * Up to [limit] chat titles for a header line (the archive entry), in list order (pinned first,
 * then most recent). Blank titles fall back to [untitledLabel]. Null when there is nothing to show.
 */
fun archivePreviewTitles(
    chats: List<Chat>,
    untitledLabel: String,
    limit: Int = 3,
): String? {
    if (chats.isEmpty() || limit <= 0) return null
    return chats.take(limit)
        .map { chat -> chat.title.trim().ifEmpty { untitledLabel } }
        .joinToString(", ")
}

/** True when the whole text is one link, so the label alone carries the meaning. */
fun String.isNakedUrl(): Boolean {
    val text = trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return false
    if (text.startsWith("http://", true) ||
        text.startsWith("https://", true) ||
        text.startsWith("tg://", true) ||
        text.startsWith("www.", true)
    ) {
        return true
    }
    return BARE_DOMAIN.matches(text)
}

private val BARE_DOMAIN = Regex(
    "^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?" +
        "(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*" +
        "\\.[A-Za-z]{2,}([/?#]\\S*)?$",
)
