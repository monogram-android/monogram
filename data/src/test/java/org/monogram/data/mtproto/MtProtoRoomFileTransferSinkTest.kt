package org.monogram.data.mtproto

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.data.db.dao.MtProtoFileTransferDao
import org.monogram.data.db.model.MtProtoFileTransferEntity

class MtProtoRoomFileTransferSinkTest {
    @Test
    fun `writes bytes before committing durable offset`() {
        runBlocking {
        val path = Files.createTempFile("mtproto-transfer", ".bin").toString()
        val dao = RecordingDao()
        val sink = MtProtoRoomFileTransferSink(dao, "account", "PRODUCTION", 2, "file", path, 4)

        sink.write(2, byteArrayOf(3, 4))

        assertEquals(4L, sink.committedOffset())
        assertEquals(byteArrayOf(0, 0, 3, 4).toList(), Files.readAllBytes(java.nio.file.Path.of(path)).toList())
        assertEquals(4L, dao.entity?.committedOffset)
            Files.deleteIfExists(java.nio.file.Path.of(path))
        }
    }

    private class RecordingDao : MtProtoFileTransferDao {
        var entity: MtProtoFileTransferEntity? = null
        override suspend fun get(accountSlot: String, environment: String, dcId: Int, fileKey: String) =
            entity
        override suspend fun upsert(entity: MtProtoFileTransferEntity) { this.entity = entity }
        override suspend fun deleteAccount(accountSlot: String, environment: String) { entity = null }
    }
}
