package org.monogram.core.telegram

import java.net.URI

object TelegramLinkDomains {
    const val CANONICAL_HOST = "t.me"
    const val DEFAULT_BASE_URL = "https://t.me"

    val supportedHosts = setOf(
        "t.me",
        "www.t.me",
        "telegram.me",
        "www.telegram.me",
        "telegram.dog",
        "www.telegram.dog",
        "t.you",
        "www.t.you"
    )

    val webPrefixes = supportedHosts
        .flatMap { host -> listOf("https://$host", "http://$host") }

    val supportedHostsRegexFragment = supportedHosts.joinToString("|") { Regex.escape(it) }

    fun isSupportedHost(host: String?): Boolean {
        return host != null && supportedHosts.contains(host.lowercase())
    }

    fun startsWithSupportedHost(value: String): Boolean {
        return supportedHosts.any { host -> value.startsWith(host, ignoreCase = true) }
    }

    fun extractPathAndQuery(url: String): String? {
        val value = url.trim()
            .removeSurrounding("<", ">")
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .takeIf { it.isNotBlank() }
            ?: return null

        supportedHosts.firstOrNull { host ->
            value.equals(host, ignoreCase = true) ||
                    value.startsWith("$host/", ignoreCase = true) ||
                    value.startsWith("$host?", ignoreCase = true) ||
                    value.startsWith("$host#", ignoreCase = true)
        }?.let { host ->
            return value.substring(host.length).removePrefix("/")
        }

        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if ((scheme != "http" && scheme != "https") || !isSupportedHost(host)) {
            return null
        }

        val path = uri.rawPath.orEmpty().trimStart('/')
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "$path$query$fragment"
    }
}
