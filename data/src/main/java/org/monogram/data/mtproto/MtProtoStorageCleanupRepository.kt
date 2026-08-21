package org.monogram.data.mtproto

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.data.db.dao.MtProtoFileTransferDao
import org.monogram.data.db.model.MtProtoFileTransferEntity
import org.monogram.domain.models.StorageCleanupResultModel

/** Clears only verified completed downloads owned by the selected MTProto account. */
internal fun interface MtProtoStorageCleanupRepository {
    suspend fun clearCompletedDownloads(chatId: Long?): StorageCleanupResultModel
}

internal class MtProtoStorageCleanupRepositoryImpl(
    private val transfers: MtProtoFileTransferDao,
    private val filesDirectory: File,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
    private val environment: MtProtoEnvironment = MtProtoEnvironment.PRODUCTION,
) : MtProtoStorageCleanupRepository {
    override suspend fun clearCompletedDownloads(chatId: Long?): StorageCleanupResultModel = withContext(Dispatchers.IO) {
        require(chatId == null) { "MTProto per-chat storage cleanup is not available" }
        val accountDirectory = File(filesDirectory, "${environment.storageName}/$accountSlot").canonicalFile
        var freedSize = 0L
        var freedFiles = 0
        var succeeded = true

        transfers.getCompleted(accountSlot, environment.storageName).forEach { transfer ->
            when (val result = deleteCompletedTransfer(accountDirectory, transfer)) {
                is DeleteResult.Deleted -> {
                    freedSize += result.size
                    freedFiles++
                    transfers.delete(accountSlot, environment.storageName, transfer.dcId, transfer.fileKey)
                }
                DeleteResult.Missing -> transfers.delete(accountSlot, environment.storageName, transfer.dcId, transfer.fileKey)
                DeleteResult.Failed -> succeeded = false
            }
        }

        StorageCleanupResultModel(freedSize, freedFiles, succeeded)
    }

    private fun deleteCompletedTransfer(accountDirectory: File, transfer: MtProtoFileTransferEntity): DeleteResult {
        val file = File(transfer.path)
        val canonical = runCatching { file.canonicalFile }.getOrElse { return DeleteResult.Failed }
        if (!canonical.toPath().startsWith(accountDirectory.toPath())) return DeleteResult.Failed
        if (!canonical.exists()) return DeleteResult.Missing
        if (!canonical.isFile) return DeleteResult.Failed
        val size = canonical.length()
        return if (canonical.delete()) DeleteResult.Deleted(size) else DeleteResult.Failed
    }

    private sealed interface DeleteResult {
        data class Deleted(val size: Long) : DeleteResult
        data object Missing : DeleteResult
        data object Failed : DeleteResult
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
