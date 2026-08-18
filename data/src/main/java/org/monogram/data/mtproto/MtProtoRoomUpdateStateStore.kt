package org.monogram.data.mtproto

import androidx.room.withTransaction
import org.monogram.data.db.MonogramDatabase
import org.monogram.data.db.dao.MtProtoUpdateStateDao
import org.monogram.data.db.model.MtProtoUpdateStateEntity
import org.monogram.mtproto.updates.MtProtoUpdateCursor
import org.monogram.mtproto.updates.MtProtoUpdateState

internal sealed interface MtProtoUpdateStateLoadResult {
    data object Missing : MtProtoUpdateStateLoadResult
    data object Corrupt : MtProtoUpdateStateLoadResult
    data class Found(val state: MtProtoUpdateState) : MtProtoUpdateStateLoadResult
}

internal interface MtProtoTransactionalUpdateStateStore {
    suspend fun loadState(scope: MtProtoAuthKeyScope): MtProtoUpdateStateLoadResult

    suspend fun applyState(
        scope: MtProtoAuthKeyScope,
        state: MtProtoUpdateState,
        applyEntities: suspend () -> Unit,
    )
}

/** Room-backed cursor boundary for the first transactional MTProto update slice. */
internal class MtProtoRoomUpdateStateStore(
    private val database: MonogramDatabase,
    private val dao: MtProtoUpdateStateDao,
) : MtProtoUpdateCursorStore, MtProtoTransactionalUpdateStateStore {
    override suspend fun load(scope: MtProtoAuthKeyScope): MtProtoUpdateCursorLoadResult =
        dao.get(scope.accountSlot, scope.environment.storageName, scope.dcId)
            ?.let { MtProtoUpdateCursorLoadResult.Found(it.toCursor()) }
            ?: MtProtoUpdateCursorLoadResult.Missing

    suspend fun loadOrNull(scope: MtProtoAuthKeyScope): MtProtoUpdateCursor? =
        (load(scope) as? MtProtoUpdateCursorLoadResult.Found)?.cursor

    override suspend fun loadState(scope: MtProtoAuthKeyScope): MtProtoUpdateStateLoadResult {
        val entity = dao.get(scope.accountSlot, scope.environment.storageName, scope.dcId)
            ?: return MtProtoUpdateStateLoadResult.Missing
        return try {
            MtProtoUpdateStateLoadResult.Found(entity.toState())
        } catch (_: IllegalArgumentException) {
            MtProtoUpdateStateLoadResult.Corrupt
        }
    }

    override suspend fun save(scope: MtProtoAuthKeyScope, cursor: MtProtoUpdateCursor) {
        database.withTransaction {
            val channelPts = dao.get(scope.accountSlot, scope.environment.storageName, scope.dcId)
                ?.channelPtsData
            dao.upsert(cursor.toEntity(scope, channelPts))
        }
    }

    suspend fun saveState(scope: MtProtoAuthKeyScope, state: MtProtoUpdateState) = database.withTransaction {
        dao.upsert(state.cursor.toEntity(scope, MtProtoChannelPtsCodec.encode(state.channelPts)))
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
            val channelPts = dao.get(scope.accountSlot, scope.environment.storageName, scope.dcId)
                ?.channelPtsData
            dao.upsert(cursor.toEntity(scope, channelPts))
        }
    }

    override suspend fun applyState(
        scope: MtProtoAuthKeyScope,
        state: MtProtoUpdateState,
        applyEntities: suspend () -> Unit,
    ) {
        database.withTransaction {
            applyEntities()
            dao.upsert(state.cursor.toEntity(scope, MtProtoChannelPtsCodec.encode(state.channelPts)))
        }
    }

    override suspend fun delete(scope: MtProtoAuthKeyScope) =
        dao.delete(scope.accountSlot, scope.environment.storageName, scope.dcId)

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) =
        dao.deleteAccount(accountSlot, environment.storageName)

    private fun MtProtoUpdateStateEntity.toCursor() = MtProtoUpdateCursor(pts, qts, date, seq)

    private fun MtProtoUpdateStateEntity.toState() = MtProtoUpdateState(
        cursor = toCursor(),
        channelPts = MtProtoChannelPtsCodec.decode(channelPtsData),
    )

    private fun MtProtoUpdateCursor.toEntity(
        scope: MtProtoAuthKeyScope,
        channelPtsData: String?,
    ) = MtProtoUpdateStateEntity(
        accountSlot = scope.accountSlot,
        environment = scope.environment.storageName,
        dcId = scope.dcId,
        pts = pts,
        qts = qts,
        date = date,
        seq = seq,
        channelPtsData = channelPtsData,
    )
}
