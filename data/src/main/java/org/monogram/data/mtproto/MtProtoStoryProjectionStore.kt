package org.monogram.data.mtproto

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import org.monogram.data.db.MonogramDatabase
import org.monogram.data.db.dao.MtProtoStoryProjectionDao
import org.monogram.data.db.model.MtProtoStoryActiveListEntity
import org.monogram.data.db.model.MtProtoStoryListCursorEntity
import org.monogram.data.db.model.MtProtoStoryProjectionEntity

internal data class MtProtoStoryKey(
    val peerType: String,
    val peerId: Long,
    val storyId: Int,
)

internal data class MtProtoStoryPayload(
    val key: MtProtoStoryKey,
    val payload: ByteArray,
    val isDeleted: Boolean,
)

internal data class MtProtoStoryActiveListItem(
    val key: MtProtoStoryKey,
    val orderKey: Long,
    val canBeArchived: Boolean,
    val maxReadStoryId: Int,
)

internal data class MtProtoStoryListCursor(
    val state: String,
    val hasMore: Boolean,
    val totalCount: Int,
)

internal interface MtProtoStoryProjectionStore {
    suspend fun upsert(scope: MtProtoAuthKeyScope, story: MtProtoStoryPayload)
    suspend fun get(scope: MtProtoAuthKeyScope, key: MtProtoStoryKey): MtProtoStoryPayload?

    /** Emits whenever story projections change; drives live stories-list republish. */
    fun observeChanges(scope: MtProtoAuthKeyScope): Flow<Unit> = emptyFlow()
    suspend fun replaceActiveList(
        scope: MtProtoAuthKeyScope,
        listType: String,
        stories: List<MtProtoStoryActiveListItem>,
        cursor: MtProtoStoryListCursor,
    )
    suspend fun activeList(scope: MtProtoAuthKeyScope, listType: String): List<MtProtoStoryActiveListItem>
    suspend fun updateMaxReadStoryId(scope: MtProtoAuthKeyScope, peerType: String, peerId: Long, maxReadStoryId: Int)
    suspend fun cursor(scope: MtProtoAuthKeyScope, listType: String): MtProtoStoryListCursor?
    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment)
}

internal object NoOpMtProtoStoryProjectionStore : MtProtoStoryProjectionStore {
    override suspend fun upsert(scope: MtProtoAuthKeyScope, story: MtProtoStoryPayload) = Unit
    override suspend fun get(scope: MtProtoAuthKeyScope, key: MtProtoStoryKey): MtProtoStoryPayload? = null
    override suspend fun replaceActiveList(scope: MtProtoAuthKeyScope, listType: String, stories: List<MtProtoStoryActiveListItem>, cursor: MtProtoStoryListCursor) = Unit
    override suspend fun activeList(scope: MtProtoAuthKeyScope, listType: String): List<MtProtoStoryActiveListItem> = emptyList()
    override suspend fun updateMaxReadStoryId(scope: MtProtoAuthKeyScope, peerType: String, peerId: Long, maxReadStoryId: Int) = Unit
    override suspend fun cursor(scope: MtProtoAuthKeyScope, listType: String): MtProtoStoryListCursor? = null
    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
}

internal class MtProtoRoomStoryProjectionStore(
    private val dao: MtProtoStoryProjectionDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val database: MonogramDatabase? = null,
) : MtProtoStoryProjectionStore {
    override fun observeChanges(scope: MtProtoAuthKeyScope): Flow<Unit> =
        dao.observeChangeToken(scope.accountSlot, scope.environment.storageName, scope.dcId)
            .map { }

    override suspend fun upsert(scope: MtProtoAuthKeyScope, story: MtProtoStoryPayload) {
        dao.upsertStory(story.toEntity(scope, nowMillis()))
    }

    override suspend fun get(scope: MtProtoAuthKeyScope, key: MtProtoStoryKey): MtProtoStoryPayload? =
        dao.getStory(scope.accountSlot, scope.environment.storageName, scope.dcId, key.peerType, key.peerId, key.storyId)
            ?.toPayload()

    override suspend fun replaceActiveList(
        scope: MtProtoAuthKeyScope,
        listType: String,
        stories: List<MtProtoStoryActiveListItem>,
        cursor: MtProtoStoryListCursor,
    ) {
        val updatedAt = nowMillis()
        suspend fun replace() {
            dao.clearActiveList(scope.accountSlot, scope.environment.storageName, scope.dcId, listType)
            if (stories.isNotEmpty()) {
                dao.upsertActiveList(stories.map { it.toEntity(scope, listType, updatedAt) })
            }
            dao.upsertCursor(cursor.toEntity(scope, listType, updatedAt))
        }
        database?.withTransaction { replace() } ?: replace()
    }

    override suspend fun activeList(scope: MtProtoAuthKeyScope, listType: String): List<MtProtoStoryActiveListItem> =
        dao.getActiveList(scope.accountSlot, scope.environment.storageName, scope.dcId, listType).map { it.toItem() }

    override suspend fun updateMaxReadStoryId(scope: MtProtoAuthKeyScope, peerType: String, peerId: Long, maxReadStoryId: Int) {
        dao.updateMaxReadStoryId(
            scope.accountSlot,
            scope.environment.storageName,
            scope.dcId,
            peerType,
            peerId,
            maxReadStoryId,
            nowMillis(),
        )
    }

    override suspend fun cursor(scope: MtProtoAuthKeyScope, listType: String): MtProtoStoryListCursor? =
        dao.getCursor(scope.accountSlot, scope.environment.storageName, scope.dcId, listType)?.toCursor()

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) {
        dao.deleteStoriesForAccount(accountSlot, environment.storageName)
        dao.deleteActiveListsForAccount(accountSlot, environment.storageName)
        dao.deleteCursorsForAccount(accountSlot, environment.storageName)
    }

    private fun MtProtoStoryPayload.toEntity(scope: MtProtoAuthKeyScope, updatedAt: Long) = MtProtoStoryProjectionEntity(
        accountSlot = scope.accountSlot,
        environment = scope.environment.storageName,
        dcId = scope.dcId,
        peerType = key.peerType,
        peerId = key.peerId,
        storyId = key.storyId,
        payload = payload,
        isDeleted = isDeleted,
        updatedAt = updatedAt,
    )

    private fun MtProtoStoryProjectionEntity.toPayload() = MtProtoStoryPayload(
        key = MtProtoStoryKey(peerType, peerId, storyId),
        payload = payload,
        isDeleted = isDeleted,
    )

    private fun MtProtoStoryActiveListItem.toEntity(scope: MtProtoAuthKeyScope, listType: String, updatedAt: Long) = MtProtoStoryActiveListEntity(
        accountSlot = scope.accountSlot,
        environment = scope.environment.storageName,
        dcId = scope.dcId,
        listType = listType,
        peerType = key.peerType,
        peerId = key.peerId,
        storyId = key.storyId,
        orderKey = orderKey,
        canBeArchived = canBeArchived,
        maxReadStoryId = maxReadStoryId,
        updatedAt = updatedAt,
    )

    private fun MtProtoStoryActiveListEntity.toItem() = MtProtoStoryActiveListItem(
        key = MtProtoStoryKey(peerType, peerId, storyId),
        orderKey = orderKey,
        canBeArchived = canBeArchived,
        maxReadStoryId = maxReadStoryId,
    )

    private fun MtProtoStoryListCursor.toEntity(scope: MtProtoAuthKeyScope, listType: String, updatedAt: Long) = MtProtoStoryListCursorEntity(
        accountSlot = scope.accountSlot,
        environment = scope.environment.storageName,
        dcId = scope.dcId,
        listType = listType,
        state = state,
        hasMore = hasMore,
        totalCount = totalCount,
        updatedAt = updatedAt,
    )

    private fun MtProtoStoryListCursorEntity.toCursor() = MtProtoStoryListCursor(state, hasMore, totalCount)
}
