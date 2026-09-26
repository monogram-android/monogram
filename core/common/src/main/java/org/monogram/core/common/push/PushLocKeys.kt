package org.monogram.core.common.push

/**
 * Templates from https://core.telegram.org/api/push-updates.
 * `{n}` is 1-based loc_args. Unknown keys join the arguments.
 */
internal val PUSH_TEMPLATES: Map<String, String> = mapOf(
    "MESSAGE_TEXT" to "{1}: {2}",
    "MESSAGE_NOTEXT" to "{1} sent a message",
    "MESSAGE_PHOTO" to "{1} sent a photo",
    "MESSAGE_VIDEO" to "{1} sent a video",
    "MESSAGE_DOC" to "{1} sent a file",
    "MESSAGE_GIF" to "{1} sent a GIF",
    "MESSAGE_AUDIO" to "{1} sent a voice message",
    "MESSAGE_ROUND" to "{1} sent a video message",
    "MESSAGE_STICKER" to "{1} sent a {2} sticker",
    "MESSAGE_CONTACT" to "{1} shared a contact {2}",
    "MESSAGE_GEO" to "{1} sent a location",
    "MESSAGE_GEOLIVE" to "{1} sent a live location",
    "MESSAGE_POLL" to "{1} sent a poll {2}",
    "MESSAGE_QUIZ" to "{1} sent a quiz {2}",
    "MESSAGE_GAME" to "{1} invited you to play {2}",
    "CHAT_MESSAGE_TEXT" to "{1} @ {2}: {3}",
    "CHAT_MESSAGE_NOTEXT" to "{1} sent a message in {2}",
    "CHAT_MESSAGE_PHOTO" to "{1} sent a photo in {2}",
    "CHAT_MESSAGE_VIDEO" to "{1} sent a video in {2}",
    "CHAT_MESSAGE_DOC" to "{1} sent a file in {2}",
    "CHAT_MESSAGE_GIF" to "{1} sent a GIF in {2}",
    "CHAT_MESSAGE_AUDIO" to "{1} sent a voice message in {2}",
    "CHAT_MESSAGE_ROUND" to "{1} sent a video message in {2}",
    "CHAT_MESSAGE_STICKER" to "{1} sent a {3} sticker in {2}",
    "CHAT_MESSAGE_CONTACT" to "{1} shared a contact in {2}",
    "CHAT_ADD_YOU" to "{1} invited you to the group {2}",
    "CHAT_ADD_MEMBER" to "{1} invited {3} to the group {2}",
    "CHAT_DELETE_YOU" to "{1} removed you from the group {2}",
    "CHAT_DELETE_MEMBER" to "{1} removed {3} from the group {2}",
    "CHAT_LEFT" to "{1} left the group {2}",
    "CHAT_RETURNED" to "{1} returned to the group {2}",
    "CHAT_TITLE" to "{1} renamed the group {2}",
    "CHAT_PHOTO" to "{1} changed the group photo for {2}",
    "CHANNEL_MESSAGE_TEXT" to "{1}: {2}",
    "CHANNEL_MESSAGE_NOTEXT" to "{1} posted a message",
    "CHANNEL_MESSAGE_PHOTO" to "{1} posted a photo",
    "CHANNEL_MESSAGE_VIDEO" to "{1} posted a video",
    "CHANNEL_MESSAGE_DOC" to "{1} posted a file",
    "CHANNEL_MESSAGE_GIF" to "{1} posted a GIF",
    "CHANNEL_MESSAGE_AUDIO" to "{1} posted a voice message",
    "CHANNEL_MESSAGE_ROUND" to "{1} posted a video message",
    "CHANNEL_MESSAGE_STICKER" to "{1} posted a {2} sticker",
    "CHANNEL_MESSAGE_CONTACT" to "{1} posted a contact {2}",
    "CHANNEL_MESSAGE_GEO" to "{1} posted a location",
    "CHANNEL_MESSAGE_GEOLIVE" to "{1} posted a live location",
    "CHANNEL_MESSAGE_POLL" to "{1} posted a poll {2}",
    "CHANNEL_MESSAGE_QUIZ" to "{1} posted a quiz {2}",
    "CHANNEL_MESSAGES" to "{1} posted an album",
    "PINNED_TEXT" to "{1} pinned {2}",
    "PINNED_NOTEXT" to "{1} pinned a message",
    "CONTACT_JOINED" to "{1} joined Telegram",
    "AUTH_UNKNOWN" to "New login from unrecognized device {1}",
    "AUTH_REGION" to "New login from unrecognized device {1}, location: {2}",
    "PHONE_CALL_REQUEST" to "{1} is calling you",
    "PHONE_CALL_MISSED" to "Missed call from {1}",
    "ENCRYPTION_REQUEST" to "{1} wants to start a secret chat",
    "ENCRYPTED_MESSAGE" to "{1} sent a secret message",
    "STORY_NOTEXT" to "posted a story",
    "STORY_LIVE" to "started a live stream!",
    "STORY_HIDDEN_AUTHOR" to "A new story was posted",
    "MESSAGE_ANNOUNCEMENT" to "{1}",
    "REACT_TEXT" to "{1} reacted {2} to {3}",
    "CHAT_REACT_TEXT" to "{1} reacted {2} in {3}",
    "CHANNEL_REACT_TEXT" to "{1} reacted {2}",
)

fun formatLocKey(locKey: String, args: List<String>): Pair<String, String> {
    if (locKey == "WAKE" || locKey.isBlank()) return "Telegram" to "New activity"
    val template = PUSH_TEMPLATES[locKey]
    val body = if (template != null) applyTemplate(template, args) else fallbackBody(locKey, args)
    val part = locKey.substringBefore('_')
    val fallbackTitle = part.take(1).uppercase() + part.drop(1).lowercase()
    val firstArg = args.firstOrNull()
    val title = if (firstArg == null || firstArg.isBlank()) fallbackTitle else firstArg
    val remainder = stripTitle(body, title, template != null && args.isNotEmpty())
    val safeTitle = if (title.isBlank()) "Telegram" else title
    val safeBody = if (remainder.isBlank()) body else remainder
    return safeTitle to safeBody
}

private fun applyTemplate(template: String, args: List<String>): String {
    val out = StringBuilder(template.length)
    var i = 0
    while (i < template.length) {
        val placeholder = i + 2 < template.length &&
            template[i] == '{' &&
            template[i + 2] == '}' &&
            template[i + 1] in '1'..'9'
        if (placeholder) {
            val index = template[i + 1] - '1'
            if (index in args.indices) out.append(args[index]) else out.append(template, i, i + 3)
            i += 3
        } else {
            out.append(template[i])
            i += 1
        }
    }
    return out.toString()
}

private fun fallbackBody(locKey: String, args: List<String>): String {
    val joined = args.joinToString(" ")
    return if (joined.isNotBlank()) joined else locKey.lowercase().replace('_', ' ')
}

private fun stripTitle(body: String, title: String, enabled: Boolean): String {
    if (!enabled || !body.startsWith(title)) return body
    val stripped = body.substring(title.length).trimStart(':', ' ', '@')
    return if (stripped.isNotBlank()) stripped else body
}
