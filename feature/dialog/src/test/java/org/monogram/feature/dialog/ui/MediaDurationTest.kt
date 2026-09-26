package org.monogram.feature.dialog.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaDurationTest {
    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("0:00", formatMediaDuration(0))
        assertEquals("0:05", formatMediaDuration(5))
        assertEquals("1:02", formatMediaDuration(62))
        assertEquals("1:01:01", formatMediaDuration(3661))
    }

    @Test
    fun clampsNegativeDuration() {
        assertEquals("0:00", formatMediaDuration(-4))
    }

    @Test
    fun aspectRatioClampsAndRejectsInvalid() {
        assertEquals(16f / 9f, mediaAspectRatio(1280, 720)!!, 0.001f)
        assertEquals(0.4f, mediaAspectRatio(10, 1000)!!, 0.001f)
        assertEquals(2.5f, mediaAspectRatio(1000, 10)!!, 0.001f)
        assertNull(mediaAspectRatio(null, 720))
        assertNull(mediaAspectRatio(1280, 0))
    }

    @Test
    fun photoBubbleHasAReadableMinimum() {
        val square = photoDisplaySize(40, 40)
        assertEquals(true, square.first >= PHOTO_MIN_DP)
        assertEquals(true, square.second >= PHOTO_MIN_DP)
        val wide = photoDisplaySize(1280, 720)
        assertEquals(true, wide.first >= PHOTO_MIN_DP)
        assertEquals(true, wide.first <= PHOTO_MAX_WIDTH_DP)
        assertEquals(true, wide.second >= PHOTO_MIN_DP)
        val tall = photoDisplaySize(100, 400)
        assertEquals(true, tall.second <= PHOTO_MAX_HEIGHT_DP)
        assertEquals(true, tall.first >= PHOTO_MIN_DP)
    }

    @Test
    fun unknownMediaUsesStablePhotoOrVideoFrame() {
        assertEquals(
            photoDisplaySize(null, null, PHOTO_DEFAULT_ASPECT),
            visualMediaDisplaySize("photo", null, null),
        )
        assertEquals(
            photoDisplaySize(null, null, VIDEO_DEFAULT_ASPECT),
            visualMediaDisplaySize("video", null, null),
        )
        assertEquals(
            photoDisplaySize(1280, 720),
            visualMediaDisplaySize("photo", 1280, 720),
        )
        val photo = visualMediaDisplaySize("photo", null, null)
        assertEquals(true, photo.first >= PHOTO_MIN_DP)
        assertEquals(true, photo.second >= PHOTO_MIN_DP)
        assertEquals(true, photo.second < photo.first)
    }

    @Test
    fun edgeMediaSpansTheBubbleAndStaysAspectCorrect() {
        val wide = bubbleEdgeMediaDisplaySize("photo", 1280, 720)
        assertEquals(BUBBLE_MAX_WIDTH_DP, wide.first)
        assertEquals(true, wide.second < PHOTO_MAX_HEIGHT_DP)
        val tall = bubbleEdgeMediaDisplaySize("photo", 300, 1200)
        assertEquals(true, tall.second <= PHOTO_MAX_HEIGHT_DP)
        assertEquals(true, tall.first < BUBBLE_MAX_WIDTH_DP)
        assertEquals(
            bubbleEdgeMediaDisplaySize("gif", null, null),
            photoDisplaySize(null, null, VIDEO_DEFAULT_ASPECT, maxWidthDp = BUBBLE_MAX_WIDTH_DP),
        )
    }

    @Test
    fun onlyPicturesVideosAndGifsBleedToTheBubbleEdge() {
        assertEquals(true, isEdgeMediaKind("photo"))
        assertEquals(true, isEdgeMediaKind("video"))
        assertEquals(true, isEdgeMediaKind("gif"))
        assertEquals(false, isEdgeMediaKind("sticker"))
        assertEquals(false, isEdgeMediaKind("document"))
        assertEquals(false, isEdgeMediaKind(null))
    }

    @Test
    fun captionlessVisualMediaKeepsClockOnThePicture() {
        assertEquals(
            true,
            shouldOverlayMediaMeta(
                stickerOnly = false,
                edgeVisualMedia = true,
                mediaCaption = false,
            ),
        )
        assertEquals(
            false,
            shouldOverlayMediaMeta(
                stickerOnly = false,
                edgeVisualMedia = true,
                mediaCaption = true,
            ),
        )
        assertEquals(
            true,
            shouldOverlayMediaMeta(
                stickerOnly = true,
                edgeVisualMedia = false,
                mediaCaption = false,
            ),
        )
        assertEquals(
            false,
            shouldOverlayMediaMeta(
                stickerOnly = false,
                edgeVisualMedia = false,
                mediaCaption = false,
            ),
        )
    }

    @Test
    fun mediaCoversBottomInsetWhenItIsTheLastBubbleChild() {
        assertEquals(
            true,
            shouldBleedMediaBottom(
                edgeMedia = true,
                mediaCaption = false,
                hasComments = false,
            ),
        )
        assertEquals(
            false,
            shouldBleedMediaBottom(
                edgeMedia = true,
                mediaCaption = true,
                hasComments = false,
            ),
        )
        assertEquals(
            false,
            shouldBleedMediaBottom(
                edgeMedia = true,
                mediaCaption = false,
                hasComments = true,
            ),
        )
        assertEquals(
            false,
            shouldBleedMediaBottom(
                edgeMedia = false,
                mediaCaption = false,
                hasComments = false,
            ),
        )
    }

    @Test
    fun fileSizeFormats() {
        assertEquals("512 B", formatFileSize(512))
        assertEquals("2 KB", formatFileSize(2048))
    }

    @Test
    fun downloadProgressUsesKnownTotal() {
        assertEquals(0f, downloadProgressFraction(0, 2000)!!, 0.001f)
        assertEquals(0.5f, downloadProgressFraction(1000, 2000)!!, 0.001f)
        assertEquals(null, downloadProgressFraction(500, null))
        assertEquals("1000 B / 2 KB", formatDownloadProgress(1000, 2000))
        assertEquals("0 B / 2 KB", formatDownloadProgress(0, 2000))
        assertEquals("512 B", formatDownloadProgress(512, null))
        assertNull(formatDownloadProgress(0, null))
    }

    @Test
    fun documentsDoNotAutoFetchFullFile() {
        val wifi = org.monogram.core.ui.AutoDownloadPreset.WIFI
        val roaming = org.monogram.core.ui.AutoDownloadPreset.ROAMING
        assertEquals(true, shouldAutoFetchFullMedia("gif", false, 200_000, wifi))
        assertEquals(false, shouldAutoFetchFullMedia("gif", false, null, wifi))
        assertEquals(true, shouldAutoFetchFullMedia("video", false, 200_000, wifi))
        assertEquals(false, shouldAutoFetchFullMedia("video", false, 20L * org.monogram.core.ui.AutoDownloadPreset.MB, wifi))
        assertEquals(false, shouldAutoFetchFullMedia("video", false, 200_000, roaming))
        assertEquals(false, shouldAutoFetchFullMedia("document", false, 200_000, roaming))
        assertEquals(true, shouldAutoFetchFullMedia("document", false, 200_000, wifi))
        assertEquals(false, shouldAutoFetchFullMedia("document", false, 4L * org.monogram.core.ui.AutoDownloadPreset.MB, wifi))
        assertEquals(true, shouldAutoFetchFullMedia("document", true, 200_000, roaming))
        assertEquals(true, shouldAutoFetchFullMedia("sticker", false))
        assertEquals(false, shouldAutoFetchFullMedia("photo", false))
        assertEquals(true, shouldAutoFetchFullMedia("photo", true))
        assertEquals(true, shouldAutoFetchDisplayMedia("photo", preset = wifi))
        assertEquals(false, shouldAutoFetchDisplayMedia("document", 200_000, wifi))
        assertEquals(true, shouldAutoFetchDisplayMedia("webpage"))
        assertEquals(true, shouldAutoFetchDisplayMedia("video"))
        assertEquals(true, shouldAutoFetchDisplayMedia("gif"))
        assertEquals(false, shouldAutoFetchDisplayMedia("sticker"))
        assertEquals(true, shouldFetchDisplayPreview("photo", false, preset = wifi))
        assertEquals(false, shouldFetchDisplayPreview("video", false, 200_000, wifi))
        assertEquals(false, shouldFetchDisplayPreview("gif", false, 200_000, wifi))
        assertEquals(true, shouldFetchDisplayPreview("video", false, 200_000, roaming))
        assertEquals(true, shouldCancelOnViewportDetach(visible = false, userRequested = false, viewerOpen = false))
        assertEquals(false, shouldCancelOnViewportDetach(visible = true, userRequested = false, viewerOpen = false))
        assertEquals(false, shouldCancelOnViewportDetach(visible = false, userRequested = true, viewerOpen = false))
        assertEquals(false, shouldCancelOnViewportDetach(visible = false, userRequested = false, viewerOpen = true))
        assertEquals(false, shouldAutoFetchFullMedia("audio", false))
        assertEquals(false, shouldAutoFetchFullMedia("voice", false))
        assertEquals(org.monogram.network.http.MediaPriority.VISIBLE, mediaFullPriority(false))
        assertEquals(org.monogram.network.http.MediaPriority.USER, mediaFullPriority(true))
        assertEquals(
            false,
            shouldFetchMessageThumb(
                "photo",
                hasThumbFile = false,
                strippedJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()),
                thumbCacheKey = "photo:1:thumb",
                fullCacheKey = "photo:1",
            ),
        )
        assertEquals(
            true,
            shouldFetchMessageThumb(
                "photo",
                hasThumbFile = false,
                strippedJpeg = null,
                thumbCacheKey = "photo:1:thumb",
                fullCacheKey = "photo:1",
            ),
        )
    }

    @Test
    fun streamableVisibleVideosAutoplayOnWifiOnly() {
        val wifi = org.monogram.core.ui.AutoDownloadPreset.WIFI
        val roaming = org.monogram.core.ui.AutoDownloadPreset.ROAMING
        assertEquals(true, shouldAutoplayChatVideo("video", true, 8_000_000, true, true, wifi))
        assertEquals(true, shouldAutoplayChatVideo("video", true, 20L * org.monogram.core.ui.AutoDownloadPreset.MB, true, true, wifi))
        assertEquals(false, shouldAutoplayChatVideo("video", false, 8_000_000, true, true, wifi))
        assertEquals(false, shouldAutoplayChatVideo("video", true, 8_000_000, false, true, wifi))
        assertEquals(false, shouldAutoplayChatVideo("video", true, 8_000_000, true, false, wifi))
        assertEquals(false, shouldAutoplayChatVideo("gif", true, 8_000_000, true, true, wifi))
        assertEquals(false, shouldAutoplayChatVideo("video", true, 8_000_000, true, true, roaming))
    }

    @Test
    fun stickerFilenameIsNotACaption() {
        assertEquals(false, shouldShowMessageCaption("sticker", "sticker.webp"))
        assertEquals(false, shouldShowMessageCaption("sticker", "🤡"))
        assertEquals(true, isStickerAltText("🤡"))
        assertEquals(false, isStickerAltText("hello"))
        assertEquals(true, shouldShowMessageCaption("sticker", "hello"))
        assertEquals(true, shouldShowMessageCaption(null, "hello"))
    }

    @Test
    fun stickerDisplayKeepsAspectAndFloor() {
        assertEquals(160 to 160, stickerDisplaySize(512, 512))
        assertEquals(160 to 160, stickerDisplaySize(null, null))
        val wide = stickerDisplaySize(512, 256)
        assertEquals(160, wide.first)
        assertEquals(true, wide.second < 160)
        assertEquals(true, wide.second >= 96)
    }

    @Test
    fun webpDocumentsCountAsStickers() {
        assertEquals(true, isStickerMedia("sticker", null))
        assertEquals(true, isStickerMedia("sticker_video", null))
        assertEquals(true, isStickerMedia("document", "troll.webp"))
        assertEquals(false, isStickerMedia("photo", null))
        assertEquals(false, isStickerMedia("document", "notes.pdf"))
    }
}
