package org.monogram.core.models

/** Chat-list last-message line. Media labels stay caller-supplied for localization. */
fun formatChatListPreview(
    isGroup: Boolean,
    isChannel: Boolean,
    outgoing: Boolean,
    senderName: String?,
    mediaKind: String?,
    text: String?,
    youLabel: String = "You",
    mediaLabel: (String) -> String? = ::chatListMediaLabel,
    someoneLabel: String = "Someone",
    serviceTemplate: ((String) -> String)? = null,
): String {
    val caption = text?.trim()?.takeIf { it.isNotEmpty() }
    if (mediaKind == "service" || (caption != null && parseServiceMessage(caption) != null)) {
        val template = serviceTemplate ?: return caption.orEmpty()
        return formatServiceMessage(
            raw = caption.orEmpty(),
            youLabel = youLabel,
            outgoing = outgoing,
            someoneLabel = someoneLabel,
            templateFor = template,
        )
    }
    val media = mediaKind?.let(mediaLabel)?.takeIf { it.isNotEmpty() }
    val body = when {
        media != null && caption != null -> "$media, $caption"
        media != null -> media
        caption != null -> caption
        else -> ""
    }
    // Broadcast channels have no per-sender prefix in the chat list.
    if (!isGroup || isChannel) return body
    val sender = when {
        outgoing -> youLabel
        !senderName.isNullOrBlank() -> senderName
        else -> null
    }
    return when {
        sender != null && body.isNotEmpty() -> "$sender: $body"
        sender != null -> "$sender:"
        else -> body
    }
}

/**
 * Media kinds whose file name is a payload or a generated name, never user text. Only documents
 * and audio albums carry a name worth previewing.
 */
private val generatedFileNameKinds = setOf(
    "photo",
    "video",
    "gif",
    "sticker",
    "sticker_animated",
    "sticker_video",
    "voice",
    "webpage",
    "todo",
    "poll",
    "geo",
    "venue",
    "contact",
    "dice",
)

private val stickerFileSuffixes = listOf(".webp", ".webm", ".tgs")

private val generatedMediaExtensions = setOf(
    "webp",
    "webm",
    "tgs",
    "gif",
    "mp4",
    "mov",
    "mkv",
    "jpg",
    "jpeg",
    "png",
    "heic",
    "ogg",
    "opus",
    "mp3",
    "m4a",
    "aac",
    "wav",
)

/** Sticker and animation names Telegram generates; they never carry user text. */
fun isStickerFileName(name: String?): Boolean {
    val lower = name?.trim()?.lowercase().orEmpty()
    return stickerFileSuffixes.any(lower::endsWith)
}

/** True when a media file name may stand in as the preview text. */
internal fun previewUsesFileName(mediaKind: String?, fileName: String): Boolean =
    !isStickerFileName(fileName) && mediaKind !in generatedFileNameKinds

/** True when a stored caption is really a generated media name such as `VID_20200803.mp4`. */
internal fun isGeneratedMediaName(text: String): Boolean {
    val name = text.trim()
    if (name.isEmpty() || name.length > 80 || name.contains(':')) return false
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.lastIndex) return false
    return name.substring(dot + 1).lowercase() in generatedMediaExtensions
}

fun Message.chatListPreviewSource(): String? =
    text?.trim()?.takeIf { it.isNotEmpty() }
        ?: fileName?.trim()?.takeIf { it.isNotEmpty() && previewUsesFileName(mediaKind, it) }

fun chatListMediaLabel(kind: String): String? = when (kind) {
    "photo" -> "Photo"
    "video" -> "Video"
    "gif" -> "GIF"
    "sticker", "sticker_animated", "sticker_video" -> "Sticker"
    "document" -> "Document"
    "audio" -> "Audio"
    "voice" -> "Voice message"
    "todo" -> "Checklist"
    "webpage" -> "Link"
    "poll" -> "Poll"
    "geo" -> "Location"
    "venue" -> "Venue"
    "contact" -> "Contact"
    "dice" -> "Dice"
    else -> null
}

fun Chat.displayPreview(
    youLabel: String = "You",
    mediaLabel: (String) -> String? = ::chatListMediaLabel,
    someoneLabel: String = "Someone",
    serviceTemplate: ((String) -> String)? = null,
): String {
    val preview = lastMessagePreview.orEmpty()
    if (lastMessageMediaKind == "service" || parseServiceMessage(preview) != null) {
        val template = serviceTemplate ?: return preview
        return formatServiceMessage(
            raw = preview,
            youLabel = youLabel,
            outgoing = lastMessageOutgoing,
            someoneLabel = someoneLabel,
            templateFor = template,
        )
    }
    val structured = lastMessageOutgoing ||
        !lastMessageSenderName.isNullOrBlank() ||
        !lastMessageMediaKind.isNullOrBlank()
    if (!structured) return preview
    composedMediaPreview(
        preview = preview,
        mediaKind = lastMessageMediaKind,
        isGroup = isGroup && !isChannel,
        mediaLabel = mediaLabel,
    )?.let { return it }
    return formatChatListPreview(
        isGroup = isGroup,
        isChannel = isChannel,
        outgoing = lastMessageOutgoing,
        senderName = lastMessageSenderName,
        mediaKind = lastMessageMediaKind,
        text = unwrapChatListCaption(
            preview = lastMessagePreview,
            mediaKind = lastMessageMediaKind,
            outgoing = lastMessageOutgoing,
            senderName = lastMessageSenderName,
            youLabel = youLabel,
            mediaLabel = mediaLabel,
        ),
        youLabel = youLabel,
        mediaLabel = mediaLabel,
        someoneLabel = someoneLabel,
        serviceTemplate = serviceTemplate,
    )
}

/** Telegram hides the obsolete migration dialog once a basic group became a supergroup. */
fun Chat.isMigratedServicePlaceholder(): Boolean =
    (isGroup || isChannel) && (
        parseServiceMessage(lastMessagePreview.orEmpty())?.kind == "migrate_to" ||
            lastMessagePreview.orEmpty().trim().equals("This group was upgraded to a supergroup", ignoreCase = true) ||
            lastMessagePreview.orEmpty().trim().equals("Группа преобразована в супергруппу", ignoreCase = true)
        )

/**
 * Rust composes media previews itself (`"<sender>: Photo, caption"` in groups and
 * `"Photo, caption"` elsewhere), and the DTO carries no sender name, so that stored line is
 * already the display form and must not be re-labelled here. Null when the line is a plain
 * caption that still needs the label.
 */
internal fun composedMediaPreview(
    preview: String,
    mediaKind: String?,
    isGroup: Boolean,
    mediaLabel: (String) -> String? = ::chatListMediaLabel,
): String? {
    val label = mediaKind?.let(mediaLabel)?.takeIf { it.isNotEmpty() } ?: return null
    val text = preview.trim()
    if (text.isEmpty()) return null
    // Group lines carry the sender before the label; channels and DMs do not.
    val colon = if (isGroup) text.indexOf(':') else -1
    if (isGroup && colon <= 0) return null
    val sender = text.substring(0, if (colon > 0) colon + 1 else 0)
    val body = text.substring(sender.length).trimStart()
    if (!body.equals(label, ignoreCase = true) && !body.startsWith("$label,", ignoreCase = true)) {
        return null
    }
    val caption = body.substring(label.length).removePrefix(",").trim()
    val visible = caption.takeIf {
        it.isNotEmpty() && !(mediaKind in generatedFileNameKinds && isGeneratedMediaName(it))
    }
    val head = if (sender.isEmpty()) label else "$sender $label"
    return if (visible != null) "$head, $visible" else head
}

internal fun unwrapChatListCaption(
    preview: String?,
    mediaKind: String?,
    outgoing: Boolean,
    senderName: String?,
    youLabel: String = "You",
    mediaLabel: (String) -> String? = ::chatListMediaLabel,
): String? {
    var text = preview?.trim().orEmpty()
    if (text.isEmpty()) return null
    val tokens = buildList {
        if (outgoing) add(youLabel)
        add("You")
        senderName?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        mediaKind?.let { mediaLabel(it) }?.let { add(it) }
    }.distinct()
    repeat(8) {
        text = stripOnePreviewToken(text, tokens) ?: return text.takeIf { it.isNotEmpty() }
    }
    return text.takeIf { it.isNotEmpty() }
}

private fun stripOnePreviewToken(text: String, tokens: List<String>): String? {
    val trimmed = text.trimStart()
    for (token in tokens) {
        if (token.isBlank()) continue
        val colon = "$token:"
        if (trimmed.startsWith(colon, ignoreCase = true)) {
            return trimmed.substring(colon.length).trimStart()
        }
        val comma = "$token, "
        if (trimmed.startsWith(comma, ignoreCase = true)) {
            return trimmed.substring(comma.length).trimStart()
        }
        if (trimmed.equals(token, ignoreCase = true)) {
            return ""
        }
    }
    return null
}
