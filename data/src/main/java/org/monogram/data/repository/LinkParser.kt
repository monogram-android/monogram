package org.monogram.data.repository

import androidx.core.net.toUri
import org.monogram.data.core.coRunCatching
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.domain.proxy.MtprotoSecretNormalizer

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
        val uri = coRunCatching { link.toUri() }.getOrNull() ?: return null

        val isProxy = link.contains("/proxy?") || link.startsWith("tg://proxy")
        val isSocks = link.contains("/socks?") || link.startsWith("tg://socks")
        val isHttp = link.contains("/http?") || link.startsWith("tg://http")
        if (!isProxy && !isSocks && !isHttp) return null

        val server = uri.getQueryParameter("server") ?: return null
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null
        val secret = uri.getQueryParameter("secret")
        val user = uri.getQueryParameter("user")
        val pass = uri.getQueryParameter("pass")

        val type = when {
            secret != null -> {
                val normalized = MtprotoSecretNormalizer.normalize(secret) ?: return null
                ProxyTypeModel.Mtproto(normalized)
            }
            isHttp -> ProxyTypeModel.Http(user ?: "", pass ?: "", false)
            else -> ProxyTypeModel.Socks5(user ?: "", pass ?: "")
        }
        return ParsedLink.AddProxy(server, port, type)
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