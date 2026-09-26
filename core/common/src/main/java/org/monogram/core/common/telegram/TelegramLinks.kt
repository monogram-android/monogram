package org.monogram.core.common.telegram

import org.monogram.core.common.push.CHANNEL_ID_OFFSET

/**
 * Parses `t.me` / `tg:` deep links.
 * https://core.telegram.org/api/links
 *
 * Extra HTTPS hosts come from `help.getConfig.me_url_prefix` (default `t.me`).
 */
sealed class TelegramLink {
    data class Username(
        val username: String,
        val messageId: Int? = null,
        val start: String? = null,
    ) : TelegramLink()

    data class PrivateChannel(
        val channelId: Long,
        val messageId: Int,
    ) : TelegramLink() {
        val chatId: Long get() = -(CHANNEL_ID_OFFSET + channelId)
    }

    data class Invite(val hash: String) : TelegramLink()

    data class Share(val text: String) : TelegramLink()
}

private val DEFAULT_HOSTS = setOf("t.me", "www.t.me", "telegram.me", "www.telegram.me", "telegram.dog")

private val RESERVED = setOf(
    "addemoji", "addlist", "addstickers", "addstyle", "addtheme", "auction", "auth",
    "boost", "bg", "contact", "giftcode", "invoice", "login", "proxy", "setlanguage",
    "share", "socks", "iv", "s", "nft", "slug", "addlist",
)

fun parseTelegramLink(raw: String, extraPrefix: String? = null): TelegramLink? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("@") && !trimmed.contains('/')) {
        val user = trimmed.removePrefix("@").trim()
        return user.takeIf { isUsername(it) }?.let { TelegramLink.Username(it) }
    }
    val url = normalize(trimmed, extraPrefix) ?: return null
    val host = url.host.lowercase()
    val hosts = hostsFrom(extraPrefix)
    return if (url.scheme == "tg") parseTg(url) else if (host in hosts) parseHttps(url) else null
}

private fun hostsFrom(extraPrefix: String?): Set<String> {
    val extra = extraPrefix
        ?.trim()
        ?.removePrefix("https://")
        ?.removePrefix("http://")
        ?.substringBefore('/')
        ?.lowercase()
        ?.removePrefix("www.")
        .orEmpty()
    return if (extra.isBlank()) DEFAULT_HOSTS else DEFAULT_HOSTS + extra + "www.$extra"
}

private data class ParsedUrl(
    val scheme: String,
    val host: String,
    val path: List<String>,
    val query: Map<String, String>,
)

private fun normalize(raw: String, extraPrefix: String?): ParsedUrl? {
    var value = raw.trim()
    if (value.startsWith("tg:")) {
        val rest = value.removePrefix("tg:").removePrefix("//")
        val schemeSplit = rest.split("?", limit = 2)
        val hostAndPath = schemeSplit[0]
        val host = hostAndPath.substringBefore('/')
        val path = hostAndPath.substringAfter('/', missingDelimiterValue = "")
            .split('/')
            .filter { it.isNotBlank() }
        return ParsedUrl("tg", host.lowercase(), path, queryMap(schemeSplit.getOrNull(1)))
    }
    if (!value.contains("://")) {
        value = if (value.startsWith("t.me/") || value.startsWith("telegram.me/") ||
            value.startsWith("telegram.dog/")
        ) {
            "https://$value"
        } else {
            return null
        }
    }
    val withoutScheme = value.substringAfter("://")
    val scheme = value.substringBefore("://").lowercase()
    val hostPart = withoutScheme.substringBefore('/').substringBefore('?').lowercase()
    val afterHost = withoutScheme.substringAfter('/', missingDelimiterValue = "")
    val pathAndQuery = afterHost.split("?", limit = 2)
    val path = pathAndQuery[0].split('/').filter { it.isNotBlank() }
    return ParsedUrl(scheme, hostPart, path, queryMap(pathAndQuery.getOrNull(1)))
}

private fun queryMap(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split('&').mapNotNull { part ->
        val key = part.substringBefore('=')
        if (key.isBlank()) null
        else key to decode(part.substringAfter('=', ""))
    }.toMap()
}

private fun decode(value: String): String = java.net.URLDecoder.decode(value, "UTF-8")

private fun parseHttps(url: ParsedUrl): TelegramLink? {
    if (url.path.isEmpty()) return null
    val first = url.path[0]
    when {
        first == "c" && url.path.size >= 3 -> {
            val channelId = url.path[1].toLongOrNull() ?: return null
            val messageId = url.path[2].toIntOrNull() ?: return null
            if (channelId <= 0) return null
            return TelegramLink.PrivateChannel(channelId, messageId)
        }
        first == "joinchat" && url.path.size >= 2 ->
            return TelegramLink.Invite(url.path[1])
        first.startsWith("+") ->
            return TelegramLink.Invite(first.removePrefix("+"))
        first == "share" -> {
            val text = url.query["url"] ?: url.query["text"] ?: return null
            return TelegramLink.Share(text)
        }
        first.lowercase() in RESERVED -> return null
        isUsername(first) -> {
            val messageId = url.path.getOrNull(1)?.toIntOrNull()
            val start = url.query["start"]
            return TelegramLink.Username(first, messageId, start)
        }
    }
    return null
}

private fun parseTg(url: ParsedUrl): TelegramLink? {
    val host = url.host
    val query = url.query
    return when (host) {
        "resolve" -> {
            val domain = query["domain"] ?: return null
            if (!isUsername(domain)) return null
            val post = query["post"]?.toIntOrNull()
            TelegramLink.Username(domain, post, query["start"])
        }
        "join" -> query["invite"]?.let { TelegramLink.Invite(it) }
        "msg", "share" -> (query["url"] ?: query["text"])?.let { TelegramLink.Share(it) }
        else -> {
            if (isUsername(host)) TelegramLink.Username(host) else null
        }
    }
}

private fun isUsername(value: String): Boolean {
    if (value.length !in 4..32) return false
    if (value.first().isDigit()) return false
    return value.all { it.isLetterOrDigit() || it == '_' }
}
