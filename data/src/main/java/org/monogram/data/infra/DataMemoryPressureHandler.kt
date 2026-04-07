package org.monogram.data.infra

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.monogram.data.BuildConfig
import org.monogram.data.repository.ChatsListRepositoryImpl

class DataMemoryPressureHandler(
    private val chatsListRepository: ChatsListRepositoryImpl,
    private val fileUpdateHandler: FileUpdateHandler
) {
    fun clearDataCaches(reason: String) {
        chatsListRepository.clearMemoryCaches()
        fileUpdateHandler.clearMemoryCaches()
        if (BuildConfig.DEBUG) {
            logSnapshot("after_clear:$reason")
        }
    }

    fun logSnapshot(reason: String) {
        if (!BuildConfig.DEBUG) return
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val maxMb = runtime.maxMemory() / MB
        val chatSnapshot = chatsListRepository.memoryCacheSnapshot()
        val fileSnapshot = fileUpdateHandler.memoryCacheSnapshot()
        Log.d(
            TAG,
            "reason=$reason heap=${usedMb}MB/${maxMb}MB " +
                    "chatModelCache=${chatSnapshot.modelCacheSize} " +
                    "invalidatedModels=${chatSnapshot.invalidatedModelsSize} " +
                    "customEmojiPaths=${fileSnapshot.customEmojiPathsSize} " +
                    "fileToEmoji=${fileSnapshot.fileToEmojiSize}"
        )
    }

    companion object {
        private const val TAG = "DataMemoryPressure"
        private const val MB = 1024L * 1024L
    }
}

class DataMemoryDiagnostics(
    scope: CoroutineScope,
    private val memoryPressureHandler: DataMemoryPressureHandler
) {
    init {
        if (BuildConfig.DEBUG) {
            scope.launch {
                while (isActive) {
                    delay(LOG_INTERVAL_MS)
                    memoryPressureHandler.logSnapshot("periodic")
                }
            }
        }
    }

    companion object {
        private const val LOG_INTERVAL_MS = 60_000L
    }
}
