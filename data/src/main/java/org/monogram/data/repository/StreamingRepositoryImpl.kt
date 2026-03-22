package org.monogram.data.repository

import androidx.media3.datasource.DataSource
import org.monogram.core.ScopeProvider
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.monogram.data.datasource.FileDataSource
import kotlinx.coroutines.launch
import org.monogram.data.datasource.TelegramStreamingDataSource
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.domain.repository.PlayerDataSourceFactory
import org.monogram.domain.repository.StreamingRepository

class StreamingRepositoryImpl(
    private val fileDataSource: FileDataSource,
    private val updates: UpdateDispatcher,
    scopeProvider: ScopeProvider
) : StreamingRepository, PlayerDataSourceFactory {

    private val scope = scopeProvider.appScope

    private val _fileProgressFlow = MutableSharedFlow<Pair<Int, Float>>(
        replay = 1,
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        scope.launch {
            updates.file.collect { update ->
                val file = update.file
                if (file.size > 0) {
                    val progress = file.local.downloadedSize.toFloat() / file.size.toFloat()
                    _fileProgressFlow.emit(file.id to progress)
                }
            }
        }
    }

    override fun createPayload(fileId: Int): DataSource.Factory {
        return TelegramStreamingDataSource.Factory(fileDataSource, fileId)
    }

    override fun getDownloadProgress(fileId: Int): Flow<Float> {
        return _fileProgressFlow
            .filter { it.first == fileId }
            .map { it.second }
    }
}