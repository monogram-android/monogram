package org.monogram.data.datasource.remote

import org.drinkless.tdlib.TdApi
import org.monogram.data.core.coRunCatching
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.mapper.toApi
import org.monogram.data.mapper.toDomain
import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.models.StickerType

class TdStickerRemoteSource(
    private val gateway: TelegramGateway
) : StickerRemoteSource {

    override suspend fun getInstalledStickerSets(type: StickerType): List<StickerSetModel> {
        return coRunCatching {
            gateway.execute(TdApi.GetInstalledStickerSets(type.toApi()))
                .sets
                .mapNotNull { getStickerSet(it.id) }
        }.getOrDefault(emptyList())
    }

    override suspend fun getArchivedStickerSets(type: StickerType): List<StickerSetModel> {
        return coRunCatching {
            gateway.execute(TdApi.GetArchivedStickerSets(type.toApi(), 0, 100))
                .sets
                .mapNotNull { getStickerSet(it.id) }
        }.getOrDefault(emptyList())
    }

    override suspend fun getStickerSet(setId: Long): StickerSetModel? {
        return coRunCatching {
            gateway.execute(TdApi.GetStickerSet(setId)).toDomain()
        }.getOrNull()
    }

    override suspend fun getStickerSetByName(name: String): StickerSetModel? {
        return coRunCatching {
            gateway.execute(TdApi.SearchStickerSet(name, false)).toDomain()
        }.getOrNull()
    }

    override suspend fun getRecentStickers(): List<StickerModel> {
        return coRunCatching {
            gateway.execute(TdApi.GetRecentStickers(false))
                .stickers
                .map { it.toDomain() }
        }.getOrDefault(emptyList())
    }

    override suspend fun toggleStickerSetInstalled(setId: Long, isInstalled: Boolean) {
        coRunCatching { gateway.execute(TdApi.ChangeStickerSet(setId, isInstalled, false)) }
    }

    override suspend fun toggleStickerSetArchived(setId: Long, isArchived: Boolean) {
        coRunCatching { gateway.execute(TdApi.ChangeStickerSet(setId, false, isArchived)) }
    }

    override suspend fun reorderStickerSets(type: StickerType, setIds: List<Long>) {
        coRunCatching {
            gateway.execute(TdApi.ReorderInstalledStickerSets(type.toApi(), setIds.toLongArray()))
        }
    }

    override suspend fun searchStickers(query: String): List<StickerModel> {
        return coRunCatching {
            gateway.execute(
                TdApi.SearchStickers(TdApi.StickerTypeRegular(), "", query, emptyArray(), 0, 100)
            ).stickers.map { it.toDomain() }
        }.getOrDefault(emptyList())
    }

    override suspend fun searchStickerSets(query: String): List<StickerSetModel> {
        return coRunCatching {
            gateway.execute(TdApi.SearchStickerSets(TdApi.StickerTypeRegular(), query))
                .sets
                .mapNotNull { getStickerSet(it.id) }
        }.getOrDefault(emptyList())
    }

    override suspend fun clearRecentStickers() {
        coRunCatching { gateway.execute(TdApi.ClearRecentStickers()) }
    }
}