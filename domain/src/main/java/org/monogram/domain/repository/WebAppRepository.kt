package org.monogram.domain.repository

import org.monogram.domain.models.webapp.ThemeParams
import org.monogram.domain.models.webapp.WebAppInfoModel

interface WebAppRepository {
    suspend fun openWebApp(
        chatId: Long,
        botUserId: Long,
        url: String,
        themeParams: ThemeParams? = null
    ): WebAppInfoModel?

    suspend fun closeWebApp(launchId: Long)

    suspend fun sendWebAppResult(launchId: Long, queryId: String)

    suspend fun saveCloudStorageValue(botUserId: Long, key: String, value: String): Boolean

    suspend fun getCloudStorageValue(botUserId: Long, key: String): String?

    suspend fun getCloudStorageValues(botUserId: Long, keys: List<String>): Map<String, String?>

    suspend fun deleteCloudStorageValue(botUserId: Long, key: String): Boolean

    suspend fun deleteCloudStorageValues(botUserId: Long, keys: List<String>): Boolean

    suspend fun getCloudStorageKeys(botUserId: Long): List<String>
}
