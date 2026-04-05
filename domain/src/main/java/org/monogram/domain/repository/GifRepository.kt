package org.monogram.domain.repository

import kotlinx.coroutines.flow.Flow
import org.monogram.domain.models.GifModel

interface GifRepository {
    fun getGifFile(gif: GifModel): Flow<String?>
    suspend fun getSavedGifs(): List<GifModel>
    suspend fun addSavedGif(path: String)
    suspend fun searchGifs(query: String): List<GifModel>
}