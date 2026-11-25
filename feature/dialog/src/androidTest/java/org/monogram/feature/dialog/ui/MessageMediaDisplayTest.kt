package org.monogram.feature.dialog.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.ui.AutoDownloadNetwork
import org.monogram.core.ui.DownloadSettings
import org.monogram.network.http.MediaFetchKind
import org.monogram.network.http.MediaRepository
import org.monogram.network.http.TelegramMediaFetcher
import org.monogram.network.http.legacyPhotoDisplayCacheKey
import org.monogram.network.http.photoDisplayCacheKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import android.graphics.Color as AndroidColor

class MessageMediaDisplayTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun visiblePhotoUpgradesWhenAllowedAndReopensFromDisplayCacheOffline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "message-media-display").apply { deleteRecursively() }
        val calls = CopyOnWriteArrayList<MediaFetchKind>()
        val allowDisplay = CompletableDeferred<Unit>()
        val online = mutableStateOf(false)
        val repository =
            MediaRepository(
                root,
                telegramFetcher =
                    TelegramMediaFetcher { _, _, path, kind, _ ->
                        calls += kind
                        if (kind == MediaFetchKind.Display) allowDisplay.await()
                        File(path).writeBytes(photoBytes(800, AndroidColor.BLUE))
                        Outcome.Ok(path)
                    },
            )
        val message = photoMessage()
        val network = AutoDownloadNetwork.Wifi
        val original = DownloadSettings.state.value.presetFor(network)
        val allowed = original.copy(enabled = true, photos = true)
        val visit = mutableIntStateOf(0)
        try {
            DownloadSettings.setNetworkOverride(network)
            DownloadSettings.setPreset(network, allowed)
            val fullKey = checkNotNull(message.mediaCacheKey)
            runBlocking {
                checkNotNull(
                    repository.cacheThumbnail(
                        legacyPhotoDisplayCacheKey(fullKey),
                        photoBytes(320, AndroidColor.RED),
                        1,
                        "photo"
                    )
                )
            }
            compose.setContent {
                CompositionLocalProvider(LocalDisplayUpgradeOnline provides online.value) {
                    key(visit.intValue) {
                        MessageMedia(
                            message,
                            repository,
                            modifier = Modifier.testTag("photo-${visit.intValue}")
                        )
                    }
                }
            }
            waitForColor(AndroidColor.RED)
            assertEquals(emptyList<MediaFetchKind>(), calls.toList())

            compose.runOnIdle { online.value = true }
            compose.waitUntil(5_000) { MediaFetchKind.Display in calls }
            waitForColor(AndroidColor.RED)
            allowDisplay.complete(Unit)
            waitForColor(AndroidColor.BLUE)
            assertTrue(repository.cachedFile(photoDisplayCacheKey(fullKey))?.isFile == true)
            assertNull(repository.cachedFile(fullKey))

            compose.runOnIdle {
                online.value = false
                visit.intValue++
            }
            waitForColor(AndroidColor.BLUE, visit = 1)
            assertEquals(listOf(MediaFetchKind.Display), calls.toList())
        } finally {
            repository.shutdown()
            root.deleteRecursively()
            DownloadSettings.setPreset(network, original)
            DownloadSettings.setNetworkOverride(null)
        }
    }

    @Test
    fun smallerDisplayCandidateDoesNotReplaceLegacyPhoto() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(
                context.cacheDir,
                "message-media-smaller-display"
            ).apply { deleteRecursively() }
            val repository =
                MediaRepository(
                    root,
                    telegramFetcher =
                        TelegramMediaFetcher { _, _, path, kind, _ ->
                            assertEquals(MediaFetchKind.Display, kind)
                            File(path).writeBytes(photoBytes(320, AndroidColor.BLUE))
                            Outcome.Ok(path)
                        },
                )
            try {
                val message = photoMessage()
                val fullKey = checkNotNull(message.mediaCacheKey)
                val legacyKey = legacyPhotoDisplayCacheKey(fullKey)
                val original = photoBytes(800, AndroidColor.RED)
                checkNotNull(repository.cacheThumbnail(legacyKey, original, 1, "photo"))
                assertTrue(repository.ensureLocalMessageDisplay(message) is Outcome.Err)
                assertTrue(
                    repository.cachedFile(legacyKey)?.readBytes()?.contentEquals(original) == true
                )
                assertNull(repository.cachedFile(photoDisplayCacheKey(fullKey)))
                assertNull(repository.cachedFile(fullKey))
            } finally {
                repository.shutdown()
                root.deleteRecursively()
            }
        }

    private fun photoMessage() =
        Message(
            id = MessageId(PeerId(1), 1),
            senderId = null,
            text = null,
            date = 0L,
            outgoing = false,
            mediaKind = "photo",
            mediaCacheKey = "photo:1",
            thumbCacheKey = "photo:1:thumb",
        )

    private fun photoBytes(
        size: Int,
        color: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun waitForColor(
        color: Int,
        visit: Int = 0,
    ) {
        val tag = "photo-$visit"
        compose.waitUntil(5_000) {
            val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) == color
        }
    }
}
