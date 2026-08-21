package org.monogram.data.mtproto

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.data.db.dao.MtProtoFileTransferDao
import org.monogram.data.db.model.MtProtoFileTransferEntity
import org.monogram.domain.models.StorageCleanupResultModel
import org.monogram.domain.models.StorageUsageModel

/** Clears only verified completed downloads owned by the selected MTProto account. */
internal fun interface MtProtoStorageCleanupRepository {
    suspend fun clearCompletedDownloads(chatId: Long?): StorageCleanupResultModel
}

internal fun interface MtProtoStorageUsageRepository {
    suspend fun getDownloadUsage(): StorageUsageModel
}

internal class MtProtoStorageCleanupRepositoryImpl(
    private val transfers: MtProtoFileTransferDao,
    private val filesDirectory: File,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
    private val environment: MtProtoEnvironment = MtProtoEnvironment.PRODUCTION,
) : MtProtoStorageCleanupRepository, MtProtoStorageUsageRepository {
    override suspend fun getDownloadUsage(): StorageUsageModel = withContext(Dispatchers.IO) {
        val accountDirectory = accountDirectory()
        val files = transfers.getAll(accountSlot, environment.storageName)
            .mapNotNull { transfer -> transfer.containedFile(accountDirectory) }
            .filter(File::isFile)
        StorageUsageModel(
            totalSize = files.sumOf(File::length),
            fileCount = files.size,
            chatStats = emptyList(),
        )
    }

    override suspend fun clearCompletedDownloads(chatId: Long?): StorageCleanupResultModel = withContext(Dispatchers.IO) {
        require(chatId == null) { "MTProto per-chat storage cleanup is not available" }
        val accountDirectory = accountDirectory()
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
        val canonical = transfer.containedFile(accountDirectory) ?: return DeleteResult.Failed
        if (!canonical.exists()) return DeleteResult.Missing
        if (!canonical.isFile) return DeleteResult.Failed
        val size = canonical.length()
        return if (canonical.delete()) DeleteResult.Deleted(size) else DeleteResult.Failed
    }

    private fun accountDirectory(): File =
        File(filesDirectory, "${environment.storageName}/$accountSlot").canonicalFile

    private fun MtProtoFileTransferEntity.containedFile(accountDirectory: File): File? {
        val canonical = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf { it.toPath().startsWith(accountDirectory.toPath()) }
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
