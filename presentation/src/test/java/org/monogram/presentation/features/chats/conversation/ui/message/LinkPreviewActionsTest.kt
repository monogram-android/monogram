package org.monogram.presentation.features.chats.conversation.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.WebPage

class LinkPreviewActionsTest {

    @Test
    fun `photo article resolves link primary and image media action`() {
        val preview = WebPage(
            url = "https://telegra.ph/Battlefield-6---Obnovlenie-1320-06-08",
            displayUrl = "telegra.ph/Battlefield-6---Obnovlenie-1320-06-08",
            type = WebPage.LinkPreviewType.Article,
            siteName = "Example",
            title = "Article",
            description = "Description",
            photo = WebPage.Photo(
                path = "/tmp/cover.jpg",
                width = 1200,
                height = 630,
                fileId = 1,
                minithumbnail = null
            ),
            embedUrl = null,
            embedType = null,
            embedWidth = 0,
            embedHeight = 0,
            duration = 0,
            author = "Author",
            video = null,
            audio = null,
            document = null,
            sticker = null,
            animation = null
        )

        val resolved = preview.resolveLinkPreview()

        assertEquals(
            LinkPreviewAction.OpenLink("https://telegra.ph/Battlefield-6---Obnovlenie-1320-06-08"),
            resolved.primaryAction
        )
        assertTrue(resolved.mediaAction is LinkPreviewAction.OpenImageViewer)
        assertEquals("/tmp/cover.jpg", resolved.thumbnailData)
    }

    @Test
    fun `photo thumbnail fallback keeps primary media action`() {
        val preview = basePreview(
            photo = WebPage.Photo(
                path = null,
                thumbnailPath = "/tmp/thumb.jpg",
                width = 320,
                height = 180,
                fileId = 7,
                thumbnailFileId = 8,
                minithumbnail = null
            )
        )

        val resolved = preview.resolveLinkPreview()

        assertEquals(LinkPreviewAction.OpenLink("https://example.com/post"), resolved.primaryAction)
        assertEquals(resolved.primaryAction, resolved.mediaAction)
        assertEquals("/tmp/thumb.jpg", resolved.thumbnailData)
    }

    @Test
    fun `fixed preview remote photo path still opens image viewer`() {
        val preview = basePreview(
            photo = WebPage.Photo(
                path = "https://convertico.com/samples/jpg/plant-jpg.jpg",
                width = 1200,
                height = 630,
                fileId = 0,
                minithumbnail = null
            )
        )

        val resolved = preview.resolveLinkPreview()

        assertTrue(resolved.mediaAction is LinkPreviewAction.OpenImageViewer)
        assertEquals("https://convertico.com/samples/jpg/plant-jpg.jpg", resolved.thumbnailData)
    }

    @Test
    fun `photo with path prefers full path thumbnail and opens image viewer`() {
        val preview = basePreview(
            photo = WebPage.Photo(
                path = "/tmp/photo.jpg",
                thumbnailPath = "/tmp/thumb.jpg",
                width = 1200,
                height = 630,
                fileId = 7,
                thumbnailFileId = 8,
                minithumbnail = null
            )
        )

        val resolved = preview.resolveLinkPreview()

        assertEquals("/tmp/photo.jpg", resolved.thumbnailData)
        assertTrue(resolved.mediaAction is LinkPreviewAction.OpenImageViewer)
    }

    @Test
    fun `streamable tdlib video resolves video viewer media action`() {
        val preview = basePreview(
            type = WebPage.LinkPreviewType.Video,
            video = WebPage.Video(
                path = "",
                width = 1280,
                height = 720,
                duration = 10,
                fileId = 42,
                thumbnailPath = "/tmp/thumb.jpg",
                supportsStreaming = true
            )
        )

        val resolved = preview.resolveLinkPreview()

        assertTrue(resolved.mediaAction is LinkPreviewAction.OpenVideoViewer)
        val request = (resolved.mediaAction as LinkPreviewAction.OpenVideoViewer).request
        assertEquals(42, request.fileId)
        assertTrue(request.supportsStreaming)
    }

    @Test
    fun `youtube preview resolves youtube viewer`() {
        val preview = basePreview(
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            siteName = "YouTube",
            type = WebPage.LinkPreviewType.ExternalVideo("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )

        val resolved = preview.resolveLinkPreview()

        assertEquals(
            LinkPreviewAction.OpenYouTube("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
            resolved.primaryAction
        )
    }

    @Test
    fun `external non playable video falls back to internal webview`() {
        val preview = basePreview(
            type = WebPage.LinkPreviewType.ExternalVideo("https://video.example/embed"),
            embedUrl = "https://video.example/embed"
        )

        val resolved = preview.resolveLinkPreview()

        assertEquals(
            LinkPreviewAction.OpenWebView("https://video.example/embed"),
            resolved.primaryAction
        )
        assertEquals(resolved.primaryAction, resolved.mediaAction)
    }

    @Test
    fun `web app preview resolves mini app`() {
        val preview = basePreview(
            type = WebPage.LinkPreviewType.WebApp("https://mini.example/app"),
            title = "Mini App"
        )

        val resolved = preview.resolveLinkPreview()

        assertEquals(
            LinkPreviewAction.OpenMiniApp("https://mini.example/app", "Mini App"),
            resolved.primaryAction
        )
    }

    @Test
    fun `instant view preview resolves instant viewer`() {
        val preview = basePreview(
            type = WebPage.LinkPreviewType.InstantView,
            url = "https://example.com/post"
        )

        val resolved = preview.resolveLinkPreview()

        assertEquals(
            LinkPreviewAction.OpenInstantView("https://example.com/post"),
            resolved.primaryAction
        )
    }

    private fun basePreview(
        url: String = "https://example.com/post",
        siteName: String? = "Example",
        title: String? = "Title",
        type: WebPage.LinkPreviewType = WebPage.LinkPreviewType.Article,
        embedUrl: String? = null,
        video: WebPage.Video? = null,
        photo: WebPage.Photo? = null
    ): WebPage {
        return WebPage(
            url = url,
            displayUrl = url.removePrefix("https://"),
            type = type,
            siteName = siteName,
            title = title,
            description = "Description",
            photo = photo,
            embedUrl = embedUrl,
            embedType = null,
            embedWidth = 0,
            embedHeight = 0,
            duration = 0,
            author = null,
            video = video,
            audio = null,
            document = null,
            sticker = null,
            animation = null
        )
    }
}
