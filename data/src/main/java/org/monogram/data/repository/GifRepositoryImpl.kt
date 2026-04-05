package org.monogram.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.monogram.data.datasource.remote.GifRemoteSource
import org.monogram.data.stickers.StickerFileManager
import org.monogram.domain.models.GifModel
import org.monogram.domain.repository.CacheProvider
import org.monogram.domain.repository.GifRepository

class GifRepositoryImpl(
    private val remote: GifRemoteSource,
    private val cacheProvider: CacheProvider,
    private val stickerFileManager: StickerFileManager
) : GifRepository {

    override fun getGifFile(gif: GifModel): Flow<String?> {
        return if (gif.fileId == 0L) {
            flowOf(null)
        } else {
            stickerFileManager.getStickerFile(gif.fileId)
        }
    }

    override suspend fun getSavedGifs(): List<GifModel> {
        val cached = cacheProvider.savedGifs.value
        if (cached.isNotEmpty()) return cached

        val remoteGifs = remote.getSavedGifs()
        cacheProvider.setSavedGifs(remoteGifs)
        return remoteGifs
    }

    override suspend fun addSavedGif(path: String) {
        remote.addSavedGif(path)
        cacheProvider.setSavedGifs(remote.getSavedGifs())
    }

    override suspend fun searchGifs(query: String): List<GifModel> {
        return remote.searchGifs(query)
    }
}