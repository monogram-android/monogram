package org.monogram.data.mtproto

import org.monogram.data.db.dao.MtProtoFileHandleDao
import org.monogram.data.db.model.MtProtoFileHandleEntity

internal data class MtProtoFileHandle(
    val fileId: Int,
    val documentId: Long,
)

internal interface MtProtoFileHandleStore {
    suspend fun getOrCreate(scope: MtProtoAuthKeyScope, documentId: Long): MtProtoFileHandle
    suspend fun get(scope: MtProtoAuthKeyScope, fileId: Int): MtProtoFileHandle?
    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment)
}

internal object NoOpMtProtoFileHandleStore : MtProtoFileHandleStore {
    override suspend fun getOrCreate(scope: MtProtoAuthKeyScope, documentId: Long): MtProtoFileHandle =
        error("MTProto file handles are unavailable")

    override suspend fun get(scope: MtProtoAuthKeyScope, fileId: Int): MtProtoFileHandle? = null
    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
}

internal class MtProtoRoomFileHandleStore(
    private val dao: MtProtoFileHandleDao,
) : MtProtoFileHandleStore {
    override suspend fun getOrCreate(scope: MtProtoAuthKeyScope, documentId: Long): MtProtoFileHandle {
        dao.getByDocument(
            accountSlot = scope.accountSlot,
            environment = scope.environment.storageName,
            sessionDcId = scope.dcId,
            documentId = documentId,
        )?.let { return it.toHandle() }

        dao.insert(
            MtProtoFileHandleEntity(
                accountSlot = scope.accountSlot,
                environment = scope.environment.storageName,
                sessionDcId = scope.dcId,
                documentId = documentId,
            )
        )
        return requireNotNull(
            dao.getByDocument(
                accountSlot = scope.accountSlot,
                environment = scope.environment.storageName,
                sessionDcId = scope.dcId,
                documentId = documentId,
            )
        ) { "Failed to persist MTProto file handle" }.toHandle()
    }

    override suspend fun get(scope: MtProtoAuthKeyScope, fileId: Int): MtProtoFileHandle? =
        dao.get(
            fileId = fileId,
            accountSlot = scope.accountSlot,
            environment = scope.environment.storageName,
            sessionDcId = scope.dcId,
        )?.toHandle()

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) =
        dao.deleteAccount(accountSlot, environment.storageName)

    private fun MtProtoFileHandleEntity.toHandle() = MtProtoFileHandle(
        fileId = fileId,
        documentId = documentId,
    )
}
