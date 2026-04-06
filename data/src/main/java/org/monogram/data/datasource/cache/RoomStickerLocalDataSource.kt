package org.monogram.data.datasource.cache

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.monogram.data.db.dao.RecentEmojiDao
import org.monogram.data.db.dao.StickerPathDao
import org.monogram.data.db.dao.StickerSetDao
import org.monogram.data.db.model.RecentEmojiEntity
import org.monogram.data.db.model.StickerPathEntity
import org.monogram.data.db.model.StickerSetEntity
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.models.StickerSetModel

class RoomStickerLocalDataSource(
    private val stickerSetDao: StickerSetDao,
    private val recentEmojiDao: RecentEmojiDao,
    private val stickerPathDao: StickerPathDao
) : StickerLocalDataSource {

    override fun getInstalledStickerSetsByType(type: String): Flow<List<StickerSetModel>> {
        return stickerSetDao.getInstalledStickerSetsByType(type)
            .map { entities -> entities.mapNotNull { it.toModel() } }
    }

    override fun getArchivedStickerSetsByType(type: String): Flow<List<StickerSetModel>> {
        return stickerSetDao.getArchivedStickerSetsByType(type)
            .map { entities -> entities.mapNotNull { it.toModel() } }
    }

    override suspend fun getStickerSetById(id: Long): StickerSetModel? {
        return stickerSetDao.getStickerSetById(id)?.toModel()
    }

    override suspend fun getStickerSetByName(name: String): StickerSetModel? {
        return stickerSetDao.getStickerSetByName(name)?.toModel()
    }

    override suspend fun saveStickerSets(
        sets: List<StickerSetModel>,
        type: String,
        isInstalled: Boolean,
        isArchived: Boolean
    ) {
        stickerSetDao.deleteStickerSets(type, isInstalled, isArchived)
        val normalized = sets.map { it.copy(isInstalled = isInstalled, isArchived = isArchived) }
        stickerSetDao.insertStickerSets(normalized.map { it.toEntity(type) })
    }

    override suspend fun insertStickerSet(set: StickerSetModel, type: String) {
        stickerSetDao.insertStickerSet(set.toEntity(type))
    }

    override suspend fun clearStickerSets() {
        stickerSetDao.clearAll()
    }

    override suspend fun getPath(fileId: Long): String? {
        return stickerPathDao.getPath(fileId)
    }

    override suspend fun insertPath(fileId: Long, path: String) {
        stickerPathDao.insertPath(StickerPathEntity(fileId, path))
    }

    override suspend fun deletePath(fileId: Long) {
        stickerPathDao.deletePath(fileId)
    }

    override suspend fun clearPaths() {
        stickerPathDao.clearAll()
    }

    override fun getRecentEmojis(): Flow<List<RecentEmojiModel>> {
        return recentEmojiDao.getRecentEmojis()
            .map { entities -> entities.mapNotNull { it.toModel() } }
    }

    override suspend fun addRecentEmoji(recentEmoji: RecentEmojiModel) {
        recentEmojiDao.deleteRecentEmoji(recentEmoji.emoji, recentEmoji.sticker?.id)
        recentEmojiDao.insertRecentEmoji(
            RecentEmojiEntity(
                emoji = recentEmoji.emoji,
                stickerId = recentEmoji.sticker?.id,
                data = Json.encodeToString(recentEmoji)
            )
        )
    }

    override suspend fun clearRecentEmojis() {
        recentEmojiDao.clearAll()
    }

    private fun StickerSetModel.toEntity(type: String): StickerSetEntity {
        return StickerSetEntity(
            id = id,
            name = name,
            type = type,
            isInstalled = isInstalled,
            isArchived = isArchived,
            data = Json.encodeToString(this)
        )
    }

    private fun StickerSetEntity.toModel(): StickerSetModel? {
        return try {
            Json.decodeFromString<StickerSetModel>(data)
        } catch (_: Exception) {
            null
        }
    }

    private fun RecentEmojiEntity.toModel(): RecentEmojiModel? {
        return try {
            Json.decodeFromString<RecentEmojiModel>(data)
        } catch (_: Exception) {
            null
        }
    }
}