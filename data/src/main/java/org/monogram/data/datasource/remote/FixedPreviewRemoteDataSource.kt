package org.monogram.data.datasource.remote

interface FixedPreviewRemoteDataSource {
    suspend fun getTwitterStatus(statusId: String): FxEmbedRemoteDataSource.FxEmbedStatusResponse?
    suspend fun getBlueskyStatus(
        handle: String,
        rkey: String
    ): FxEmbedRemoteDataSource.FxEmbedStatusResponse?
}
