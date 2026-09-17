package org.monogram.core.common.push

import org.monogram.core.models.CompactJson
import org.monogram.core.models.jsonLenientLong
import org.monogram.core.models.jsonMap
import org.monogram.core.models.jsonString
import org.monogram.core.models.jsonStringList

const val CHANNEL_ID_OFFSET: Long = 1_000_000_000_000L

enum class PushAction {
    Show,
    Wake,
    Delete,
    ReadHistory,
    ReadReaction,
    SessionRevoke,
    Ignore,
}

enum class PushChannelKind {
    Private,
    Group,
    Channel,
    Stories,
    Reactions,
    Other,
}

data class PushPayload(
    val locKey: String,
    val locArgs: List<String>,
    val title: String,
    val body: String,
    val chatId: Long?,
    val messageId: Int?,
    val maxId: Int?,
    val deletedIds: List<Int>,
    val mention: Boolean,
    val silent: Boolean,
    val sound: String?,
    /** `custom.attachb64`: base64url TL blob of the Photo/Document for a media message. */
    val attachB64: String?,
    val userId: Long?,
    val action: PushAction,
    val channelKind: PushChannelKind,
) {
    val customIds: String
        get() = buildString {
            chatId?.let { append("chat=").append(it) }
            messageId?.let {
                if (isNotEmpty()) append(' ')
                append("msg=").append(it)
            }
        }
}

fun parsePushPayload(json: String): PushPayload {
    val root = unwrapData(json.trim())
    val locKey = root.jsonString("loc_key").orEmpty().ifBlank { "WAKE" }
    val locArgs = root.jsonStringList("loc_args")
    val custom = root.jsonMap("custom") ?: root
    val chatId = resolveChatId(custom)
    val messageId = custom.jsonLenientLong("msg_id")?.toInt()
    val maxId = custom.jsonLenientLong("max_id")?.toInt()
    val deleted = custom.jsonString("messages")
        ?.split(',')
        ?.mapNotNull { it.trim().toIntOrNull() }
        .orEmpty()
    val formatted = formatLocKey(locKey, locArgs)
    return PushPayload(
        locKey = locKey,
        locArgs = locArgs,
        title = formatted.first,
        body = formatted.second,
        chatId = chatId,
        messageId = messageId,
        maxId = maxId,
        deletedIds = deleted,
        mention = custom.jsonLenientLong("mention") == 1L || custom.jsonString("mention") == "true",
        silent = custom.jsonLenientLong("silent") == 1L || custom.jsonString("silent") == "true",
        sound = root.jsonString("sound"),
        attachB64 = custom.jsonString("attachb64"),
        userId = root.jsonLenientLong("user_id"),
        action = actionFor(locKey),
        channelKind = channelKindFor(locKey),
    )
}

fun actionFor(locKey: String): PushAction = when (locKey) {
    "WAKE", "MESSAGE_MUTED", "GEO_LIVE_PENDING", "DC_UPDATE" -> PushAction.Wake
    "MESSAGE_DELETED", "STORY_DELETED" -> PushAction.Delete
    "READ_HISTORY", "READ_STORIES" -> PushAction.ReadHistory
    "READ_REACTION" -> PushAction.ReadReaction
    "SESSION_REVOKE" -> PushAction.SessionRevoke
    else -> PushAction.Show
}

fun channelKindFor(locKey: String): PushChannelKind = when {
    locKey.startsWith("CHANNEL_") -> PushChannelKind.Channel
    locKey.startsWith("CHAT_") -> PushChannelKind.Group
    locKey.startsWith("STORY_") -> PushChannelKind.Stories
    locKey.contains("REACT") -> PushChannelKind.Reactions
    locKey.startsWith("MESSAGE_") ||
        locKey.startsWith("PINNED_") ||
        locKey.startsWith("CONTACT_") ||
        locKey.startsWith("PHONE_") ||
        locKey.startsWith("AUTH_") -> PushChannelKind.Private
    else -> PushChannelKind.Other
}

fun resolveChatId(custom: Map<*, *>): Long? {
    custom.jsonLenientLong("from_id")?.let { if (it != 0L) return it }
    custom.jsonLenientLong("chat_id")?.let { if (it != 0L) return -it }
    custom.jsonLenientLong("channel_id")?.let { if (it != 0L) return -(CHANNEL_ID_OFFSET + it) }
    return null
}

fun redactToken(token: String): String {
    val value = token.trim()
    if (value.length <= 8) return if (value.isEmpty()) "" else "••••"
    return value.take(4) + "…" + value.takeLast(4)
}

/**
 * FCM payloads nest the fields under `data` when present; otherwise the body is already the
 * field map. Malformed input yields an empty map so every field falls back to its default.
 */
internal fun unwrapData(json: String): Map<*, *> {
    val root = CompactJson.parse(json) as? Map<*, *> ?: return emptyMap<String, Any?>()
    return root.jsonMap("data") ?: root
}

fun notificationHttpUrl(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.any { it.isWhitespace() }) return null
    val http = trimmed.startsWith("http://", ignoreCase = true)
    val https = trimmed.startsWith("https://", ignoreCase = true)
    if (!http && !https) return null
    val host = trimmed.substringAfter("://")
    if (host.isBlank() || '.' !in host) return null
    return trimmed
}
