package org.monogram.data.mtproto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.data.db.dao.MtProtoFileTransferDao
import org.monogram.data.db.model.MtProtoFileTransferEntity

/** Room-backed upload progress over the shared durable transfer table. */
internal class MtProtoRoomUploadProgressStore(
    private val dao: MtProtoFileTransferDao,
    private val accountSlot: String,
    private val dcIdProvider: suspend () -> Int,
    private val environment: String = MtProtoEnvironment.PRODUCTION.storageName,
) : MtProtoUploadProgressStore {
    private suspend fun dcId(): Int = dcIdProvider()

    override suspend fun committedBytes(fileKey: String): Long {
        val entity = dao.get(accountSlot, environment, dcId(), fileKey) ?: return 0L
        return if (entity.isComplete) 0L else entity.committedOffset
    }

    override suspend fun advance(fileKey: String, path: String, expectedSize: Long, committedBytes: Long) =
        withContext(Dispatchers.IO) {
            dao.upsert(
                MtProtoFileTransferEntity(
                    accountSlot = accountSlot,
                    environment = environment,
                    dcId = dcId(),
                    fileKey = fileKey,
                    path = path,
                    expectedSize = expectedSize,
                    committedOffset = committedBytes,
                    isComplete = false,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            Unit
        }

    override suspend fun clear(fileKey: String) {
        dao.delete(accountSlot, environment, dcId(), fileKey)
    }
}
