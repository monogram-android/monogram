package org.monogram.data.mtproto

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.db.dao.MtProtoFileTransferDao
import org.monogram.data.db.model.MtProtoFileTransferEntity

class MtProtoStorageCleanupRepositoryTest {
    @Test
    fun `deletes only completed selected-account files and counts actual bytes`() = runBlocking {
        val root = Files.createTempDirectory("mtproto-files").toFile()
        val complete = File(root, "prod/default/2/complete.bin").apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(ByteArray(4))
        }
        val incomplete = transfer(root, "prod/default/2/incomplete.bin", complete = false)
        val dao = RecordingDao(listOf(transfer(root, "prod/default/2/complete.bin"), transfer(root, "prod/default/4/missing.bin"), incomplete))
        val repository = MtProtoStorageCleanupRepositoryImpl(dao, root)

        val result = repository.clearCompletedDownloads(null)

        assertEquals(4L, result.tdlibFreedSize)
        assertEquals(1, result.tdlibFreedFileCount)
        assertTrue(result.tdlibCleanupSucceeded)
        assertFalse(complete.exists())
        assertEquals(setOf("complete", "missing"), dao.deleted.map(MtProtoFileTransferEntity::fileKey).toSet())
        assertTrue(dao.entries.contains(incomplete))
        root.deleteRecursively()
        Unit
    }

    @Test
    fun `rejects per-chat cleanup before reading transfers`() {
        val dao = RecordingDao(emptyList())
        val repository = MtProtoStorageCleanupRepositoryImpl(dao, Files.createTempDirectory("mtproto-files").toFile())

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.clearCompletedDownloads(7L) }
        }
        assertEquals(0, dao.completedReads)
    }

    @Test
    fun `refuses a completed transfer outside the app-private account directory`() = runBlocking {
        val root = Files.createTempDirectory("mtproto-files").toFile()
        val outside = requireNotNull(File.createTempFile("outside", ".bin")).apply { writeBytes(ByteArray(3)) }
        val dao = RecordingDao(listOf(transferAtPath(outside.absolutePath)))
        val repository = MtProtoStorageCleanupRepositoryImpl(dao, root)

        val result = repository.clearCompletedDownloads(null)

        assertFalse(result.tdlibCleanupSucceeded)
        assertTrue(outside.exists())
        assertTrue(dao.deleted.isEmpty())
        outside.delete()
        root.deleteRecursively()
        Unit
    }

    private fun transfer(root: File, relativePath: String, complete: Boolean = true) = MtProtoFileTransferEntity(
        accountSlot = "default",
        environment = "prod",
        dcId = 2,
        fileKey = File(relativePath).nameWithoutExtension.ifBlank { "missing" },
        path = File(relativePath).takeIf(File::isAbsolute)?.absolutePath ?: File(root, relativePath).absolutePath,
        expectedSize = 4,
        committedOffset = 4,
        isComplete = complete,
        updatedAt = 0,
    )

    private fun transferAtPath(path: String) = MtProtoFileTransferEntity(
        accountSlot = "default",
        environment = "prod",
        dcId = 2,
        fileKey = "outside",
        path = path,
        expectedSize = 3,
        committedOffset = 3,
        isComplete = true,
        updatedAt = 0,
    )

    private class RecordingDao(entries: List<MtProtoFileTransferEntity>) : MtProtoFileTransferDao {
        val entries = entries.toMutableList()
        val deleted = mutableListOf<MtProtoFileTransferEntity>()
        var completedReads = 0

        override suspend fun get(accountSlot: String, environment: String, dcId: Int, fileKey: String) =
            entries.firstOrNull { it.accountSlot == accountSlot && it.environment == environment && it.dcId == dcId && it.fileKey == fileKey }

        override suspend fun upsert(entity: MtProtoFileTransferEntity) = Unit

        override suspend fun getCompleted(accountSlot: String, environment: String): List<MtProtoFileTransferEntity> {
            completedReads++
            return entries.filter { it.accountSlot == accountSlot && it.environment == environment && it.isComplete }
        }

        override suspend fun delete(accountSlot: String, environment: String, dcId: Int, fileKey: String) {
            entries.firstOrNull { it.accountSlot == accountSlot && it.environment == environment && it.dcId == dcId && it.fileKey == fileKey }
                ?.also { deleted += it; entries.remove(it) }
        }

        override suspend fun deleteAccount(accountSlot: String, environment: String) = Unit
    }
}
