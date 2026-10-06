package org.monogram.core.common.push

import org.monogram.core.models.CHANNEL_PEER_OFFSET
import org.monogram.core.models.CompactJson
import org.monogram.core.models.channelPeerId
import org.monogram.core.models.jsonLenientLong
import org.monogram.core.models.jsonMap
import org.monogram.core.models.jsonString
import org.monogram.core.models.jsonStringList

const val CHANNEL_ID_OFFSET: Long = CHANNEL_PEER_OFFSET

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
    Calls,
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
    val topicId: Int? = null,
    val topicTitle: String? = null,
    val senderId: Long? = null,
    val scheduled: Boolean = false,
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
        mention = custom.pushFlag("mention"),
        silent = custom.pushFlag("silent"),
        sound = root.jsonString("sound"),
        attachB64 = custom.jsonString("attachb64"),
        userId = root.jsonLenientLong("user_id"),
        action = actionFor(locKey),
        channelKind = channelKindFor(locKey),
        topicId = (custom.jsonLenientLong("topic_id")?.takeIf { it > 0 }
            ?: custom.jsonLenientLong("top_msg_id"))?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
            ?.toInt(),
        topicTitle = custom.jsonString("topic_title")?.takeIf { it.isNotBlank() },
        senderId = resolveSenderId(custom),
        scheduled = custom.pushFlag("schedule"),
    )
}

fun actionFor(locKey: String): PushAction = when (locKey) {
    "WAKE", "MESSAGE_MUTED", "GEO_LIVE_PENDING", "DC_UPDATE",
    "ENCRYPTED_MESSAGE", "ENCRYPTION_REQUEST", "ENCRYPTION_ACCEPT" -> PushAction.Wake
    "MESSAGE_DELETED", "STORY_DELETED" -> PushAction.Delete
    "READ_HISTORY", "READ_STORIES" -> PushAction.ReadHistory
    "READ_REACTION" -> PushAction.ReadReaction
    "SESSION_REVOKE" -> PushAction.SessionRevoke
    else -> PushAction.Show
}

fun channelKindFor(locKey: String): PushChannelKind = when {
    locKey.startsWith("PHONE_CALL_") || locKey.contains("VOICECHAT") -> PushChannelKind.Calls
    locKey.contains("REACT") -> PushChannelKind.Reactions
    locKey.startsWith("CHANNEL_") -> PushChannelKind.Channel
    locKey.startsWith("CHAT_") -> PushChannelKind.Group
    locKey.startsWith("STORY_") -> PushChannelKind.Stories
    locKey.startsWith("MESSAGE_") ||
        locKey.startsWith("PINNED_") ||
        locKey.startsWith("CONTACT_") ||
        locKey.startsWith("PHONE_") ||
        locKey.startsWith("AUTH_") -> PushChannelKind.Private
    else -> PushChannelKind.Other
}

data class CallNotice(
    val call: Boolean,
    val answer: Boolean,
    val fullScreen: Boolean,
    val categoryCall: Boolean,
    val staysUntilOpened: Boolean,
)

/**
 * Answer, decline, [categoryCall], and full-screen are on only when the app can join that call.
 * Group calls and live streams never get an answer button here.
 */
fun callNotice(locKey: String, canAnswer: Boolean, fullScreenAllowed: Boolean): CallNotice {
    val voiceOrLive = locKey.contains("VOICECHAT") || locKey == "STORY_LIVE"
    val phone = locKey.startsWith("PHONE_CALL_")
    val answerable = canAnswer && locKey == "PHONE_CALL_REQUEST"
    return CallNotice(
        call = phone || voiceOrLive,
        answer = answerable,
        fullScreen = answerable && fullScreenAllowed,
        categoryCall = answerable,
        staysUntilOpened = phone || voiceOrLive,
    )
}

fun resolveChatId(custom: Map<*, *>): Long? {
    custom.jsonLenientLong("chat_id")?.let { if (it != 0L) return -it }
    custom.jsonLenientLong("channel_id")?.let { if (it != 0L) return channelPeerId(it) }
    custom.jsonLenientLong("from_id")?.let { if (it != 0L) return it }
    return null
}

fun resolveSenderId(custom: Map<*, *>): Long? {
    custom.jsonLenientLong("chat_from_broadcast_id")?.takeIf { it != 0L }
        ?.let { return channelPeerId(it) }
    custom.jsonLenientLong("chat_from_group_id")?.takeIf { it != 0L }
        ?.let { return channelPeerId(it) }
    return custom.jsonLenientLong("chat_from_id")?.takeIf { it != 0L }
        ?: custom.jsonLenientLong("from_id")?.takeIf { it != 0L }
}

private fun Map<*, *>.pushFlag(key: String): Boolean =
    jsonLenientLong(key)?.let { it != 0L } ?: (this[key] == true || jsonString(key) == "true")

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

sealed class IncomingPush {
    data object Wake : IncomingPush()
    data class Present(val json: String) : IncomingPush()
    data class Decrypt(val cipher: String) : IncomingPush()
}

/** FCM `data[p]` decrypts or wakes. UnifiedPush accepts plaintext, quoted JSON, `p`, or base64. */
fun normalizeIncomingBody(fcm: Boolean, body: ByteArray?): IncomingPush {
    if (body == null || body.isEmpty()) return IncomingPush.Wake
    if (fcm) {
        val text = utf8OrNull(body)?.trim().orEmpty()
        if (text.isEmpty()) return IncomingPush.Wake
        return IncomingPush.Decrypt(text)
    }
    val text = utf8OrNull(body)?.trim() ?: return IncomingPush.Wake
    return classifyPlainPush(text, 0) ?: IncomingPush.Wake
}

/** Decrypted FCM text presents only when it is a push JSON object; otherwise wake. */
fun normalizeDecrypted(plain: String): IncomingPush =
    classifyPlainPush(plain.trim(), 0) ?: IncomingPush.Wake

private fun classifyPlainPush(text: String, depth: Int): IncomingPush? {
    if (text.isEmpty() || depth > 4) return null
    if (text.startsWith("\"")) {
        val parsed = CompactJson.parse(text)
        if (parsed is String) {
            classifyPlainPush(parsed.trim(), depth + 1)?.let { return it }
        }
        if (text.endsWith("\"") && text.length >= 2) {
            classifyPlainPush(text.substring(1, text.length - 1).trim(), depth + 1)?.let { return it }
        }
        return null
    }
    if (text.startsWith("{")) {
        val map = CompactJson.parse(text) as? Map<*, *> ?: return null
        if (!map.jsonString("loc_key").isNullOrBlank()) return IncomingPush.Present(text)
        val nested = map.jsonMap("data")
        if (!nested?.jsonString("loc_key").isNullOrBlank()) return IncomingPush.Present(text)
        val wrapped = map.jsonString("p")?.trim().orEmpty()
        if (wrapped.isEmpty()) return null
        return classifyPlainPush(wrapped, depth + 1)
    }
    val decoded = decodeBase64Text(text) ?: return null
    return classifyPlainPush(decoded.trim(), depth + 1)
}

private fun utf8OrNull(body: ByteArray): String? = try {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    decoder.decode(java.nio.ByteBuffer.wrap(body)).toString()
} catch (_: java.nio.charset.CharacterCodingException) {
    null
}

private fun decodeBase64Text(text: String): String? {
    if (text.length < 16 || text.length % 4 != 0) return null
    if (!text.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }) {
        return null
    }
    val normalized = text.replace('-', '+').replace('_', '/')
    val decoded = runCatching { java.util.Base64.getDecoder().decode(normalized) }.getOrNull() ?: return null
    return utf8OrNull(decoded)
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
