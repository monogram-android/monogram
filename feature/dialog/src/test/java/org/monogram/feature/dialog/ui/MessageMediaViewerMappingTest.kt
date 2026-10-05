package org.monogram.feature.dialog.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.core.ui.media.MediaViewerKind

class MessageMediaViewerMappingTest {
    @Test
    fun mapsEverySupportedKindWithoutCollapsingAudioOrVideoNotes() {
        val expected = mapOf(
            "photo" to MediaViewerKind.PHOTO,
            "video" to MediaViewerKind.VIDEO,
            "gif" to MediaViewerKind.VIDEO,
            "video_note" to MediaViewerKind.VIDEO_NOTE,
            "voice" to MediaViewerKind.VOICE,
            "audio" to MediaViewerKind.AUDIO,
            "document" to MediaViewerKind.UNKNOWN,
            null to MediaViewerKind.UNKNOWN,
        )
        expected.forEach { (kind, mapped) -> assertEquals("kind=$kind", mapped, viewerKind(kind)) }
    }
}
