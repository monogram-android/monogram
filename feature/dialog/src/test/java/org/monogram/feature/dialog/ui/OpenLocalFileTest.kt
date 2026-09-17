package org.monogram.feature.dialog.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenLocalFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun apkMimeAndShareNameKeepApkSuffix() {
        assertEquals(APK_MIME, mimeFromName("app.apk"))
        assertEquals("App.apk", shareFileName("My/App.apk"))
        assertEquals("My App.apk", shareFileName("My App.apk"))
        assertEquals("setup.apk", shareFileName("setup.APK"))
    }

    @Test
    fun externalViewCopyUsesOriginalApkNameOutsideCache() {
        val media = tmp.newFolder("files", "media")
        val hashed = File(media, "abc123")
        hashed.writeBytes(byteArrayOf(1, 2, 3, 4))
        val share = fileForExternalView(hashed, "chat.apk")
        assertEquals("chat.apk", share.name)
        assertTrue(share.path.replace('\\', '/').endsWith("/open/chat.apk"))
        assertEquals(4, share.length())
        assertTrue(hashed.exists())
    }
}
