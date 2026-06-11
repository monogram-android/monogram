package org.monogram.presentation.features.chats.conversation.ui.message

import org.monogram.domain.models.WebPage
import org.monogram.presentation.core.util.namespacedCacheKey
import org.monogram.presentation.features.viewers.extractYouTubeId
import java.net.URI

internal sealed interface LinkPreviewAction {
    data class OpenInstantView(val url: String) : LinkPreviewAction
    data class OpenYouTube(val url: String) : LinkPreviewAction
    data class OpenMiniApp(val url: String, val name: String) : LinkPreviewAction
    data class OpenWebView(val url: String) : LinkPreviewAction
    data class OpenLink(val url: String) : LinkPreviewAction
    data class OpenImageViewer(val request: PreviewImageViewerRequest) : LinkPreviewAction
    data class OpenVideoViewer(val request: PreviewVideoViewerRequest) : LinkPreviewAction
}

internal data class PreviewImageViewerRequest(
    val images: List<String>,
    val captions: List<String?>,
    val startIndex: Int = 0,
    val sourceUrl: String
)

internal data class PreviewVideoViewerRequest(
    val path: String,
    val fileId: Int,
    val supportsStreaming: Boolean,
    val caption: String?,
    val sourceUrl: String
)

internal data class LinkPreviewMeta(
    val kicker: String,
    val title: String?,
    val description: String?
)

internal data class ResolvedLinkPreview(
    val primaryAction: LinkPreviewAction,
    val mediaAction: LinkPreviewAction,
    val hasMedia: Boolean,
    val isSmallMedia: Boolean,
    val showPlayOverlay: Boolean,
    val showInstantViewButton: Boolean,
    val aspectRatio: Float,
    val thumbnailData: Any?,
    val thumbnailCacheKey: String?,
    val viewerCaption: String?,
    val sourceUrl: String,
    val meta: LinkPreviewMeta
)

internal fun WebPage.resolveLinkPreview(): ResolvedLinkPreview {
    val previewType = type
    val previewVideo = video
    val previewPhoto = photo
    val previewAnimation = animation
    val sourceUrl = resolvePreviewSourceUrl()
    val meta = buildLinkPreviewMeta(sourceUrl = sourceUrl)
    val isInstantView = previewType == WebPage.LinkPreviewType.InstantView || instantViewVersion > 0
    val isYouTube = siteName?.contains("YouTube", ignoreCase = true) == true ||
            url?.let { extractYouTubeId(it) != null } == true
    val hasPlayableVideo = hasPlayableVideo(previewVideo, previewAnimation)
    val isVideoLike = isVideoLike(previewType, previewVideo, previewAnimation)

    val primaryAction = when {
        isInstantView && sourceUrl.isNotBlank() -> LinkPreviewAction.OpenInstantView(sourceUrl)
        isYouTube && sourceUrl.isNotBlank() -> LinkPreviewAction.OpenYouTube(sourceUrl)
        previewType is WebPage.LinkPreviewType.WebApp -> {
            LinkPreviewAction.OpenMiniApp(
                url = previewType.url,
                name = bestMiniAppName(meta = meta, fallbackUrl = sourceUrl)
            )
        }

        shouldOpenInWebView(isYouTube = isYouTube, hasPlayableVideo = hasPlayableVideo) -> {
            LinkPreviewAction.OpenWebView(resolveWebViewUrl(sourceUrl))
        }

        else -> LinkPreviewAction.OpenLink(sourceUrl)
    }

    val viewerCaption = buildViewerCaption(meta = meta)
    val mediaAction = when {
        !previewPhoto?.path.isNullOrBlank() -> {
            LinkPreviewAction.OpenImageViewer(
                PreviewImageViewerRequest(
                    images = listOfNotNull(previewPhoto.path),
                    captions = listOf(viewerCaption),
                    sourceUrl = sourceUrl
                )
            )
        }

        !previewVideo?.path.isNullOrBlank() || (previewVideo?.supportsStreaming == true && (previewVideo.fileId) != 0) -> {
            LinkPreviewAction.OpenVideoViewer(
                PreviewVideoViewerRequest(
                    path = previewVideo.path.orEmpty(),
                    fileId = previewVideo.fileId,
                    supportsStreaming = previewVideo.supportsStreaming,
                    caption = viewerCaption,
                    sourceUrl = sourceUrl
                )
            )
        }

        !previewAnimation?.path.isNullOrBlank() -> {
            LinkPreviewAction.OpenVideoViewer(
                PreviewVideoViewerRequest(
                    path = previewAnimation.path.orEmpty(),
                    fileId = previewAnimation.fileId,
                    supportsStreaming = false,
                    caption = viewerCaption,
                    sourceUrl = sourceUrl
                )
            )
        }

        else -> primaryAction
    }

    val thumbnailData = resolveThumbnailData(isYouTube = isYouTube)
    val showPlayOverlay = isVideoLike
    val hasTextContent =
        !siteName.isNullOrBlank() || !title.isNullOrBlank() || !description.isNullOrBlank()
    val hasMedia = thumbnailData != null
    val isSmallMedia = previewPhoto != null && !showPlayOverlay && hasTextContent
    val aspectRatio = resolveAspectRatio()

    return ResolvedLinkPreview(
        primaryAction = primaryAction,
        mediaAction = mediaAction,
        hasMedia = hasMedia,
        isSmallMedia = isSmallMedia,
        showPlayOverlay = showPlayOverlay,
        showInstantViewButton = isInstantView && sourceUrl.isNotBlank(),
        aspectRatio = aspectRatio,
        thumbnailData = thumbnailData,
        thumbnailCacheKey = namespacedCacheKey("link_preview_media", thumbnailData),
        viewerCaption = viewerCaption,
        sourceUrl = sourceUrl,
        meta = meta
    )
}

internal fun String.hostFromUrl(): String? {
    return runCatching { URI(this).host?.removePrefix("www.") }.getOrNull()
}

private fun WebPage.resolvePreviewSourceUrl(): String {
    val typeUrl = when (val currentType = type) {
        is WebPage.LinkPreviewType.EmbeddedAnimation -> currentType.url
        is WebPage.LinkPreviewType.EmbeddedAudio -> currentType.url
        is WebPage.LinkPreviewType.EmbeddedVideo -> currentType.url
        is WebPage.LinkPreviewType.ExternalAudio -> currentType.url
        is WebPage.LinkPreviewType.ExternalVideo -> currentType.url
        is WebPage.LinkPreviewType.WebApp -> currentType.url
        else -> null
    }

    return listOfNotNull(
        url?.takeIf { it.isNotBlank() },
        embedUrl?.takeIf { it.isNotBlank() },
        typeUrl?.takeIf { it.isNotBlank() },
        displayUrl?.takeIf { it.isNotBlank() }
    ).firstOrNull().orEmpty()
}

private fun WebPage.resolveWebViewUrl(sourceUrl: String): String {
    return listOfNotNull(
        embedUrl?.takeIf { it.isNotBlank() },
        sourceUrl.takeIf { it.isNotBlank() }
    ).firstOrNull().orEmpty()
}

private fun WebPage.bestMiniAppName(meta: LinkPreviewMeta, fallbackUrl: String): String {
    return listOfNotNull(
        meta.title?.takeIf { !it.startsWith("@") && it.isNotBlank() },
        siteName?.takeIf { it.isNotBlank() },
        title?.takeIf { it.isNotBlank() },
        fallbackUrl.hostFromUrl(),
        fallbackUrl.takeIf { it.isNotBlank() }
    ).firstOrNull().orEmpty()
}

private fun WebPage.buildLinkPreviewMeta(sourceUrl: String): LinkPreviewMeta {
    val fallbackHost = sourceUrl.hostFromUrl()
    val source = siteName?.takeIf { it.isNotBlank() }
        ?: fallbackHost
        ?: sourceUrl

    val kicker = listOfNotNull(
        source.takeIf { it.isNotBlank() },
        author?.takeIf { it.isNotBlank() && it != source }
    ).joinToString(" • ").ifBlank { sourceUrl }

    val rawTitle = title?.takeIf { it.isNotBlank() }
        ?: siteName?.takeIf { it.isNotBlank() }
    val rawDescription = description?.takeIf { it.isNotBlank() }
        ?: document?.fileName?.takeIf { it.isNotBlank() }
        ?: audio?.title?.takeIf { it.isNotBlank() }

    val titleLooksLikeHandle = rawTitle?.startsWith("@") == true && !rawDescription.isNullOrBlank()
    val candidateTitle = if (titleLooksLikeHandle) rawDescription else rawTitle
    val candidateDescription = if (titleLooksLikeHandle) rawTitle else rawDescription

    return LinkPreviewMeta(
        kicker = kicker,
        title = candidateTitle ?: fallbackHost ?: sourceUrl.takeIf { it.isNotBlank() },
        description = candidateDescription
            ?: sourceUrl.takeIf { it.isNotBlank() && it != candidateTitle }
    )
}

private fun WebPage.buildViewerCaption(meta: LinkPreviewMeta): String? {
    return listOfNotNull(
        meta.title?.takeIf { it.isNotBlank() },
        meta.description?.takeIf { it.isNotBlank() && it != meta.title }
    ).joinToString("\n").ifBlank {
        meta.kicker.takeIf { it.isNotBlank() }
    }
}

private fun WebPage.shouldOpenInWebView(
    isYouTube: Boolean,
    hasPlayableVideo: Boolean
): Boolean {
    if (isYouTube || hasPlayableVideo) return false
    return type is WebPage.LinkPreviewType.ExternalVideo || type is WebPage.LinkPreviewType.EmbeddedVideo
}

private fun WebPage.hasPlayableVideo(
    previewVideo: WebPage.Video?,
    previewAnimation: WebPage.Animation?
): Boolean {
    return !previewVideo?.path.isNullOrBlank() ||
            (previewVideo?.supportsStreaming == true && previewVideo.fileId != 0) ||
            !previewAnimation?.path.isNullOrBlank()
}

private fun WebPage.isVideoLike(
    previewType: WebPage.LinkPreviewType,
    previewVideo: WebPage.Video?,
    previewAnimation: WebPage.Animation?
): Boolean {
    return when (previewType) {
        is WebPage.LinkPreviewType.Video,
        is WebPage.LinkPreviewType.ExternalVideo,
        is WebPage.LinkPreviewType.EmbeddedVideo,
        is WebPage.LinkPreviewType.Animation,
        is WebPage.LinkPreviewType.EmbeddedAnimation -> true

        else -> previewVideo != null || previewAnimation != null
    }
}

private fun WebPage.resolveThumbnailData(isYouTube: Boolean): Any? {
    return photo?.path
        ?: photo?.thumbnailPath
        ?: photo?.minithumbnail
        ?: video?.thumbnailPath
        ?: video?.minithumbnail
        ?: animation?.thumbnailPath
        ?: animation?.minithumbnail
        ?: resolveYouTubeThumbnail(isYouTube = isYouTube)
        ?: sticker?.path
}

private fun WebPage.resolveYouTubeThumbnail(isYouTube: Boolean): String? {
    if (!isYouTube) return null
    val videoId = extractYouTubeId(url)
    return videoId?.let { "https://img.youtube.com/vi/$it/maxresdefault.jpg" }
}

private fun WebPage.resolveAspectRatio(): Float {
    val width = video?.width ?: photo?.width ?: animation?.width ?: embedWidth
    val height = video?.height ?: photo?.height ?: animation?.height ?: embedHeight
    return if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1.77f
}
