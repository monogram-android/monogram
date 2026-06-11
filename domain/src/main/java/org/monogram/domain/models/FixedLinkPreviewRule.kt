package org.monogram.domain.models

import java.net.URI

data class FixedLinkPreviewCandidate(
    val url: String,
    val host: String
)

sealed interface FixedLinkPreviewRule {
    val sourceHosts: Set<String>

    data class ApiBacked(
        override val sourceHosts: Set<String>,
        val fixedHost: String
    ) : FixedLinkPreviewRule

    data class HostRewrite(
        override val sourceHosts: Set<String>,
        val candidateHosts: List<String>
    ) : FixedLinkPreviewRule
}

object FixedLinkPreviewRules {
    private val rules = listOf(
        FixedLinkPreviewRule.ApiBacked(
            sourceHosts = setOf("twitter.com", "x.com", "mobile.twitter.com", "mobile.x.com"),
            fixedHost = "fxtwitter.com"
        ),
        FixedLinkPreviewRule.ApiBacked(
            sourceHosts = setOf("bsky.app"),
            fixedHost = "fxbsky.app"
        ),
        FixedLinkPreviewRule.HostRewrite(
            sourceHosts = setOf("tiktok.com", "www.tiktok.com", "m.tiktok.com"),
            candidateHosts = listOf("tnktok.com", "tfxktok.com", "tiktxk.com", "tiktokez.com")
        ),
        FixedLinkPreviewRule.HostRewrite(
            sourceHosts = setOf("reddit.com", "www.reddit.com", "old.reddit.com", "m.reddit.com"),
            candidateHosts = listOf("rxddit.com", "redditez.com")
        ),
        FixedLinkPreviewRule.HostRewrite(
            sourceHosts = setOf("pixiv.net", "www.pixiv.net"),
            candidateHosts = listOf("phixiv.net")
        )
    )

    fun findRule(normalizedUrl: String): FixedLinkPreviewRule? {
        val host = normalizedUrl.toParsedUri()?.host?.lowercase() ?: return null
        return rules.firstOrNull { host in it.sourceHosts }
    }

    fun shouldUseFixedPreview(normalizedUrl: String): Boolean = findRule(normalizedUrl) != null

    fun optimisticFixedUrl(normalizedUrl: String): String? {
        return when (val rule = findRule(normalizedUrl)) {
            is FixedLinkPreviewRule.ApiBacked -> rewriteHost(normalizedUrl, rule.fixedHost)
            is FixedLinkPreviewRule.HostRewrite -> rule.candidateHosts.firstOrNull()?.let {
                rewriteHost(normalizedUrl, it)
            }

            null -> null
        }
    }

    fun candidateFixedUrls(normalizedUrl: String): List<FixedLinkPreviewCandidate> {
        return when (val rule = findRule(normalizedUrl)) {
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
                uri.fragment
            ).toString()
        }.getOrNull()
    }

    private fun String.toParsedUri(): URI? = runCatching { URI(this) }.getOrNull()
}
