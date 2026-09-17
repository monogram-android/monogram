package org.monogram.core.common.telegram

import java.util.concurrent.ConcurrentHashMap

/**
 * Typed Telegram RPC / transport error.
 *
 * Every official `errors.json` code is recognized via [TelegramErrorCatalog].
 * Unknown codes still produce a [Kind.Generic] instance instead of a raw string.
 */
data class TelegramError(
    val httpCode: Int,
    val type: String,
    val pattern: String?,
    val argument: Int?,
    val kind: Kind,
    val description: String,
    val recognized: Boolean,
    val retryAfterSeconds: Int? = null,
    val migrateDcId: Int? = null,
) {
    val message: String get() = description.ifBlank { type.ifBlank { "Request failed" } }

    fun logLine(): String = buildString {
        append("kind=").append(kind)
        append(" http=").append(httpCode)
        append(" type=").append(type)
        if (recognized) append(" catalog=1") else append(" catalog=0")
        argument?.let { append(" arg=").append(it) }
        retryAfterSeconds?.let { append(" wait=").append(it) }
        migrateDcId?.let { append(" dc=").append(it) }
    }

    val canAutoRetry: Boolean
        get() = kind == Kind.Flood && (retryAfterSeconds ?: 0) in 1..SHORT_FLOOD_RETRY_SECONDS

    /** Main-DC 401 session death requires re-login. AUTH_KEY_PERM_EMPTY is PFS, not logout. */
    val requiresReauth: Boolean
        get() = kind == Kind.Session &&
            type != "AUTH_KEY_UNSYNCHRONIZED" &&
            type != "AUTH_KEY_PERM_EMPTY"

    enum class Kind {
        Flood,
        Auth,
        Session,
        Network,
        FileReference,
        Peer,
        Media,
        Privacy,
        SeeOther,
        NotFound,
        NotAcceptable,
        Internal,
        Generic,
    }

    companion object {
        const val CATALOG_LAYER: Int = TelegramErrorCatalog.LAYER
        const val CATALOG_SIZE: Int = TelegramErrorCatalog.SIZE
        const val SHORT_FLOOD_RETRY_SECONDS: Int = 5

        fun parse(throwable: Throwable, fallback: String = "Request failed"): TelegramError {
            val raw = throwable.message?.removePrefix("v1=")?.trim().orEmpty()
            return parse(raw.ifBlank { fallback }, fallback)
        }

        fun parse(raw: String, fallback: String = "Request failed"): TelegramError {
            val trimmed = raw.trim().removePrefix("v1=").trim()
            localSkip(trimmed)?.let { return it }
            parseSessionInvalidated(trimmed)?.let { return it }
            transportKind(trimmed)?.let { kind ->
                val hit = match("Timeout") ?: match("MSG_WAIT_TIMEOUT")
                val row = hit?.first
                return TelegramError(
                    httpCode = row?.httpCode ?: 0,
                    type = row?.pattern ?: "NETWORK",
                    pattern = row?.pattern,
                    argument = hit?.second,
                    kind = kind,
                    description = row?.let { formatDescription(it, hit.second) }
                        ?: "Telegram took too long to respond. Try again.",
                    recognized = row != null,
                )
            }

            val rpc = RPC_PREFIX.matchEntire(trimmed)
            val httpFromRpc = rpc?.groupValues?.get(1)?.toIntOrNull()
            val typeRaw = (rpc?.groupValues?.get(2) ?: trimmed).trim()
            val type = typeRaw.ifBlank { fallback }

            val match = match(type)
            val argument = match?.second
            val row = match?.first
            val http = httpFromRpc ?: row?.httpCode ?: 400
            val resolvedType = type.uppercase().ifBlank { row?.pattern ?: "UNKNOWN" }
            val kind = classify(http, resolvedType)
            val description = when {
                row != null -> formatDescription(row, argument)
                type.isNotBlank() && type != fallback -> type
                else -> fallback
            }
            val retry = argument.takeIf { kind == Kind.Flood || resolvedType.contains("WAIT") }
            val migrate = argument.takeIf { kind == Kind.SeeOther }
            return TelegramError(
                httpCode = http,
                type = resolvedType,
                pattern = row?.pattern,
                argument = argument,
                kind = kind,
                description = description,
                recognized = row != null,
                retryAfterSeconds = retry,
                migrateDcId = migrate,
            )
        }

        fun classify(httpCode: Int, type: String): Kind {
            val t = type.uppercase()
            if (t.contains("MIGRATE")) return Kind.SeeOther
            if (httpCode == 420 ||
                t.contains("FLOOD_WAIT") ||
                t.contains("FLOOD_PREMIUM") ||
                t.contains("SLOWMODE_WAIT") ||
                t.contains("2FA_CONFIRM_WAIT") ||
                t.contains("TAKEOUT_INIT_DELAY") ||
                t.contains("STORY_SEND_FLOOD")
            ) {
                return Kind.Flood
            }
            if (t.contains("FILE_REFERENCE")) return Kind.FileReference
            if (t.contains("AUTH_KEY") ||
                t == "SESSION_REVOKED" ||
                t == "SESSION_EXPIRED" ||
                t == "USER_DEACTIVATED" ||
                t == "USER_DEACTIVATED_BAN" ||
                t == "AUTH_KEY_DUPLICATED"
            ) {
                return Kind.Session
            }
            if (t.startsWith("PHONE_") ||
                t.startsWith("PASSWORD_") ||
                t.contains("AUTH_RESTART") ||
                t == "SESSION_PASSWORD_NEEDED" ||
                t.startsWith("CODE_") ||
                t.startsWith("API_ID") ||
                t.contains("SIGN_UP") ||
                t == "PHONE_NUMBER_UNOCCUPIED"
            ) {
                return Kind.Auth
            }
            if (t.contains("PEER_ID") ||
                t.contains("CHAT_ID") ||
                t.contains("UNKNOWN PEER") ||
                t == "USER_ID_INVALID" ||
                t.contains("CHANNEL_INVALID") ||
                t.contains("CHANNEL_ID") ||
                t == "CHAT_INVALID" ||
                t == "INPUT_USER_DEACTIVATED" ||
                t == "OFFSET_PEER_ID_INVALID"
            ) {
                return Kind.Peer
            }
            if (t.contains("FILE_PART") ||
                t.startsWith("MEDIA_") ||
                t.startsWith("PHOTO_") ||
                t.startsWith("VIDEO_") ||
                t.startsWith("STICKER_") ||
                t.startsWith("CDN_") ||
                t.startsWith("FILE_TOKEN") ||
                t.startsWith("FILE_ID") ||
                t.startsWith("FILE_TITLE")
            ) {
                return Kind.Media
            }
            if (httpCode == 403 ||
                t.contains("PRIVACY") ||
                t == "CHAT_WRITE_FORBIDDEN" ||
                t == "CHAT_ADMIN_REQUIRED" ||
                t.contains("USER_BANNED") ||
                t == "CHANNEL_PRIVATE" ||
                t == "CHAT_FORBIDDEN" ||
                t == "CHAT_RESTRICTED" ||
                t.contains("USER_BLOCKED")
            ) {
                return Kind.Privacy
            }
            if (httpCode == 404) return Kind.NotFound
            if (httpCode == 406) return Kind.NotAcceptable
            if (httpCode == 500 || httpCode == -503) return Kind.Internal
            if (httpCode == 303) return Kind.SeeOther
            return Kind.Generic
        }

        /**
         * Sign-out signal produced by the native layer, never by the server.
         *
         * The authorization was already invalidated (e.g. `406 AUTH_KEY_DUPLICATED`),
         * so later calls fail locally with the reason recorded when it died. Keeping
         * the real token is what makes logs point at the cause instead of a fake 401.
         */
        fun sessionInvalidated(reason: String? = null): TelegramError {
            val token = reason?.trim()?.takeIf { SESSION_REASON.matches(it) } ?: SESSION_INVALIDATED_TYPE
            return parse("$SESSION_INVALIDATED_PREFIX $token")
        }

        private fun parseSessionInvalidated(raw: String): TelegramError? {
            val token = SESSION_INVALIDATED.matchEntire(raw)?.groupValues?.get(1) ?: return null
            val row = match(token)?.first
            return TelegramError(
                httpCode = row?.httpCode ?: 401,
                type = row?.pattern ?: token.uppercase(),
                pattern = row?.pattern,
                argument = null,
                kind = Kind.Session,
                description = row?.let { formatDescription(it, null) } ?: SESSION_INVALIDATED_HINT,
                recognized = row != null,
            )
        }

        private fun localSkip(raw: String): TelegramError? {
            val lower = raw.lowercase()
            val type = when {
                lower.contains("no downloadable thumb") -> "NO_THUMB"
                lower == "media deferred" -> "MEDIA_DEFERRED"
                else -> return null
            }
            return TelegramError(
                httpCode = 0,
                type = type,
                pattern = null,
                argument = null,
                kind = Kind.Media,
                description = raw,
                recognized = false,
            )
        }

        private fun transportKind(raw: String): Kind? {
            val lower = raw.lowercase()
            if (lower.contains("unknown mtproto constructor") ||
                lower.contains("updates recovery required") ||
                lower.contains("updates queue overflow") ||
                lower.contains("invalid mtproto") ||
                lower.contains("msg_key") ||
                lower.contains("invalid inbound message")
            ) {
                return Kind.Network
            }
            if (lower.contains("rpc timeout") ||
                lower == "timeout" ||
                lower.contains("connection closed") ||
                lower.contains("connection error") ||
                lower.contains("failed to connect") ||
                lower.contains("network is unreachable") ||
                lower.contains("os error")
            ) {
                return Kind.Network
            }
            return null
        }
    }
}

private val RPC_PREFIX = Regex("""^RPC\s+(-?\d+)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)

private const val SESSION_INVALIDATED_PREFIX = "session invalidated:"
private const val SESSION_INVALIDATED_TYPE = "SESSION_INVALIDATED"
private const val SESSION_INVALIDATED_HINT = "Session invalidated. Sign in again."

internal val SESSION_INVALIDATED = Regex(
    """^$SESSION_INVALIDATED_PREFIX\s*([A-Za-z][A-Za-z0-9_]{2,63})$""",
    RegexOption.IGNORE_CASE,
)

private val SESSION_REASON = Regex("^[A-Za-z][A-Za-z0-9_]{2,63}$")

private val exactIndex: Map<String, TelegramErrorCatalog.Row> by lazy {
    TelegramErrorCatalog.rows.associateBy { it.pattern.uppercase() }
}

private val parameterized: List<Pair<Regex, TelegramErrorCatalog.Row>> by lazy {
    TelegramErrorCatalog.rows
        .filter { it.pattern.contains("%d") }
        .sortedByDescending { it.pattern.length }
        .map { row -> patternToRegex(row.pattern) to row }
}

private val regexCache = ConcurrentHashMap<String, Regex>()

private fun patternToRegex(pattern: String): Regex =
    regexCache.getOrPut(pattern) {
        val body = pattern.split("%d").joinToString("(\\d+)") { Regex.escape(it) }
        Regex("^$body$", RegexOption.IGNORE_CASE)
    }

private fun match(type: String): Pair<TelegramErrorCatalog.Row, Int?>? {
    val key = type.trim()
    if (key.isEmpty()) return null
    exactIndex[key.uppercase()]?.let { return it to extractSingleInt(key) }
    for ((regex, row) in parameterized) {
        val found = regex.matchEntire(key) ?: continue
        val arg = found.groupValues.getOrNull(1)?.toIntOrNull()
        return row to arg
    }
    return null
}

private fun extractSingleInt(type: String): Int? {
    val found = DIGITS.findAll(type).take(2).toList()
    return if (found.size == 1) found[0].value.toIntOrNull() else null
}

private fun formatDescription(row: TelegramErrorCatalog.Row, argument: Int?): String {
    val raw = row.description.ifBlank { row.pattern }
    val filled = if (argument != null && raw.contains("%d")) {
        raw.replace("%d", argument.toString())
    } else {
        raw
    }
    return sanitizeDescription(filled)
}

private val MARKDOWN_LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
private val HTML_TAG = Regex("</?[^>]+>")
private val WHITESPACE = Regex("\\s+")
private val DIGITS = Regex("(\\d+)")

internal fun sanitizeDescription(raw: String): String =
    raw
        .replace(MARKDOWN_LINK, "$1")
        .replace("**", "")
        .replace("&raquo;", ">>")
        .replace("&nbsp;", " ")
        .replace(HTML_TAG, "")
        .replace(WHITESPACE, " ")
        .trim()
