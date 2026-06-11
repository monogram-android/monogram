package org.monogram.presentation.features.chats.conversation.logic

import androidx.core.net.toUri
import org.monogram.domain.models.LinkPreviewTarget

internal object DraftLinkPreviewTextParser {
    fun parseTargets(text: String): List<LinkPreviewTarget> {
        return urlRegex.findAll(text)
            .map { it.value }
            .mapNotNull(::toTarget)
            .distinctBy { it.normalizedUrl }
            .toList()
    }

    fun normalizeUrl(rawUrl: String): String? {
        val trimmed = rawUrl
            .trim()
            .removeSurrounding("<", ">")
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trimEnd('.', ',', ';', '!', '?', ')', ']', '}')

        if (trimmed.isBlank()) return null
        val withScheme = if (schemeRegex.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        return runCatching {
            val uri = withScheme.toUri()
            val scheme = uri.scheme?.lowercase()
            val host = uri.host ?: return null
            if (scheme != "http" && scheme != "https") return null

            uri.buildUpon()
                .scheme(scheme)
                .authority(host.lowercase())
                .build()
                .toString()
        }.getOrNull()
    }

    private fun toTarget(rawUrl: String): LinkPreviewTarget? {
        val normalized = normalizeUrl(rawUrl) ?: return null
        val uri = normalized.toUri()
        val host = uri.host?.removePrefix("www.")?.lowercase().orEmpty()
        if (host.isBlank()) return null

        val label = buildString {
            append(host)
            val path = uri.path.orEmpty().trimEnd('/')
            if (path.isNotBlank() && path != "/") {
                append(path.take(maxLabelPathLength))
                if (path.length > maxLabelPathLength) append(ellipsis)
            }
        }

        return LinkPreviewTarget(
            sourceUrl = rawUrl,
            normalizedUrl = normalized,
            displayLabel = label,
            host = host
        )
    }

    private const val maxLabelPathLength = 24
    private const val ellipsis = "..."
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val urlRegex =
        Regex("""(?i)\b((?:https?://|www\.)[^\s<>()]+|(?:[a-z0-9-]+\.)+[a-z]{2,}(?:/[^\s<>()]*)?)""")
}
