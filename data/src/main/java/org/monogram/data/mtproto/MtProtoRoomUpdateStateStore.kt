package org.monogram.data.mtproto

import androidx.room.withTransaction
import org.monogram.data.db.MonogramDatabase
import org.monogram.data.db.dao.MtProtoUpdateStateDao
import org.monogram.data.db.model.MtProtoUpdateStateEntity
import org.monogram.mtproto.updates.MtProtoUpdateCursor

/** Room-backed cursor boundary for the first transactional MTProto update slice. */
internal class MtProtoRoomUpdateStateStore(
    private val database: MonogramDatabase,
    private val dao: MtProtoUpdateStateDao,
) : MtProtoUpdateCursorStore {
    override suspend fun load(scope: MtProtoAuthKeyScope): MtProtoUpdateCursorLoadResult =
        dao.get(scope.accountSlot, scope.environment.storageName, scope.dcId)
            ?.let { MtProtoUpdateCursorLoadResult.Found(it.toCursor()) }
            ?: MtProtoUpdateCursorLoadResult.Missing

    suspend fun loadOrNull(scope: MtProtoAuthKeyScope): MtProtoUpdateCursor? =
        (load(scope) as? MtProtoUpdateCursorLoadResult.Found)?.cursor

    override suspend fun save(scope: MtProtoAuthKeyScope, cursor: MtProtoUpdateCursor) {
        dao.upsert(
            MtProtoUpdateStateEntity(
                accountSlot = scope.accountSlot,
                environment = scope.environment.storageName,
                dcId = scope.dcId,
                pts = cursor.pts,
                qts = cursor.qts,
                date = cursor.date,
                seq = cursor.seq,
            )
        )
    }

    /**
     * Applies mapped entities and advances ordering state atomically. The cursor is written last so
     * a failed or cancelled entity apply can never acknowledge an update that was not committed.
     */
    suspend fun apply(
        scope: MtProtoAuthKeyScope,
        cursor: MtProtoUpdateCursor,
        applyEntities: suspend () -> Unit,
    ) {
        database.withTransaction {
            applyEntities()
            dao.upsert(
                MtProtoUpdateStateEntity(
                    accountSlot = scope.accountSlot,
                    environment = scope.environment.storageName,
                    dcId = scope.dcId,
                    pts = cursor.pts,
                    qts = cursor.qts,
                    date = cursor.date,
                    seq = cursor.seq,
                )
            )
        }
    }

    override suspend fun delete(scope: MtProtoAuthKeyScope) =
        dao.delete(scope.accountSlot, scope.environment.storageName, scope.dcId)

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) =
        dao.deleteAccount(accountSlot, environment.storageName)

    private fun MtProtoUpdateStateEntity.toCursor() = MtProtoUpdateCursor(pts, qts, date, seq)
}
