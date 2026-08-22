package org.monogram.data.mtproto

import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.data.db.dao.MtProtoFileTransferDao
import org.monogram.data.db.model.MtProtoFileTransferEntity

internal class MtProtoRoomFileTransferSink(
    private val dao: MtProtoFileTransferDao,
    private val accountSlot: String,
    private val environment: String,
    private val dcId: Int,
    private val fileKey: String,
    private val path: String,
    private val expectedSize: Long,
) : MtProtoFileTransferSink {
    override suspend fun committedOffset(): Long = dao.get(accountSlot, environment, dcId, fileKey)?.committedOffset ?: 0L

    override suspend fun write(offset: Long, bytes: ByteArray) {
        require(offset >= 0L) { "MTProto file offset must not be negative" }
        withContext(Dispatchers.IO) {
            RandomAccessFile(path, "rw").use { file ->
                file.seek(offset)
                file.write(bytes)
            }
        }
        dao.upsert(
            MtProtoFileTransferEntity(
                accountSlot = accountSlot,
                environment = environment,
                dcId = dcId,
                fileKey = fileKey,
                path = path,
                expectedSize = expectedSize,
                committedOffset = offset + bytes.size,
                isComplete = false,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun complete(totalBytes: Long) {
        require(totalBytes >= 0L) { "MTProto file size must not be negative" }
        dao.upsert(
            MtProtoFileTransferEntity(
                accountSlot = accountSlot,
                environment = environment,
                dcId = dcId,
                fileKey = fileKey,
                path = path,
                expectedSize = expectedSize,
                committedOffset = totalBytes,
                isComplete = true,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }
}
