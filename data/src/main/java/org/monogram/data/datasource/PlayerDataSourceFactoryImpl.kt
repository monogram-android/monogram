package org.monogram.data.datasource

import org.monogram.domain.repository.PlayerDataSourceFactory

class PlayerDataSourceFactoryImpl(
    private val fileDataSource: FileDataSource
) : PlayerDataSourceFactory {

    override fun createPayload(fileId: Int): Any {
        return TelegramStreamingDataSource.Factory(fileDataSource, fileId)
    }
}