package org.monogram.data.mtproto

import org.monogram.data.db.dao.MtProtoDocumentLocationDao
import org.monogram.data.db.model.MtProtoDocumentLocationEntity
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeFilename
import org.monogram.mtproto.tl.generated.cloud.layer223.Document_be725c3b31

internal data class MtProtoDocumentLocation(
    val documentId: Long,
    val accessHash: Long,
    val fileReference: ByteArray,
    val documentDcId: Int,
    val mimeType: String,
    val size: Long,
    val fileName: String,
)

internal interface MtProtoDocumentLocationStore {
    suspend fun upsert(scope: MtProtoAuthKeyScope, document: Document_be725c3b31)
    suspend fun get(scope: MtProtoAuthKeyScope, documentId: Long): MtProtoDocumentLocation?
    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment)
}

internal object NoOpMtProtoDocumentLocationStore : MtProtoDocumentLocationStore {
    override suspend fun upsert(scope: MtProtoAuthKeyScope, document: Document_be725c3b31) = Unit
    override suspend fun get(scope: MtProtoAuthKeyScope, documentId: Long): MtProtoDocumentLocation? = null
    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
}

internal class MtProtoRoomDocumentLocationStore(
    private val dao: MtProtoDocumentLocationDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MtProtoDocumentLocationStore {
    override suspend fun upsert(scope: MtProtoAuthKeyScope, document: Document_be725c3b31) {
        dao.upsert(
            MtProtoDocumentLocationEntity(
                accountSlot = scope.accountSlot,
                environment = scope.environment.storageName,
                sessionDcId = scope.dcId,
                documentId = document.id,
                accessHash = document.accessHash,
                fileReference = document.fileReference.toByteArray(),
                documentDcId = document.dcId,
                mimeType = document.mimeType,
                size = document.size,
                fileName = document.attributes.filterIsInstance<DocumentAttributeFilename>().firstOrNull()?.fileName.orEmpty(),
                updatedAt = nowMillis(),
            )
        )
    }

    override suspend fun get(scope: MtProtoAuthKeyScope, documentId: Long): MtProtoDocumentLocation? =
        dao.get(scope.accountSlot, scope.environment.storageName, scope.dcId, documentId)?.let {
            MtProtoDocumentLocation(
                documentId = it.documentId,
                accessHash = it.accessHash,
                fileReference = it.fileReference,
                documentDcId = it.documentDcId,
                mimeType = it.mimeType,
                size = it.size,
                fileName = it.fileName,
            )
        }

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) =
        dao.deleteAccount(accountSlot, environment.storageName)
}
