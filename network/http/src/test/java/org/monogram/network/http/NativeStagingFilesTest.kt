package org.monogram.network.http

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.monogram.network.http.internal.isNativeStagingName
import org.monogram.network.http.internal.nativeStagingFiles

/**
 * `nativeStagingFiles` must select exactly `<destination>.<pid>.<sequence>.part`. The
 * expression below is the oracle for that grammar.
 */
class NativeStagingFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun oracleMatches(destinationName: String, candidate: String): Boolean =
        Regex("${Regex.escape(destinationName)}\\.[0-9]+\\.[0-9]+\\.part").matches(candidate)

    @Test
    fun filterAcceptsExactlyTheStagingNameGrammar() {
        val destinations = listOf(
            "video.mp4",
            "blob",
            "a.part",
            "archive.tar.gz",
            "a+b(c)[d]",
            "weird.",
        )
        val candidates = listOf(
            "video.mp4.1.2.part",
            "video.mp4.1234.0.part",
            "video.mp4.1.2.part.tmp",
            "video.mp4.1.2",
            "video.mp4.1.part",
            "video.mp4..2.part",
            "video.mp4.1..part",
            "video.mp4.a.2.part",
            "video.mp4.1.b.part",
            "video.mp4.1.2.partx",
            "video.mp4.-1.2.part",
            "video.mp41.2.part",
            "videoXmp4.1.2.part",
            "video.mp4.part",
            "video.mp4..part",
            "blob.1.2.part",
            "blob..part",
            "blob.part",
            "blob.1.2.part.extra",
            "a.part.1.2.part",
            "a.part..part",
            "archive.tar.gz.7.9.part",
            "a+b(c)[d].1.2.part",
            "weird..1.2.part",
            "weird.part",
            "",
            ".part",
            "1.2.part",
        )
        for (destination in destinations) {
            val prefix = "$destination."
            for (candidate in candidates) {
                assertEquals(
                    "mismatch for destination=$destination candidate=$candidate",
                    oracleMatches(destination, candidate),
                    isNativeStagingName(candidate, prefix),
                )
            }
        }
    }

    @Test
    fun collectsOnlyNativeStagingSiblingsOfDestination() {
        val root = tmp.newFolder("staging")
        val destination = File(root, "photo.jpg")
        val staged = listOf("photo.jpg.100.1.part", "photo.jpg.101.2.part")
        val ignored = listOf(
            "photo.jpg.part",
            "photo.jpg.100.part",
            "photo.jpg.100.1.part.tmp",
            "photo.jpg.100.x.part",
            "photo.png.100.1.part",
            "unrelated.txt",
        )
        staged.forEach { File(root, it).writeText("x") }
        ignored.forEach { File(root, it).writeText("x") }
        File(root, "photo.jpg.100.1.part").writeText("payload")

        val found = nativeStagingFiles(destination).map { it.name }.sorted()

        assertEquals(staged.sorted(), found)
        assertTrue(nativeStagingFiles(File(root, "missing.jpg")).isEmpty())
    }

    @Test
    fun missingParentDirectoryYieldsEmptyList() {
        val orphan = File(tmp.root, "no-such-dir/photo.jpg")
        assertTrue(nativeStagingFiles(orphan).isEmpty())
        assertEquals(emptyList<File>(), nativeStagingFiles(orphan))
    }
}
