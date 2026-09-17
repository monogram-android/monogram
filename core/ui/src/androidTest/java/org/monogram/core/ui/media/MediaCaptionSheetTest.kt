package org.monogram.core.ui.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.theme.MonogramTheme

/**
 * Caption chrome of the media viewer: the header, the two actions, and the rule that an
 * unknown sender is left out instead of rendered as a placeholder.
 */
class MediaCaptionSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val caption = "Альбом с подписью"

    private var shownInChat = 0
    private var copiedCaptions = 0

    private fun photo(name: String, background: Int, accent: Int): File {
        val target = File(compose.activity.cacheDir, name)
        if (target.exists()) return target
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(background)
            drawRect(80f, 80f, 700f, 820f, android.graphics.Paint().apply { color = accent })
        }
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return target
    }

    private fun album(sender: String?): List<MediaViewerItem> = listOf(
        MediaViewerItem(
            id = "c1",
            kind = MediaViewerKind.PHOTO,
            source = MediaSource.Local(photo("cap-a.png", Color.rgb(226, 231, 240), Color.rgb(70, 130, 220))),
            preview = photo("cap-a.png", Color.rgb(226, 231, 240), Color.rgb(70, 130, 220)),
            caption = caption,
            senderName = sender,
            dateLabel = "06:00",
        ),
        MediaViewerItem(
            id = "c2",
            kind = MediaViewerKind.PHOTO,
            source = MediaSource.Local(photo("cap-b.png", Color.rgb(24, 26, 32), Color.rgb(200, 90, 140))),
            preview = photo("cap-b.png", Color.rgb(24, 26, 32), Color.rgb(200, 90, 140)),
            senderName = sender,
            dateLabel = "06:00",
        ),
        MediaViewerItem(
            id = "c3",
            kind = MediaViewerKind.PHOTO,
            source = MediaSource.Local(photo("cap-c.png", Color.rgb(30, 40, 34), Color.rgb(90, 200, 150))),
            preview = photo("cap-c.png", Color.rgb(30, 40, 34), Color.rgb(90, 200, 150)),
            senderName = sender,
            dateLabel = "06:00",
        ),
    )

    private fun render(sender: String?) {
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                MediaViewerTheme {
                    MediaViewerShell(
                        album = rememberAlbumState(album(sender), startIndex = 0),
                        onDismiss = {},
                        chatKey = "caption-test",
                        actions = MediaViewerActions(
                            onShowInChat = { shownInChat++ },
                            onCopyCaption = { copiedCaptions++ },
                        ),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun openCaptionSheet() {
        compose.onNodeWithContentDescription(
            compose.activity.getString(org.monogram.core.ui.R.string.media_caption_expand),
        ).performClick()
        compose.waitForIdle()
    }

    @Test
    fun sheetShowsSenderMetaCaptionAndBothActions() {
        render(sender = "testing bot")
        openCaptionSheet()

        // Top bar and sheet header.
        assertEquals(
            "the sheet header repeats the sender",
            2,
            compose.onAllNodes(hasText("testing bot")).fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("06:00").assertIsDisplayed()
        compose.onNodeWithText("1/3").assertIsDisplayed()
        compose.onNodeWithText(caption).assertIsDisplayed()

        compose.onNodeWithText("Show in chat").performClick()
        compose.waitForIdle()
        assertEquals("Show in chat must reach the chat", 1, shownInChat)

        compose.onNodeWithText("Copy caption").performClick()
        compose.waitForIdle()
        assertEquals("Copy caption must copy the caption", 1, copiedCaptions)

        compose.onNodeWithContentDescription(
            compose.activity.getString(org.monogram.core.ui.R.string.media_caption_collapse),
        ).performClick()
        compose.waitForIdle()
        assertEquals(
            "the collapse control must close the sheet",
            0,
            compose.onAllNodes(hasContentDescription(
                compose.activity.getString(org.monogram.core.ui.R.string.media_caption_collapse),
            )).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun unknownSenderIsLeftOut() {
        render(sender = null)

        val unknown = compose.activity.getString(org.monogram.core.ui.R.string.media_sender_unknown)
        assertEquals(
            "\"$unknown\" must not be rendered when the album has no sender",
            0,
            compose.onAllNodes(hasText(unknown)).fetchSemanticsNodes().size,
        )

        openCaptionSheet()
        compose.onNodeWithText("06:00").assertIsDisplayed()
        compose.onNodeWithText("1/3").assertIsDisplayed()
        compose.onNodeWithText("Show in chat").assertIsDisplayed()
        assertEquals(
            "\"$unknown\" must not leak into the caption sheet either",
            0,
            compose.onAllNodes(hasText(unknown)).fetchSemanticsNodes().size,
        )
    }
}
