package org.monogram.network.bridge.session

import org.monogram.core.common.telegram.TelegramError
import uniffi.monogram_mtproto.MtprotoException

/** True when the native client no longer knows the handle this call used. */
internal fun isStaleHandleFailure(error: Exception): Boolean {
    val text = when (error) {
        is MtprotoException.Message -> error.v1
        else -> error.message
    }.orEmpty().lowercase()
    return error is MtprotoException.UnknownClient ||
            text.contains("unknown client") ||
            text.contains("client closed")
}

internal fun nativeExceptionMessage(error: Exception): String = when (error) {
    is MtprotoException.Message -> error.v1
    else -> error.message
}.orEmpty()

internal fun isMissingThumbError(raw: String): Boolean {
    val text = raw.lowercase()
    return text.contains("no downloadable thumb") ||
            text.contains("file_id_invalid") ||
            text.contains("provided file id is invalid")
}

internal fun isFileMigrateError(raw: String): Boolean =
    raw.contains("FILE_MIGRATE", ignoreCase = true)

internal fun nativeFailureLogLine(error: TelegramError, raw: String = ""): String {
    val summary = if (error.recognized) error.logLine()
    else "kind=${error.kind} http=${error.httpCode} recognized=false"
    // Only fixed labels cross this boundary: native errors can contain payloads.
    val lower = raw.lowercase()
    val cause = when {
        (lower.startsWith("rpc ") && !lower.startsWith("rpc timeout")) ||
                (raw.isBlank() && error.recognized) -> "rpc_rejected"
        // Native-side sign-out signal: the authorization died earlier, this call
        // never reached the server. Distinct from a fresh `rpc_rejected` 401.
        SESSION_INVALIDATED.containsMatchIn(raw) -> "session_invalidated"
        "invalid inbound message time" in lower -> "inbound_time"
        "bad_msg_notification" in lower -> "bad_message"
        "msg_key" in lower -> "message_key"
        "invalid mtproto" in lower -> "invalid_packet"
        "unknown mtproto constructor" in lower -> "unknown_constructor"
        "rpc timeout" in lower -> "rpc_timeout"
        "connection closed" in lower -> "socket_closed"
        "reset" in lower -> "socket_reset"
        "os error 11" in lower || "temporarily unavailable" in lower -> "would_block"
        "timed out" in lower -> "io_timeout"
        "connection error" in lower -> "connection_error"
        "cancelled" in lower || "client closed" in lower -> "cancelled"
        error.kind == TelegramError.Kind.Peer -> "peer_resolution"
        else -> "unclassified"
    }
    val constructor = UNKNOWN_NATIVE_CONSTRUCTOR.matchEntire(raw)?.groupValues?.get(1)
    val badMessageCode = BAD_NATIVE_MESSAGE.find(raw)?.groupValues?.get(1)
    val reason = SESSION_INVALIDATED.find(raw)?.groupValues?.get(1)?.uppercase()
    val local = if (error.recognized) null else nativeLocalReason(raw)
    return "$summary cause=$cause" +
            (reason?.let { " reason=$it" } ?: "") +
            (constructor?.let { " constructor=$it" } ?: "") +
            (badMessageCode?.let { " bad_msg_code=$it" } ?: "") +
            (local?.let { " local=$it" } ?: "")
}

internal val LOCAL_REASONS = listOf(
    "no downloadable thumb" to "no_thumb",
    "no display size" to "no_display_size",
    "media deferred" to "media_deferred",
    "invalid media range offset" to "invalid_range",
    "missing media part" to "part_missing",
    "oversized media part" to "part_oversized",
    "unexpected media response" to "unexpected_response",
    "unexpected upload.file" to "unexpected_file",
    "CDN redirect not supported" to "cdn_redirect",
    "no media after file-ref refresh" to "media_index_stale",
    "no media for chat" to "media_index_missing",
    "empty custom emoji documents" to "emoji_empty",
    "custom emoji document empty" to "emoji_empty",
)

internal fun nativeLocalReason(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) return "empty"
    return LOCAL_REASONS
        .firstOrNull { text.contains(it.first, ignoreCase = true) }
        ?.second ?: "unknown"
}

/** `session invalidated: AUTH_KEY_DUPLICATED` -> `AUTH_KEY_DUPLICATED`; fixed tokens only. */
internal val SESSION_INVALIDATED = Regex(
    """session invalidated:\s*([A-Za-z][A-Za-z0-9_]{2,63})""",
    RegexOption.IGNORE_CASE,
)

internal val BAD_NATIVE_MESSAGE = Regex("^bad_msg_notification ([0-9]{1,3})(?:\\s|$)")

internal val UNKNOWN_NATIVE_CONSTRUCTOR = Regex(
    "unknown MTProto constructor (0x[0-9a-fA-F]{8}); updates recovery required",
)