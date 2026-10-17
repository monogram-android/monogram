package org.monogram.network.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class FileCacheAccountingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun filesystemBytes(root: File): Long =
        root.listFiles().orEmpty()
            .filter { it.isFile && it.name != FileCache.INDEX_NAME && !it.name.endsWith(".part") }
            .sumOf { it.length() }

    @Test
    fun bucketsAreMutuallyExclusiveAndMatchTheFilesystem() {
        val root = tmp.newFolder("buckets")
        val cache = FileCache(root, maxBytes = 1024)
        cache.put("a", ByteArray(10))
        cache.put("b", ByteArray(20))
        cache.put("c", ByteArray(30))
        cache.setProtectedKeys("viewport", listOf("a"))
        val lease = checkNotNull(cache.acquire("b"))
        cache.notePartial("p", 7)
        val reservation = cache.reserve("q", 50)

        val snapshot = cache.accounting()
        assertEquals(30L, snapshot.completedBytes)
        assertEquals(30L, snapshot.protectedBytes)
        assertEquals(7L, snapshot.partialBytes)
        assertEquals(50L, snapshot.reservedBytes)
        assertEquals(60L + 7L + 50L, snapshot.totalBytes)
        assertEquals(60L, filesystemBytes(root))

        lease.close()
        reservation.close()
        cache.setProtectedKeys("viewport", emptyList())
        cache.notePartial("p", 0)
        val after = cache.accounting()
        assertEquals(StorageAccounting(60, 0, 0, 0), after)
    }

    @Test
    fun reservationIsReplacedByPartialBytesAsTheyAreWritten() {
        val cache = FileCache(tmp.newFolder("reserve"), maxBytes = 1024)
        val reservation = cache.reserve("x", 100)
        cache.notePartial("x", 40)
        val snapshot = cache.accounting()
        assertEquals(40L, snapshot.partialBytes)
        assertEquals(60L, snapshot.reservedBytes)
        reservation.close()
        assertEquals(40L, cache.accounting().partialBytes)
    }

    @Test
    fun optionalReservationIsRefusedWhenInFlightWorkLeavesNoRoom() {
        val cache = FileCache(tmp.newFolder("room"), maxBytes = 100)
        val first = cache.reserve("a", 80)
        val failure =
            assertThrows(FileCache.AdmissionException::class.java) { cache.reserve("b", 40) }
        assertEquals(FileCache.AdmissionFailure.Capacity, failure.reason)
        cache.reserve("b", 40, optional = false).close()
        first.close()
        cache.reserve("b", 40).close()
    }

    @Test
    fun inFlightBytesForceEvictionOfCompletedFilesOnly() {
        val cache = FileCache(tmp.newFolder("evict"), maxBytes = 100)
        cache.put("old", ByteArray(40))
        cache.setProtectedKeys("v", listOf("held"))
        cache.put("held", ByteArray(30))
        cache.notePartial("p", 60)
        cache.trimIfNeeded()
        assertNull(cache.get("old"))
        assertNotNull(cache.get("held"))
    }

    private fun part(cache: FileCache, root: File, key: String, size: Int, modified: Long) =
        File(root, cache.fileFor(key).name + ".part").apply {
            writeBytes(ByteArray(size))
            setLastModified(modified)
        }

    @Test
    fun partialsExpireAfterADayUnlessOwnedOrRecent() {
        var now = 1_000_000_000L
        val root = tmp.newFolder("expiry")
        val cache = FileCache(root, nowMs = { now })
        val idle = part(cache, root, "idle", 9, now)
        val owned = part(cache, root, "owned", 5, now)
        val recent = part(cache, root, "recent", 3, now + FileCache.PARTIAL_IDLE_MS)
        val ownerReservation = cache.reserve("owned", 5)

        now += FileCache.PARTIAL_IDLE_MS - 1
        assertEquals(0L, cache.expireInactivePartials())
        now += 2 + FileCache.PARTIAL_IDLE_MS / 2
        assertEquals(9L, cache.expireInactivePartials())
        assertFalse(idle.exists())
        assertTrue(owned.exists())
        assertTrue(recent.exists())

        ownerReservation.close()
        now += FileCache.PARTIAL_IDLE_MS
        assertEquals(8L, cache.expireInactivePartials())
        assertFalse(owned.exists())
    }

    @Test
    fun reconcileRebuildsPartialCountersAndRecoversUnindexedFiles() {
        val root = tmp.newFolder("reconcile")
        val first = FileCache(root, maxBytes = 1024)
        first.put("a", ByteArray(10))
        first.put("b", ByteArray(5))
        File(root, "leftover").writeBytes(ByteArray(7))
        part(first, root, "x", 11, System.currentTimeMillis())
        File(root, first.fileFor("b").name).delete()
        // The new instance has no index (never flushed) and a stale view of nothing.
        val restarted = FileCache(root, maxBytes = 1024)
        assertEquals(0L, restarted.reconcile())
        val snapshot = restarted.accounting()
        assertEquals(10L + 7L, snapshot.completedBytes)
        assertEquals(11L, snapshot.partialBytes)
        assertEquals(filesystemBytes(root), snapshot.completedBytes + snapshot.protectedBytes)
    }

    @Test
    fun interruptedIndexWriteKeepsFilesUsableAndIsRecovered() {
        val root = tmp.newFolder("index-fail")
        val cache = FileCache(root, maxBytes = 1024)
        // A directory where the index belongs makes every index replacement fail.
        File(root, FileCache.INDEX_NAME).mkdirs()
        File(File(root, FileCache.INDEX_NAME), "blocker").writeBytes(byteArrayOf(1))
        cache.put("a", ByteArray(4))
        cache.flushIndex()
        assertNotNull(cache.get("a"))
        File(File(root, FileCache.INDEX_NAME), "blocker").delete()
        File(root, FileCache.INDEX_NAME).delete()
        cache.flushIndex()
        val restored = FileCache(root, maxBytes = 1024)
        assertEquals(4L, restored.accounting().completedBytes)
        assertNotNull(restored.get("a"))
    }

    @Test
    fun indexWritesAreBatchedAndFlushedOnDemand() {
        val root = tmp.newFolder("batched")
        val cache = FileCache(root, maxBytes = 1024 * 1024)
        cache.put("one", ByteArray(1))
        assertFalse(
            "a single publication must not rewrite the index",
            File(root, FileCache.INDEX_NAME).exists()
        )
        cache.flushIndex()
        assertTrue(File(root, FileCache.INDEX_NAME).exists())
        repeat(40) { cache.put("k$it", ByteArray(1)) }
        val lines = File(root, FileCache.INDEX_NAME).readLines().size
        assertTrue("batch threshold must have flushed, lines=$lines", lines > 1)
    }

    @Test
    fun pinnedDownloadsSurviveTrimAndClearButNotExplicitRemoval() {
        val cache = FileCache(tmp.newFolder("pinned"), maxBytes = 10)
        cache.put("doc:1", ByteArray(8))
        cache.pin("doc:1")
        cache.put("photo:2", ByteArray(2))
        cache.trimIfNeeded()
        assertEquals(8L, cache.accounting().protectedBytes)
        assertEquals(2L, cache.clear())
        assertNotNull(cache.get("doc:1"))
        assertEquals(0L, cache.clearKind(FileCache.KIND_FILES))
        assertTrue(cache.remove("doc:1"))
        assertNull(cache.get("doc:1"))
    }

    @Test
    fun clearReportsOnlyBytesActuallyFreed() {
        val cache = FileCache(tmp.newFolder("freed"))
        cache.put("a", ByteArray(10))
        cache.put("b", ByteArray(20))
        val lease = checkNotNull(cache.acquire("b"))
        assertEquals(10L, cache.clear())
        assertTrue(lease.file.exists())
        lease.close()
        assertFalse(lease.file.exists())
    }

    @Test
    fun orphanAliasBytesAreCountedOnceAndProtectedByPhysicalFile() {
        val root = tmp.newFolder("orphans")
        File(root, "leftover").writeBytes(ByteArray(6))
        val cache = FileCache(root)
        val key = FileCache.ORPHAN_PREFIX + "leftover"
        val lease = checkNotNull(cache.acquire(key))
        val snapshot = cache.accounting()
        assertEquals(6L, snapshot.protectedBytes)
        assertEquals(0L, snapshot.completedBytes)
        lease.close()
        assertEquals(6L, cache.accounting().completedBytes)
    }

    @Test
    fun concurrentPublishReadTrimAndClearKeepAccountingConsistent() {
        val root = tmp.newFolder("concurrent")
        val cache = FileCache(root, maxBytes = 200)
        val pool = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(6) { worker ->
            pool.execute {
                start.await()
                try {
                    repeat(150) { i ->
                        val key = "k${(worker * 7 + i) % 25}"
                        when ((worker + i) % 4) {
                            0 -> runCatching { cache.put(key, ByteArray(10 + i % 5)) }
                            1 -> cache.acquire(key)?.use { assertTrue(it.file.isFile) }
                            2 -> cache.trimIfNeeded()
                            else -> if (i % 40 == 0) cache.clear() else cache.get(key)
                        }
                    }
                } catch (error: Throwable) {
                    failures += error
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("failures=$failures", failures.isEmpty())
        cache.trimIfNeeded()
        val snapshot = cache.accounting()
        assertEquals(0L, snapshot.protectedBytes)
        assertEquals(0L, cache.reconcile())
        assertTrue(snapshot.completedBytes <= 200L)
    }

    @Test
    fun fileLeaseProtectsTheResolvedPathUntilTheConsumerFinishes() {
        val root = tmp.newFolder("file-lease")
        val cache = FileCache(root)
        val file = cache.put("photo:1", ByteArray(5))
        val lease = checkNotNull(cache.acquireFile(file))
        assertEquals(0L, cache.clear())
        assertTrue(file.exists())
        assertNull(
            "files outside the cache cannot be leased",
            cache.acquireFile(tmp.newFile("elsewhere"))
        )
        lease.close()
        assertFalse(file.exists())
    }
}
