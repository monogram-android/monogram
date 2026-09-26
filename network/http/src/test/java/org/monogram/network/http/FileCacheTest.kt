package org.monogram.network.http

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileCacheTest {
    @Test
    fun removeDocumentPreservesOtherFilesAndPersistsIndex() {
        val root = tmp.newFolder("remove-document")
        val cache = FileCache(root)
        cache.put("doc:1", byteArrayOf(1, 2, 3), 7L, FileCache.KIND_FILES)
        cache.put("doc:2", byteArrayOf(4, 5), 7L, FileCache.KIND_FILES)
        assertTrue(cache.remove("doc:1"))
        val restored = FileCache(root)
        assertEquals(null, restored.get("doc:1"))
        assertTrue(restored.get("doc:2")!!.exists())
        assertEquals(listOf("doc:2"), restored.records().map { it.key })
        assertEquals(2L, restored.usageByChat()[7L])
    }

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun putGetAndClearRoundTrip() {
        val cache = FileCache(tmp.newFolder("media"), maxBytes = 1024 * 1024)
        val file = cache.put("photo:1", byteArrayOf(1, 2, 3, 4))
        assertTrue(file.exists())
        assertEquals(4, cache.totalBytes())
        assertTrue(cache.get("photo:1")!!.exists())

        val freed = cache.clear()
        assertEquals(4, freed)
        assertEquals(0, cache.totalBytes())
        assertFalse(cache.get("photo:1")?.exists() == true)
    }

    @Test
    fun newInstanceSeesExistingFile() {
        val root = tmp.newFolder("durable")
        FileCache(root, maxBytes = 1024 * 1024).put("photo:9", byteArrayOf(1, 2, 3))
        val again = FileCache(root, maxBytes = 1024 * 1024)
        assertTrue(again.get("photo:9")!!.exists())
        assertEquals(3, again.get("photo:9")!!.length())
    }

    @Test
    fun peerAvatarAliasSurvivesNewCacheInstance() {
        val root = tmp.newFolder("avatars")
        val first = FileCache(root, maxBytes = 1024 * 1024)
        val photo = first.put("photo:7", byteArrayOf(9, 8, 7, 6))
        first.putFile("avatar:11", photo, chatId = 11L, kind = FileCache.KIND_PHOTOS)
        val again = FileCache(root, maxBytes = 1024 * 1024)
        assertTrue(again.get("avatar:11")!!.exists())
        assertEquals(4, again.get("avatar:11")!!.length())
    }

    @Test
    fun putFileSamePathDoesNotCopy() {
        val cache = FileCache(tmp.newFolder("media"), maxBytes = 1024 * 1024)
        val written = cache.put("avatar:1", byteArrayOf(9, 8, 7))
        val again = cache.putFile("avatar:1", written)
        assertEquals(written.canonicalPath, again.canonicalPath)
        assertEquals(3, again.length())
    }

    @Test
    fun clearChatOnlyRemovesThatChat() {
        val cache = FileCache(tmp.newFolder("media"), maxBytes = 1024 * 1024)
        cache.put("photo:1", byteArrayOf(1, 2, 3, 4), chatId = 10L, kind = FileCache.KIND_PHOTOS)
        cache.put("photo:2", byteArrayOf(5, 6), chatId = 20L, kind = FileCache.KIND_PHOTOS)
        assertEquals(4, cache.clearChat(10L))
        assertTrue(cache.get("photo:2")!!.exists())
        assertFalse(cache.get("photo:1")?.exists() == true)
        assertEquals(mapOf(20L to 2L), cache.usageByChat())
    }

    @Test
    fun unindexedFilesCountAsOtherAndFillTheRing() {
        val root = tmp.newFolder("media")
        File(root, "leftover-bytes").writeBytes(ByteArray(8) { 1 })
        val cache = FileCache(root, maxBytes = 1024 * 1024)
        assertEquals(8, cache.totalBytes())
        assertEquals(8L, cache.usageByKind()[FileCache.KIND_OTHER])
        assertEquals(8, cache.clearKind(FileCache.KIND_OTHER))
        assertEquals(0, cache.totalBytes())
    }

    @Test
    fun deferredMaintenanceKeepsConstructionCheapAndBackfillsOnDemand() {
        val root = tmp.newFolder("deferred-maintenance")
        File(root, "leftover-bytes").writeBytes(ByteArray(8) { 1 })
        val cache = FileCache(root, maxBytes = 1024 * 1024, maintainOnInit = false)

        assertTrue(cache.records().isEmpty())
        cache.maintain()
        assertEquals(8L, cache.usageByKind()[FileCache.KIND_OTHER])
    }

    @Test
    fun indexSurvivesRelaunch() {
        val root = tmp.newFolder("durable")
        FileCache(root, maxBytes = 1024 * 1024).put(
            "photo:7",
            byteArrayOf(1, 2, 3, 4, 5),
            chatId = 99L,
            kind = FileCache.KIND_PHOTOS,
        )
        val again = FileCache(root, maxBytes = 1024 * 1024)
        assertEquals(mapOf(FileCache.KIND_PHOTOS to 5L), again.usageByKind())
        assertEquals(mapOf(99L to 5L), again.usageByChat())
    }

    @Test
    fun inferKindFromKeyAndMediaHint() {
        assertEquals(FileCache.KIND_PHOTOS, FileCache.inferKind("photo:1"))
        assertEquals(FileCache.KIND_PHOTOS, FileCache.inferKind("web:9", "webpage"))
        assertEquals(FileCache.KIND_VIDEOS, FileCache.inferKind("doc:2", "video"))
        assertEquals(FileCache.KIND_VIDEOS, FileCache.inferKind("doc:4:thumb"))
        assertEquals(FileCache.KIND_STICKERS, FileCache.inferKind("emoji:9"))
        assertEquals(FileCache.KIND_FILES, FileCache.inferKind("doc:3", "document"))
        assertEquals(FileCache.KIND_OTHER, FileCache.inferKind("avatar:1"))
    }
}
