package org.monogram.data.repository

import org.monogram.domain.models.FixedLinkPreviewRules
import org.monogram.domain.models.LinkPreviewTarget
import java.net.URI

class DraftLinkPreviewResolver {
    fun parseTargets(text: String): List<LinkPreviewTarget> {
        return URL_REGEX.findAll(text)
            .map { it.value }
            .mapNotNull { toTarget(it) }
            .distinctBy { it.normalizedUrl }
            .toList()
    }

    fun toTarget(rawUrl: String): LinkPreviewTarget? {
        val normalized = normalizeUrl(rawUrl) ?: return null
        val uri = normalized.toParsedUri() ?: return null
        val host = uri.host?.removePrefix("www.")?.lowercase().orEmpty()
        if (host.isBlank()) return null

        val label = buildString {
            append(host)
            val path = uri.path.orEmpty().trimEnd('/')
            if (path.isNotBlank() && path != "/") {
                append(path.take(MAX_LABEL_PATH_LENGTH))
                if (path.length > MAX_LABEL_PATH_LENGTH) append(ELLIPSIS)
            }
        }

        return LinkPreviewTarget(
            sourceUrl = rawUrl,
            normalizedUrl = normalized,
            displayLabel = label,
            host = host
        )
    }

    fun normalizeUrl(rawUrl: String): String? {
        val trimmed = rawUrl
            .trim()
            .removeSurrounding("<", ">")
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trimEnd('.', ',', ';', '!', '?', ')', ']', '}')

        if (trimmed.isBlank()) return null
        val withScheme = if (SCHEME_REGEX.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        return runCatching {
            val uri = URI(withScheme)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host ?: return null
            if (scheme != "http" && scheme != "https") return null

            URI(
                scheme,
                uri.userInfo,
                host.lowercase(),
                uri.port,
                uri.path,
                uri.query,
                uri.fragment
            ).toString()
        }.getOrNull()
    }

    fun shouldUseFixedPreview(normalizedUrl: String): Boolean {
        return FixedLinkPreviewRules.shouldUseFixedPreview(normalizedUrl)
    }

    fun parseTwitterStatusId(normalizedUrl: String): String? {
        val uri = normalizedUrl.toParsedUri() ?: return null
        val host = uri.host?.removePrefix("www.")?.lowercase() ?: return null
        if (!isTwitterHost(host)) return null
        val segments = uri.pathSegments()
        val statusIndex = segments.indexOf("status")
        if (statusIndex == -1) return null
        return segments.getOrNull(statusIndex + 1)?.takeIf { it.all(Char::isDigit) }
    }

    fun parseBlueskyStatus(normalizedUrl: String): Pair<String, String>? {
        val uri = normalizedUrl.toParsedUri() ?: return null
        val host = uri.host?.removePrefix("www.")?.lowercase() ?: return null
        if (!isBlueskyHost(host)) return null
        val segments = uri.pathSegments()
        if (segments.size < 4) return null
        if (segments[0] != "profile" || segments[2] != "post") return null
        val handle = segments[1]
        val rkey = segments[3]
        if (handle.isBlank() || rkey.isBlank()) return null
        return handle to rkey
    }

    fun toFixedPreviewUrl(normalizedUrl: String): String? {
        return FixedLinkPreviewRules.optimisticFixedUrl(normalizedUrl)
    }

    fun toFixedPreviewUrls(normalizedUrl: String): List<String> {
        return FixedLinkPreviewRules.candidateFixedUrls(normalizedUrl).map { it.url }
    }

    private fun rebuildUrlWithHost(uri: URI, host: String): String? {
        return runCatching {
            URI(
                uri.scheme ?: "https",
                uri.userInfo,
                host,
                uri.port,
                uri.path,
                uri.query,
                uri.fragment
            ).toString()
        }.getOrNull()
    }

    private fun isTwitterHost(host: String): Boolean {
        return host == "twitter.com" || host == "x.com" || host == "mobile.twitter.com" || host == "mobile.x.com"
    }

    private fun isBlueskyHost(host: String): Boolean = host == "bsky.app"

    private fun String.toParsedUri(): URI? = runCatching { URI(this) }.getOrNull()

    private fun URI.pathSegments(): List<String> {
        return path.orEmpty()
            .split('/')
            .filter { it.isNotBlank() }
    }

    private companion object {
        private const val MAX_LABEL_PATH_LENGTH = 24
        private const val ELLIPSIS = "..."
        private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
        private val URL_REGEX =
            Regex("""(?i)\b((?:https?://|www\.)[^\s<>()]+|(?:[a-z0-9-]+\.)+[a-z]{2,}(?:/[^\s<>()]*)?)""")
    }
}
