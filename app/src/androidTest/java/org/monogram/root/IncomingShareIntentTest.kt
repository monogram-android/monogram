package org.monogram.root

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingShareIntentTest {
    private val first = Uri.parse("content://share.test/photo/1")
    private val second = Uri.parse("content://share.test/photo/2")

    @Test
    fun singleStreamMirroredInClipDataIsImportedOnce() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, first)
            clipData = ClipData.newRawUri("photo", first)
        }
        assertEquals(listOf(first), IncomingShareStager.streamUris(intent))
    }

    @Test
    fun singleShareFallsBackToClipData() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            clipData = ClipData.newRawUri("photo", first)
        }
        assertEquals(listOf(first), IncomingShareStager.streamUris(intent))
    }

    @Test
    fun multipleStreamsKeepOrderWithoutMirroredDuplicates() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
            clipData = ClipData.newRawUri("photos", first).apply {
                addItem(ClipData.Item(second))
            }
        }
        assertEquals(listOf(first, second), IncomingShareStager.streamUris(intent))
    }
}
