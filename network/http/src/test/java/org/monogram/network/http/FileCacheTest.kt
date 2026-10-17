package org.monogram.network.http

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileCacheTest {
    @Test
    fun acquiredUnindexedFileSurvivesTrimBeforeStartupReconciliation() {
        val root = tmp.newFolder("unindexed-lease")
        val cache = FileCache(root, maxBytes = 4, maintainOnInit = false)
        cache.fileFor("held").writeBytes(byteArrayOf(1, 2, 3, 4))
        val lease = checkNotNull(cache.acquire("held"))
        try {
            cache.fileFor("other").writeBytes(byteArrayOf(5, 6, 7, 8))
            cache.trimIfNeeded()
            assertTrue(lease.file.exists())
            assertEquals(4L, cache.totalBytes())
            try {
                cache.put("optional", byteArrayOf(9))
                org.junit.Assert.fail("optional publication must account for an unindexed lease")
            } catch (error: FileCache.AdmissionException) {
                assertEquals(FileCache.AdmissionFailure.Capacity, error.reason)
            }
        } finally {
            lease.close()
        }
    }

    @Test
    fun optionalPublicationCannotExceedCapacityThroughProtection() {
        val cache = FileCache(tmp.newFolder("optional-admission"), maxBytes = 4)
        cache.setProtectedKeys("viewport", listOf("photo:oversized"))
        val failure = org.junit.Assert.assertThrows(FileCache.AdmissionException::class.java) {
            cache.put("photo:oversized", ByteArray(8))
        }
        assertEquals(FileCache.AdmissionFailure.Capacity, failure.reason)
        assertEquals(null, cache.get("photo:oversized"))
    }

    @Test
    fun leasedPublicationOverProtectedBudgetIsTemporary() {
        val cache = FileCache(tmp.newFolder("protected-budget"), maxBytes = 6)
        cache.put("photo:visible", ByteArray(4))
        val visible = checkNotNull(cache.acquire("photo:visible"))
        val source = tmp.newFile("protected-source").apply { writeBytes(ByteArray(4)) }
        val temporary = cache.publishLeased("photo:demand", source)
        assertTrue(visible.file.exists())
        assertTrue(temporary.file.exists())
        assertEquals(null, cache.acquire("photo:demand"))
        temporary.close()
        assertFalse(temporary.file.exists())
        assertTrue(visible.file.exists())
        visible.close()
    }

    @Test
    fun orphanAliasCannotEvictOrClearAnAcquiredFile() {
        val root = tmp.newFolder("orphan-lease")
        val cache = FileCache(root, maxBytes = 6, maintainOnInit = false)
        val file = cache.fileFor("photo:late").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        cache.maintain()
        val lease = checkNotNull(cache.acquire("photo:late"))
        try {
            file.setLastModified(1)
            File(root, "another-orphan").writeBytes(ByteArray(5))
            cache.maintain()
            cache.trimIfNeeded()
            assertTrue(file.exists())
            assertEquals(0L, cache.clear())
            assertEquals(null, cache.acquire("photo:late"))
            assertTrue(file.exists())
        } finally {
            lease.close()
        }
        assertFalse(file.exists())
    }

    @Test
    fun rememberingTheRealKeyReplacesOrphanAccounting() {
        val root = tmp.newFolder("orphan-accounting")
        val cache = FileCache(root, maintainOnInit = false)
        val file = cache.fileFor("photo:late").apply { writeBytes(ByteArray(3)) }
        cache.maintain()
        cache.remember("photo:late", 42L, FileCache.KIND_PHOTOS, file.length())
        assertEquals(listOf("photo:late"), cache.records().map { it.key })
        assertEquals(mapOf(FileCache.KIND_PHOTOS to 3L), cache.usageByKind())
        assertEquals(3L, cache.clear())
    }

    @Test
    fun oversizedLeasedPublicationIsRemovedAfterRelease() {
        val root = tmp.newFolder("temporary-publication")
        val cache = FileCache(root, maxBytes = 64)
        val source = tmp.newFile("temporary-source").apply { writeBytes(ByteArray(128)) }
        val lease = cache.publishLeased("video:temporary", source)
        cache.trimIfNeeded()
        assertTrue(lease.file.exists())
        assertEquals(null, cache.acquire("video:temporary"))
        lease.close()
        assertFalse(lease.file.exists())
        assertTrue(source.exists())
    }

    @Test
    fun publicationCannotReplaceALeasedFile() {
        val cache = FileCache(tmp.newFolder("leased-replacement"))
        cache.put("photo:1", byteArrayOf(1, 2, 3))
        val lease = checkNotNull(cache.acquire("photo:1"))
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            cache.put("photo:1", byteArrayOf(4, 5))
        }
        org.junit.Assert.assertArrayEquals(byteArrayOf(1, 2, 3), lease.file.readBytes())
        lease.close()
        cache.put("photo:1", byteArrayOf(4, 5))
        org.junit.Assert.assertArrayEquals(byteArrayOf(4, 5), cache.get("photo:1")!!.readBytes())
    }

    @Test
    fun clearDefersDeletionUntilTheFinalLeaseIsReleased() {
        val cache = FileCache(tmp.newFolder("leases"))
        cache.put("photo:1", byteArrayOf(1, 2, 3))
        val first = checkNotNull(cache.acquire("photo:1"))
        val second = checkNotNull(cache.acquire("photo:1"))
        assertEquals(0L, cache.clear())
        assertEquals(null, cache.acquire("photo:1"))
        first.close()
        assertTrue(second.file.exists())
        second.close()
        second.close()
        assertFalse(second.file.exists())
        assertEquals(0L, cache.totalBytes())
    }

    @Test
    fun oversizedPublicationFailsWithoutReplacingAnExistingEntry() {
        val root = tmp.newFolder("oversized")
        val cache = FileCache(root, maxBytes = 64)
        cache.put("photo:1", byteArrayOf(1, 2, 3))
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            cache.put("photo:1", ByteArray(128))
        }
        org.junit.Assert.assertArrayEquals(byteArrayOf(1, 2, 3), cache.get("photo:1")!!.readBytes())
        val source = File(root.parentFile, "oversized-source").apply { writeBytes(ByteArray(128)) }
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            cache.putFile("doc:2", source)
        }
        assertTrue(source.exists())
        assertEquals(null, cache.get("doc:2"))
    }

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
        assertEquals(0, cache.retainedDiskNames())
    }

    @Test
    fun removeDropsTheRetainedDiskName() {
        val cache = FileCache(tmp.newFolder("names"), maxBytes = 1024 * 1024)
        cache.put("photo:1", byteArrayOf(1))
        cache.put("photo:2", byteArrayOf(2))
        assertTrue(cache.retainedDiskNames() >= 2)
        cache.remove("photo:1")
        cache.get("photo:2")
        assertEquals(1, cache.retainedDiskNames())
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
        val first = FileCache(root, maxBytes = 1024 * 1024)
        first.put(
            "photo:7",
            byteArrayOf(1, 2, 3, 4, 5),
            chatId = 99L,
            kind = FileCache.KIND_PHOTOS,
        )
        first.flushIndex()
        val again = FileCache(root, maxBytes = 1024 * 1024)
        assertEquals(mapOf(FileCache.KIND_PHOTOS to 5L), again.usageByKind())
        assertEquals(mapOf(99L to 5L), again.usageByChat())
    }

    @Test
    fun finiteLimitEvictsOldestUnpinnedAndKeepsPinnedAndLeased() {
        var now = 1L
        val cache = FileCache(
            tmp.newFolder("limit"),
            maxBytes = 200,
            nowMs = { now },
            maintainOnInit = false
        )
        cache.put("old", ByteArray(40))
        now = 2
        cache.put("pinned", ByteArray(40))
        cache.pin("pinned")
        now = 3
        cache.put("leased", ByteArray(40))
        val lease = checkNotNull(cache.acquire("leased"))
        try {
            cache.setMaxBytes(50)
            assertNull(cache.get("old"))
            assertNotNull(cache.get("pinned"))
            assertNotNull(cache.get("leased"))
        } finally {
            lease.close()
        }
    }

    @Test
    fun unlimitedDoesNotSizeEvict() {
        val cache = FileCache(tmp.newFolder("unlimited"), maxBytes = 10, maintainOnInit = false)
        cache.setMaxBytes(null)
        cache.put("a", ByteArray(40))
        cache.put("b", ByteArray(40))
        cache.trimIfNeeded()
        assertNotNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        cache.setMaxBytes(40)
        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
    }

    @Test
    fun repositoryAppliesTheLimitOffTheCallerThread() = runBlocking {
        val root = tmp.newFolder("repo-limit")
        val seeded = FileCache(root, maxBytes = 10_000, maintainOnInit = false)
        seeded.put("old", ByteArray(80))
        seeded.put("keep", ByteArray(80))
        seeded.pin("keep")
        seeded.flushIndex()
        val caller = Thread.currentThread()
        val repository = MediaRepository(root, cacheLimitBytes = 100)
        try {
            withTimeout(5_000) {
                while (repository.cacheWorkThread == null || repository.cacheSizeBytes() > 100) delay(
                    20
                )
            }
            assertTrue(repository.cacheWorkThread != caller)
            assertTrue(repository.cacheSizeBytes() <= 100)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun inferKindFromKeyAndMediaHint() {
        assertEquals(FileCache.KIND_PHOTOS, FileCache.inferKind("photo:1"))
        assertEquals(FileCache.KIND_PHOTOS, FileCache.inferKind("web:9", "webpage"))
        assertEquals(FileCache.KIND_VIDEOS, FileCache.inferKind("doc:2", "video"))
        assertEquals(FileCache.KIND_VIDEOS, FileCache.inferKind("doc:6", "video_note"))
        assertEquals(FileCache.KIND_VIDEOS, FileCache.inferKind("doc:4:thumb"))
        assertEquals(FileCache.KIND_GIFS, FileCache.inferKind("doc:5", "gif"))
        assertEquals(FileCache.KIND_GIFS, FileCache.inferKind("gif:12"))
        assertEquals(FileCache.KIND_GIFS, FileCache.inferKind("gif:12:thumb"))
        assertEquals(FileCache.KIND_STICKERS, FileCache.inferKind("emoji:9"))
        assertEquals(FileCache.KIND_STICKERS, FileCache.inferKind("doc:9", "sticker"))
        assertEquals(FileCache.KIND_FILES, FileCache.inferKind("doc:3", "document"))
        assertEquals(FileCache.KIND_FILES, FileCache.inferKind("doc:7", "audio"))
        assertEquals(FileCache.KIND_FILES, FileCache.inferKind("doc:8", "voice"))
        assertEquals(FileCache.KIND_OTHER, FileCache.inferKind("avatar:1"))
    }
}
