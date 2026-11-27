package org.monogram.feature.chats.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.models.Chat
import org.monogram.core.models.PeerId
import org.monogram.network.http.MediaFetchKind
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository
import org.monogram.network.http.TelegramMediaFetcher
import org.monogram.network.http.TelegramPeerInlineThumbPeek
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

class VisibleChatAvatarDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun visibleRowReplacesOfflineAvatarWithoutTapAndLeavesNoPhotoIdle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "visible-avatar").toFile()
        val visible =
            Chat(PeerId(42), "Has photo", photoCacheKey = "avatar:42:2", photoKnown = true)
        val noPhoto = Chat(PeerId(43), "No photo", photoKnown = true)
        val requests = ConcurrentLinkedQueue<Triple<PeerId, MediaFetchKind, Int>>()
        val previewReads = ConcurrentLinkedQueue<PeerId>()
        val releaseDisplay = CompletableDeferred<Unit>()
        val releaseFull = CompletableDeferred<Unit>()
        val current = png(android.graphics.Color.GREEN)
        val preview = png(android.graphics.Color.YELLOW)
        val repo =
            MediaRepository(
                cacheRoot = root,
                telegramFetcher =
                    TelegramMediaFetcher { peer, _, path, kind, priority ->
                        requests.add(Triple(peer, kind, priority))
                        if (kind == MediaFetchKind.Display) releaseDisplay.await() else releaseFull.await()
                        File(path).writeBytes(current)
                        Outcome.Ok(path)
                    },
                peerInlineThumbPeek =
                    TelegramPeerInlineThumbPeek { peer ->
                        previewReads.add(peer)
                        preview
                    },
            )
        try {
            runBlocking {
                repo.cacheThumbnail("avatar:42", png(android.graphics.Color.RED), 42, "photo")
                repo.cacheThumbnail("avatar:43", png(android.graphics.Color.BLUE), 43, "photo")
            }
            rule.setContent {
                MaterialTheme {
                    Column {
                        for (chat in listOf(visible, noPhoto)) {
                            ChatRow(
                                chat = chat,
                                selected = false,
                                savedMessages = false,
                                mediaRepository = repo,
                                showAvatar = true,
                                showReadStatus = true,
                                texts = chatListTexts(),
                                onClick = { error("Unexpected row tap") },
                                onAvatarClick = { error("Unexpected avatar tap") },
                            )
                        }
                    }
                }
            }
            rule.waitUntil(5_000) { shows(Color.Red) }
            releaseDisplay.complete(Unit)
            rule.waitUntil(5_000) { shows(Color.Green) && requests.size >= 2 }
            assertEquals(
                listOf(
                    Triple(visible.id, MediaFetchKind.Display, MediaPriority.THUMB),
                    Triple(visible.id, MediaFetchKind.Full, MediaPriority.IDLE),
                ),
                requests.toList(),
            )
            assertEquals(listOf(visible.id), previewReads.toList())
            assertEquals(false, shows(Color.Blue))
        } finally {
            releaseDisplay.complete(Unit)
            releaseFull.complete(Unit)
            repo.shutdown()
            root.deleteRecursively()
        }
    }

    private fun shows(color: Color): Boolean {
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        return (0 until pixels.height step 4).any { y ->
            (0 until pixels.width step 4).any { x -> pixels[x, y] == color }
        }
    }

    private fun png(color: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
}
