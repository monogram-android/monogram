package org.monogram.data.repository

import android.util.Log
import org.monogram.data.datasource.remote.DraftLinkPreviewRemoteDataSource
import org.monogram.data.datasource.remote.FixedPreviewRemoteDataSource
import org.monogram.data.datasource.remote.FxEmbedRemoteDataSource
import org.monogram.domain.models.DraftLinkPreview
import org.monogram.domain.models.DraftLinkPreviewRequest
import org.monogram.domain.models.FixedLinkPreviewRule
import org.monogram.domain.models.FixedLinkPreviewRules
import org.monogram.domain.models.WebPage

class FixedDraftLinkPreviewFetcher(
    private val draftLinkPreviewResolver: DraftLinkPreviewResolver,
    private val draftLinkPreviewRemoteDataSource: DraftLinkPreviewRemoteDataSource,
    private val fixedPreviewRemoteDataSource: FixedPreviewRemoteDataSource
) {
    suspend fun getFixedDraftLinkPreview(
        sourceUrl: String,
        normalizedUrl: String
    ): DraftLinkPreview? {
        return when (val rule = FixedLinkPreviewRules.findRule(normalizedUrl)) {
            is FixedLinkPreviewRule.ApiBacked -> getApiBackedDraftLinkPreview(
                sourceUrl,
                normalizedUrl
            )

            is FixedLinkPreviewRule.HostRewrite -> getHostRewriteDraftLinkPreview(
                sourceUrl,
                normalizedUrl,
                rule
            )

            null -> {
                Log.d(DRAFT_LINK_PREVIEW_TAG, "repo fixed preview unsupported for $normalizedUrl")
                null
            }
        }
    }

    private suspend fun getApiBackedDraftLinkPreview(
        sourceUrl: String,
        normalizedUrl: String
    ): DraftLinkPreview? {
        draftLinkPreviewResolver.parseTwitterStatusId(normalizedUrl)?.let { statusId ->
            Log.d(
                DRAFT_LINK_PREVIEW_TAG,
                "repo fixed preview twitter normalized=$normalizedUrl statusId=$statusId"
            )
            val response =
                fixedPreviewRemoteDataSource.getTwitterStatus(statusId) ?: return@let null
            return response.toDraftLinkPreview(
                sourceUrl = sourceUrl,
                resolvedUrl = normalizedUrl,
                fallbackSiteName = "X"
            )
        }

        draftLinkPreviewResolver.parseBlueskyStatus(normalizedUrl)?.let { (handle, rkey) ->
            Log.d(
                DRAFT_LINK_PREVIEW_TAG,
                "repo fixed preview bluesky normalized=$normalizedUrl handle=$handle rkey=$rkey"
            )
            val response =
                fixedPreviewRemoteDataSource.getBlueskyStatus(handle, rkey) ?: return@let null
            return response.toDraftLinkPreview(
                sourceUrl = sourceUrl,
                resolvedUrl = normalizedUrl,
                fallbackSiteName = "Bluesky"
            )
        }

        Log.d(
            DRAFT_LINK_PREVIEW_TAG,
            "repo fixed preview api-backed unsupported for $normalizedUrl"
        )
        return null
    }

    private suspend fun getHostRewriteDraftLinkPreview(
        sourceUrl: String,
        normalizedUrl: String,
        rule: FixedLinkPreviewRule.HostRewrite
    ): DraftLinkPreview? {
        for (candidate in FixedLinkPreviewRules.candidateFixedUrls(normalizedUrl)) {
            Log.d(
                DRAFT_LINK_PREVIEW_TAG,
                "repo fixed preview host-rewrite normalized=$normalizedUrl candidate=${candidate.url}"
            )
            val preview = draftLinkPreviewRemoteDataSource.getDraftLinkPreview(
                DraftLinkPreviewRequest(
                    sourceUrl = candidate.url,
                    useFixedPreview = false
                )
            )
            if (preview?.webPage != null) {
                return preview.copy(
                    sourceUrl = sourceUrl,
                    resolvedUrl = candidate.url
                )
            }
        }

        Log.d(
            DRAFT_LINK_PREVIEW_TAG,
            "repo fixed preview host-rewrite miss normalized=$normalizedUrl candidates=${rule.candidateHosts}"
        )
        return null
    }

    private fun FxEmbedRemoteDataSource.FxEmbedStatusResponse.toDraftLinkPreview(
        sourceUrl: String,
        resolvedUrl: String,
        fallbackSiteName: String
    ): DraftLinkPreview? {
        val bestMedia =
            media.orEmpty().firstOrNull { !it.mediaUrl.isNullOrBlank() || !it.url.isNullOrBlank() }
        val mediaUrl = bestMedia?.mediaUrl ?: bestMedia?.url
        val isVideo = bestMedia?.type?.contains("video", ignoreCase = true) == true
        val authorHandle = author?.screenName ?: author?.handle
        val title = authorHandle?.let { "@$it" } ?: author?.name
        val description = text?.takeIf { it.isNotBlank() }

        if (title.isNullOrBlank() && description.isNullOrBlank() && mediaUrl.isNullOrBlank()) {
            Log.d(
                DRAFT_LINK_PREVIEW_TAG,
                "repo fxembed mapped empty preview normalized=$resolvedUrl fallbackSiteName=$fallbackSiteName"
            )
            return null
        }

        val photo = mediaUrl?.let {
            WebPage.Photo(
                path = it,
                thumbnailPath = null,
                width = bestMedia?.width ?: 0,
                height = bestMedia?.height ?: 0,
                fileId = 0,
                thumbnailFileId = 0,
                originalFileId = 0,
                minithumbnail = null
            )
        }

        return DraftLinkPreview(
            sourceUrl = sourceUrl,
            resolvedUrl = resolvedUrl,
            webPage = WebPage(
                url = resolvedUrl,
                displayUrl = resolvedUrl,
                type = if (isVideo) {
                    WebPage.LinkPreviewType.ExternalVideo(resolvedUrl)
                } else {
                    WebPage.LinkPreviewType.Article
                },
                siteName = fallbackSiteName,
                title = title ?: fallbackSiteName,
                description = description,
                photo = photo,
                embedUrl = null,
                embedType = null,
                embedWidth = bestMedia?.width ?: 0,
                embedHeight = bestMedia?.height ?: 0,
                duration = 0,
                author = author?.name,
                video = null,
                audio = null,
                document = null,
                sticker = null,
                animation = null,
                instantViewVersion = 0
            )
        )
    }

    private companion object {
        private const val DRAFT_LINK_PREVIEW_TAG = "DraftLinkPreview"
    }
}
