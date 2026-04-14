package org.monogram.data.repository

import androidx.core.net.toUri
import org.monogram.data.core.coRunCatching
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.domain.proxy.MtprotoSecretNormalizer
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class LinkParser {

    fun normalize(link: String): String = when {
        link.startsWith("tg://") -> link
        link.startsWith("https://t.me/") -> link
        link.startsWith("http://t.me/") -> link.replace("http://", "https://")
        link.startsWith("t.me/") -> "https://$link"
        else -> link
    }

    fun parsePrimary(link: String): ParsedLink? {
        parseProxyLink(link)?.let { return it }
        parseUserLink(link)?.let { return it }
        return null
    }

    fun parseFallback(link: String): ParsedLink {
        val uri = coRunCatching { link.toUri() }.getOrNull() ?: return parseExternalOrNone(link)

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

            if (host == "t.me" || host == "www.t.me" || host == "telegram.me" || host == "www.telegram.me") {
                val first = pathSegments.firstOrNull()
                val second = pathSegments.getOrNull(1)

                if (!first.isNullOrBlank()) {
                    if (first == "joinchat" && !second.isNullOrBlank()) {
                        return ParsedLink.JoinChat("https://t.me/joinchat/$second")
                    }

                    if (first.startsWith("+")) {
                        return ParsedLink.JoinChat("https://t.me/$first")
                    }

                    if (pathSegments.size == 1) {
                        return ParsedLink.OpenPublicChat(first)
                    }
                }
            }
        }

        return parseExternalOrNone(link)
    }

    private fun parseProxyLink(link: String): ParsedLink.AddProxy? {
        val normalizedLink = normalizeTelegramScheme(link.trim())
        val uri = coRunCatching { normalizedLink.toUri() }.getOrNull() ?: return null

        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        val pathType = uri.pathSegments.firstOrNull()?.lowercase()
        val schemeSpecificType = uri.schemeSpecificPart
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
            (host == "t.me" || host == "www.t.me" || host == "telegram.me" || host == "www.telegram.me")
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

        val proxyType = tgType ?: httpsType ?: return null
        val queryMap = parseQueryMap(uri, normalizedLink)

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

    private fun parseQueryMap(uri: android.net.Uri, originalLink: String): Map<String, String> {
        val rawQuery = uri.encodedQuery
            ?: originalLink.substringAfter('?', missingDelimiterValue = "")
                .takeIf { it.isNotBlank() }
            ?: return emptyMap()
        return rawQuery.split('&')
            .mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val key = pair.substringBefore('=')
                if (key.isBlank()) return@mapNotNull null
                val value = pair.substringAfter('=', missingDelimiterValue = "")
                decode(key).lowercase() to decode(value)
            }.toMap()
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
}