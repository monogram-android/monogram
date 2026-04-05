package org.monogram.data.datasource.remote

import org.monogram.domain.models.GifModel

interface GifRemoteSource {
    suspend fun getSavedGifs(): List<GifModel>
    suspend fun addSavedGif(path: String)
    suspend fun searchGifs(query: String): List<GifModel>
}