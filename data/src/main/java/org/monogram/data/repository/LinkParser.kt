package org.monogram.data.repository

import androidx.core.net.toUri
import org.monogram.core.telegram.TelegramLinkDomains
import org.monogram.data.core.coRunCatching
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.domain.proxy.MtprotoSecretNormalizer
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class LinkParser {

    fun normalize(link: String): String = when {
        normalizeForParsing(link).startsWith(
            "tg://",
            ignoreCase = true
        ) -> normalizeForParsing(link)

        else -> canonicalizeTelegramHttpLink(normalizeForParsing(link)) ?: normalizeForParsing(link)
    }

    fun parsePrimary(link: String): ParsedLink? {
        parseProxyLink(link)?.let { return it }
        parseUserLink(link)?.let { return it }
        return null
    }

    fun parseFallback(link: String): ParsedLink {
        val normalizedLink = normalize(link)
        parseTelegramHttpFallback(normalizedLink)?.let { return it }
        val uri = coRunCatching { normalizedLink.toUri() }.getOrNull()
            ?: return parseExternalOrNone(normalizedLink)

        if (uri.scheme.equals("tg", ignoreCase = true)) {
            if (uri.host.equals("resolve", ignoreCase = true)) {
                uri.getQueryParameter("user_id")?.toLongOrNull()?.let {
                    return ParsedLink.OpenUser(it)
                }

                uri.getQueryParameter("phone")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return ParsedLink.ResolveByPhone(phoneNumber = it, openProfile = false) }

                val username = uri.getQueryParameter("domain")?.takeIf { it.isNotBlank() }
                if (username != null) {
                    val hasMessageTarget = uri.getQueryParameter("post") != null ||
                            uri.getQueryParameter("thread") != null ||
                            uri.getQueryParameter("comment") != null
                    if (!hasMessageTarget) {
                        return ParsedLink.OpenPublicChat(username)
                    }
                }
            }

            return ParsedLink.None
        }

        if (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) {
            val host = uri.host?.lowercase()
            val pathSegments = uri.pathSegments.orEmpty()

            if (isTelegramHost(host)) {
                val first = pathSegments.firstOrNull()
                val second = pathSegments.getOrNull(1)

                if (!first.isNullOrBlank()) {
                    if (first == "joinchat" && !second.isNullOrBlank()) {
                        return ParsedLink.JoinChat("${TelegramLinkDomains.DEFAULT_BASE_URL}/joinchat/$second")
                    }

                    if (first.startsWith("+")) {
                        return ParsedLink.JoinChat("${TelegramLinkDomains.DEFAULT_BASE_URL}/$first")
                    }

                    if (pathSegments.size == 1) {
                        return ParsedLink.OpenPublicChat(first)
                    }
                }
            }
        }

        return parseExternalOrNone(normalizedLink)
    }

    private fun parseTelegramHttpFallback(link: String): ParsedLink? {
        if (!link.startsWith(TelegramLinkDomains.DEFAULT_BASE_URL, ignoreCase = true)) {
            return null
        }

        val path = link
            .removePrefix(TelegramLinkDomains.DEFAULT_BASE_URL)
            .trimStart('/')
            .substringBefore('?')
            .substringBefore('#')

        if (path.isBlank()) return null

        val pathSegments = path.split('/').filter { it.isNotBlank() }
        val first = pathSegments.firstOrNull()
        val second = pathSegments.getOrNull(1)

        if (first == "joinchat" && !second.isNullOrBlank()) {
            return ParsedLink.JoinChat("${TelegramLinkDomains.DEFAULT_BASE_URL}/joinchat/$second")
        }

        if (!first.isNullOrBlank() && first.startsWith("+")) {
            return ParsedLink.JoinChat("${TelegramLinkDomains.DEFAULT_BASE_URL}/$first")
        }

        if (!first.isNullOrBlank() && pathSegments.size == 1) {
            return ParsedLink.OpenPublicChat(first)
        }

        return null
    }

    private fun parseProxyLink(link: String): ParsedLink.AddProxy? {
        val normalizedLink = normalizeTelegramScheme(link.trim())
        val uri = coRunCatching { normalizedLink.toUri() }.getOrNull()

        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host?.lowercase()
        val pathType = uri?.pathSegments?.firstOrNull()?.lowercase()
        val schemeSpecificType = uri?.schemeSpecificPart
            ?.substringBefore('?')
            ?.removePrefix("//")
            ?.substringBefore('/')
            ?.lowercase()

        val tgType = if (scheme == "tg") {
            when (host ?: pathType ?: schemeSpecificType) {
                "proxy" -> "proxy"
                "socks" -> "socks"
                "http" -> "http"
                else -> null
            }
        } else {
            null
        }

        val httpsType = if (
            (scheme == "https" || scheme == "http") &&
            isTelegramHost(host)
        ) {
            when (pathType) {
                "proxy" -> "proxy"
                "socks" -> "socks"
                "http" -> "http"
                else -> null
            }
        } else {
            null
        }

        val manualType = detectProxyTypeFromString(normalizedLink.lowercase())
        val proxyType = tgType ?: httpsType ?: manualType ?: return null
        val queryMap = if (uri != null) {
            parseQueryMap(uri, normalizedLink)
        } else {
            parseQueryMapFromLink(normalizedLink)
        }

        val server = queryMap["server"] ?: return null
        val port = queryMap["port"]?.toIntOrNull() ?: return null
        if (server.isBlank() || port !in 1..65535) return null
        val secret = queryMap["secret"]
        val user = queryMap["user"] ?: queryMap["username"]
        val pass = queryMap["pass"] ?: queryMap["password"]

        val type = when {
            secret != null -> {
                val normalized = MtprotoSecretNormalizer.normalize(secret) ?: return null
                ProxyTypeModel.Mtproto(normalized)
            }
            proxyType == "http" -> ProxyTypeModel.Http(user ?: "", pass ?: "", false)
            else -> ProxyTypeModel.Socks5(user ?: "", pass ?: "")
        }
        return ParsedLink.AddProxy(server, port, type)
    }

    private fun normalizeTelegramScheme(link: String): String {
        if (link.startsWith("tg://", ignoreCase = true)) return link
        if (link.startsWith("tg:", ignoreCase = true)) {
            return "tg://${link.substringAfter(':')}"
        }
        return link
    }

    private fun normalizeForParsing(link: String): String {
        var sanitized = link.trim()
            .removeSurrounding("<", ">")
            .removeSurrounding("\"")
            .removeSurrounding("'")

        while (sanitized.isNotEmpty() && sanitized.last() in setOf(
                ')',
                ']',
                '}',
                '.',
                ',',
                ';',
                '!',
                '?'
            )
        ) {
            sanitized = sanitized.dropLast(1)
        }
        return sanitized
    }

    private fun parseQueryMap(uri: android.net.Uri, originalLink: String): Map<String, String> {
        val rawQuery = uri.encodedQuery
            ?: originalLink.substringAfter('?', missingDelimiterValue = "")
                .takeIf { it.isNotBlank() }
            ?: return emptyMap()
        return parseQueryMapFromRawQuery(rawQuery)
    }

    private fun parseQueryMapFromLink(originalLink: String): Map<String, String> {
        val rawQuery = originalLink.substringAfter('?', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?: return emptyMap()
        return parseQueryMapFromRawQuery(rawQuery)
    }

    private fun parseQueryMapFromRawQuery(rawQuery: String): Map<String, String> {
        return rawQuery.split('&')
            .mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val key = pair.substringBefore('=')
                if (key.isBlank()) return@mapNotNull null
                val value = pair.substringAfter('=', missingDelimiterValue = "")
                decode(key).lowercase() to decode(value)
            }.toMap()
    }

    private fun detectProxyTypeFromString(linkLower: String): String? = when {
        linkLower.startsWith("tg://proxy?") ||
                linkLower.startsWith("tg:proxy?") ||
                linkLower.startsWith("tg://proxy/") -> "proxy"

        linkLower.startsWith("tg://socks?") ||
                linkLower.startsWith("tg:socks?") ||
                linkLower.startsWith("tg://socks/") -> "socks"

        linkLower.startsWith("tg://http?") ||
                linkLower.startsWith("tg:http?") ||
                linkLower.startsWith("tg://http/") -> "http"

        TELEGRAM_WEB_PREFIXES.any { prefix -> linkLower.startsWith("$prefix/proxy?") } -> "proxy"

        TELEGRAM_WEB_PREFIXES.any { prefix -> linkLower.startsWith("$prefix/socks?") } -> "socks"

        TELEGRAM_WEB_PREFIXES.any { prefix -> linkLower.startsWith("$prefix/http?") } -> "http"

        else -> null
    }

    private fun canonicalizeTelegramHttpLink(link: String): String? {
        if (TelegramLinkDomains.supportedHosts.any { host ->
                link.equals(
                    host,
                    ignoreCase = true
                )
            }) {
            return TelegramLinkDomains.DEFAULT_BASE_URL
        }

        val directWebPrefixMatch = TELEGRAM_WEB_PREFIXES.firstOrNull { prefix ->
            link.equals(prefix, ignoreCase = true) ||
                    link.startsWith("$prefix/", ignoreCase = true) ||
                    link.startsWith("$prefix?", ignoreCase = true) ||
                    link.startsWith("$prefix#", ignoreCase = true)
        }
        if (directWebPrefixMatch != null) {
            val suffix = link.substring(directWebPrefixMatch.length)
            return when {
                suffix.isBlank() -> TelegramLinkDomains.DEFAULT_BASE_URL
                suffix.startsWith("/") -> "${TelegramLinkDomains.DEFAULT_BASE_URL}$suffix"
                else -> "${TelegramLinkDomains.DEFAULT_BASE_URL}/$suffix"
            }.removeSuffix("/")
        }

        val directHostMatch = TelegramLinkDomains.supportedHosts.firstOrNull { host ->
            link.startsWith("$host/", ignoreCase = true)
        }
        if (directHostMatch != null) {
            return "${TelegramLinkDomains.DEFAULT_BASE_URL}/${
                link.substring(directHostMatch.length).trimStart('/')
            }"
        }

        val uri = coRunCatching { link.toUri() }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if ((scheme != "http" && scheme != "https") || !isTelegramHost(host)) {
            return null
        }

        val path = uri.encodedPath.orEmpty().trimStart('/')
        val query = uri.encodedQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.encodedFragment?.let { "#$it" }.orEmpty()
        return "${TelegramLinkDomains.DEFAULT_BASE_URL}/${path}${query}${fragment}".removeSuffix("/")
    }

    private fun isTelegramHost(host: String?): Boolean {
        return TelegramLinkDomains.isSupportedHost(host)
    }

    private fun decode(value: String): String {
        return runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.toString()) }
            .getOrDefault(value)
    }

    private fun parseUserLink(link: String): ParsedLink.OpenUser? {
        val uri = coRunCatching { link.toUri() }.getOrNull() ?: return null
        if (!uri.scheme.equals("tg", ignoreCase = true)) return null

        val userId = when {
            uri.host.equals("user", ignoreCase = true) ->
                uri.getQueryParameter("id")?.toLongOrNull()

            uri.host.equals("openmessage", ignoreCase = true) ->
                uri.getQueryParameter("user_id")?.toLongOrNull()

            else -> null
        } ?: return null

        return ParsedLink.OpenUser(userId)
    }

    private fun parseExternalOrNone(link: String): ParsedLink {
        return if (link.startsWith("http://") || link.startsWith("https://")) {
            ParsedLink.OpenExternal(link)
        } else {
            ParsedLink.None
        }
    }

    companion object {
        private val TELEGRAM_WEB_PREFIXES = TelegramLinkDomains.webPrefixes
    }
}
