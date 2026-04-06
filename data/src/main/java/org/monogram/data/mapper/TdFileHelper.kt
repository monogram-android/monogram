package org.monogram.data.mapper

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.drinkless.tdlib.TdApi
import org.monogram.data.chats.ChatCache
import org.monogram.data.datasource.remote.MessageFileApi
import org.monogram.data.datasource.remote.TdMessageRemoteDataSource
import org.monogram.domain.repository.AppPreferencesProvider

class TdFileHelper(
    private val connectivityManager: ConnectivityManager,
    private val fileApi: MessageFileApi,
    private val appPreferences: AppPreferencesProvider,
    private val cache: ChatCache
) {
    fun isValidPath(path: String?): Boolean {
        return isValidFilePath(path)
    }

    fun getUpdatedFile(file: TdApi.File): TdApi.File {
        return cache.fileCache[file.id] ?: file
    }

    fun resolveLocalFilePath(file: TdApi.File?): String? {
        if (file == null) return null
        val directPath = file.local.path.takeIf { isValidPath(it) }
        if (directPath != null) return directPath
        return cache.fileCache[file.id]?.local?.path?.takeIf { isValidPath(it) }
    }

    fun findBestAvailablePath(mainFile: TdApi.File?, sizes: Array<TdApi.PhotoSize>? = null): String? {
        if (mainFile != null && isValidPath(mainFile.local.path)) {
            return mainFile.local.path
        }

        if (sizes != null) {
            return sizes.sortedByDescending { it.width }
                .map { getUpdatedFile(it.photo) }
                .firstOrNull { isValidPath(it.local.path) }
                ?.local?.path
        }

        return null
    }

    fun resolveCachedPath(fileId: Int, storedPath: String?): String? {
        val fromStored = storedPath
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { isValidPath(it) }
        if (fromStored != null) return fromStored

        return fileId.takeIf { it != 0 }
            ?.let { cache.fileCache[it]?.local?.path }
            ?.takeIf { isValidPath(it) }
    }

    fun registerCachedFile(fileId: Int, chatId: Long, messageId: Long) {
        if (fileId != 0) {
            fileApi.registerFileForMessage(fileId, chatId, messageId)
        }
    }

    fun enqueueDownload(
        fileId: Int,
        priority: Int,
        downloadType: TdMessageRemoteDataSource.DownloadType,
        offset: Int = 0,
        limit: Int = 0,
        synchronous: Boolean = false
    ) {
        fileApi.enqueueDownload(fileId, priority, downloadType, offset.toLong(), limit.toLong(), synchronous)
    }

    fun isFileQueued(fileId: Int): Boolean = fileApi.isFileQueued(fileId)

    fun computeDownloadProgress(file: TdApi.File): Float {
        return if (file.size > 0) {
            file.local.downloadedSize.toFloat() / file.size.toFloat()
        } else {
            0f
        }
    }

    fun computeUploadProgress(file: TdApi.File): Float {
        return if (file.size > 0) {
            file.remote.uploadedSize.toFloat() / file.size.toFloat()
        } else {
            0f
        }
    }

    fun isNetworkAutoDownloadEnabled(): Boolean {
        return when (getCurrentNetworkType()) {
            is TdApi.NetworkTypeWiFi -> appPreferences.autoDownloadWifi.value
            is TdApi.NetworkTypeMobile -> appPreferences.autoDownloadMobile.value
            is TdApi.NetworkTypeMobileRoaming -> appPreferences.autoDownloadRoaming.value
            else -> appPreferences.autoDownloadWifi.value
        }
    }

    private fun getCurrentNetworkType(): TdApi.NetworkType {
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

        return when {
            capabilities == null -> TdApi.NetworkTypeNone()
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TdApi.NetworkTypeWiFi()
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                if (connectivityManager.isDefaultNetworkActive && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) {
                    TdApi.NetworkTypeMobileRoaming()
                } else {
                    TdApi.NetworkTypeMobile()
                }
            }

            else -> TdApi.NetworkTypeNone()
        }
    }
}