package org.monogram.core.models

import java.net.URI

data class FixedLinkPreviewCandidate(
    val url: String,
    val host: String,
)

sealed interface FixedLinkPreviewRule {
    val sourceHosts: Set<String>

    data class ApiBacked(
        override val sourceHosts: Set<String>,
        val fixedHost: String,
    ) : FixedLinkPreviewRule

    data class HostRewrite(
        override val sourceHosts: Set<String>,
        val candidateHosts: List<String>,
    ) : FixedLinkPreviewRule
}

object FixedLinkPreviewRules {
    private val rules = listOf(
        FixedLinkPreviewRule.ApiBacked(
            sourceHosts = setOf("twitter.com", "x.com", "mobile.twitter.com", "mobile.x.com"),
            fixedHost = "fxtwitter.com",
        ),
        FixedLinkPreviewRule.ApiBacked(
            sourceHosts = setOf("bsky.app"),
            fixedHost = "fxbsky.app",
        ),
        FixedLinkPreviewRule.HostRewrite(
            sourceHosts = setOf("tiktok.com", "www.tiktok.com", "m.tiktok.com"),
            candidateHosts = listOf("tnktok.com", "tfxktok.com", "tiktxk.com", "tiktokez.com"),
        ),
        FixedLinkPreviewRule.HostRewrite(
            sourceHosts = setOf("reddit.com", "www.reddit.com", "old.reddit.com", "m.reddit.com"),
            candidateHosts = listOf("rxddit.com", "redditez.com"),
        ),
        FixedLinkPreviewRule.HostRewrite(
            sourceHosts = setOf("pixiv.net", "www.pixiv.net"),
            candidateHosts = listOf("phixiv.net"),
        ),
    )

    private val urlRegex = Regex("""https?://[^\s<>()]+""", RegexOption.IGNORE_CASE)

    fun firstUrl(text: String): String? = urls(text).firstOrNull()

    fun urls(text: String): List<String> =
        urlRegex.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ')', ']') }
            .filter { it.length >= 8 }
            .distinct()
            .toList()

    fun findRule(normalizedUrl: String): FixedLinkPreviewRule? {
        val host = normalizedUrl.toParsedUri()?.host?.lowercase() ?: return null
        return rules.firstOrNull { host in it.sourceHosts }
    }

    fun optimisticFixedUrl(normalizedUrl: String): String? = when (val rule = findRule(normalizedUrl)) {
        is FixedLinkPreviewRule.ApiBacked -> rewriteHost(normalizedUrl, rule.fixedHost)
        is FixedLinkPreviewRule.HostRewrite ->
            rule.candidateHosts.firstOrNull()?.let { rewriteHost(normalizedUrl, it) }
        null -> null
    }

    fun candidateFixedUrls(normalizedUrl: String): List<FixedLinkPreviewCandidate> = when (val rule = findRule(normalizedUrl)) {
        is FixedLinkPreviewRule.ApiBacked -> {
            rewriteHost(normalizedUrl, rule.fixedHost)?.let {
                listOf(FixedLinkPreviewCandidate(url = it, host = rule.fixedHost))
            }.orEmpty()
        }
        is FixedLinkPreviewRule.HostRewrite -> {
            rule.candidateHosts.mapNotNull { host ->
                rewriteHost(normalizedUrl, host)?.let { url ->
                    FixedLinkPreviewCandidate(url = url, host = host)
                }
            }
        }
        null -> emptyList()
    }

    fun replaceFirstUrl(text: String, nextUrl: String): String {
        val current = firstUrl(text) ?: return text
        return text.replaceFirst(current, nextUrl)
    }

    fun previewUrlFor(text: String, preferredFixedUrl: String? = null): String? {
        val url = firstUrl(text) ?: return null
        val preferred = preferredFixedUrl?.takeIf { candidate ->
            candidate != url && candidateFixedUrls(url).any { it.url == candidate }
        }
        return preferred ?: optimisticFixedUrl(url)
    }

    fun matchesPreviewSource(draftUrl: String, storedUrl: String?): Boolean {
        if (storedUrl.isNullOrBlank()) return false
        if (draftUrl == storedUrl) return true
        return candidateFixedUrls(draftUrl).any { it.url == storedUrl }
    }

    fun rewriteForSend(text: String, preferredFixedUrl: String? = null): String {
        val next = previewUrlFor(text, preferredFixedUrl) ?: return text
        return replaceFirstUrl(text, next)
    }

    private fun rewriteHost(normalizedUrl: String, host: String): String? {
        val uri = normalizedUrl.toParsedUri() ?: return null
        return runCatching {
            URI(
                uri.scheme ?: "https",
                uri.userInfo,
                host,
                uri.port,
                uri.path,
                uri.query,
                uri.fragment,
            ).toString()
        }.getOrNull()
    }

    private fun String.toParsedUri(): URI? = runCatching { URI(this) }.getOrNull()
}
