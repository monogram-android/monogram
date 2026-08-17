package org.monogram.data.infra

import android.content.Context
import org.drinkless.tdlib.TdApi
import org.monogram.data.BuildConfig
import java.io.File

class TdLibParametersProvider(
    private val context: Context,
    private val metadataProvider: TelegramClientMetadataProvider = TelegramClientMetadataProvider(context),
) {
    fun create(): TdApi.SetTdlibParameters {
        val metadata = metadataProvider.create()
        val tdMediaCacheDir = File(context.externalCacheDir ?: context.cacheDir, "tdlib/files")
        val tdDbDir = File(context.filesDir, "td-db")

        return TdApi.SetTdlibParameters().apply {
            databaseDirectory = tdDbDir.absolutePath
            filesDirectory = tdMediaCacheDir.absolutePath
            databaseEncryptionKey = byteArrayOf()
            apiId = BuildConfig.API_ID
            apiHash = BuildConfig.API_HASH
            systemLanguageCode = metadata.systemLanguageCode
            deviceModel = metadata.deviceModel
            systemVersion = metadata.systemVersion
            applicationVersion = metadata.applicationVersion
            useMessageDatabase = true
            useFileDatabase = true
            useChatInfoDatabase = true
            useSecretChats = true
        }
    }

}
