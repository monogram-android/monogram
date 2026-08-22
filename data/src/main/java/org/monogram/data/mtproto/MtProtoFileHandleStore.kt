package org.monogram.data.mtproto

import org.monogram.data.db.dao.MtProtoFileHandleDao
import org.monogram.data.db.model.MtProtoFileHandleEntity

internal enum class MtProtoFileResourceType {
    DOCUMENT,
    PHOTO,
}

internal data class MtProtoFileResourceKey(
    val type: MtProtoFileResourceType,
    val id: Long,
    val variant: String = "",
) {
    init {
        require(id > 0L) { "MTProto file resource ID must be positive" }
        require(variant.isNotBlank() || type == MtProtoFileResourceType.DOCUMENT) {
            "MTProto photo handles require a thumb-size variant"
        }
    }
}

internal data class MtProtoFileHandle(
    val fileId: Int,
    val resource: MtProtoFileResourceKey,
)

internal interface MtProtoFileHandleStore {
    suspend fun getOrCreate(scope: MtProtoAuthKeyScope, resource: MtProtoFileResourceKey): MtProtoFileHandle
    suspend fun get(scope: MtProtoAuthKeyScope, fileId: Int): MtProtoFileHandle?
    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment)
}

internal object NoOpMtProtoFileHandleStore : MtProtoFileHandleStore {
    override suspend fun getOrCreate(scope: MtProtoAuthKeyScope, resource: MtProtoFileResourceKey): MtProtoFileHandle =
        error("MTProto file handles are unavailable")

    override suspend fun get(scope: MtProtoAuthKeyScope, fileId: Int): MtProtoFileHandle? = null
    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
}

internal class MtProtoRoomFileHandleStore(
    private val dao: MtProtoFileHandleDao,
) : MtProtoFileHandleStore {
    override suspend fun getOrCreate(scope: MtProtoAuthKeyScope, resource: MtProtoFileResourceKey): MtProtoFileHandle {
        dao.getByResource(
            accountSlot = scope.accountSlot,
            environment = scope.environment.storageName,
            sessionDcId = scope.dcId,
            resourceType = resource.type.name,
            resourceId = resource.id,
            resourceVariant = resource.variant,
        )?.let { return it.toHandle() }

        dao.insert(
            MtProtoFileHandleEntity(
                accountSlot = scope.accountSlot,
                environment = scope.environment.storageName,
                sessionDcId = scope.dcId,
                resourceType = resource.type.name,
                resourceId = resource.id,
                resourceVariant = resource.variant,
            )
        )
        return requireNotNull(
            dao.getByResource(
                accountSlot = scope.accountSlot,
                environment = scope.environment.storageName,
                sessionDcId = scope.dcId,
                resourceType = resource.type.name,
                resourceId = resource.id,
                resourceVariant = resource.variant,
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
        resource = MtProtoFileResourceKey(
            type = MtProtoFileResourceType.valueOf(resourceType),
            id = resourceId,
            variant = resourceVariant,
        ),
    )
}
