package org.monogram.data.infra

import android.content.Context
import android.os.Build
import org.drinkless.tdlib.TdApi
import org.monogram.data.BuildConfig
import java.io.File
import java.util.Locale

class TdLibParametersProvider(
    private val context: Context
) {
    fun create(): TdApi.SetTdlibParameters {
        val tdMediaCacheDir = File(context.externalCacheDir ?: context.cacheDir, "tdlib/files")
        val tdDbDir = File(context.filesDir, "td-db")

        return TdApi.SetTdlibParameters().apply {
            databaseDirectory = tdDbDir.absolutePath
            filesDirectory = tdMediaCacheDir.absolutePath
            databaseEncryptionKey = byteArrayOf()
            apiId = BuildConfig.API_ID
            apiHash = BuildConfig.API_HASH
            systemLanguageCode = Locale.getDefault().language
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
            systemVersion = Build.VERSION.RELEASE
            applicationVersion = resolveAppVersion()
            useMessageDatabase = true
            useFileDatabase = true
            useChatInfoDatabase = true
            useSecretChats = true
        }
    }

    private fun resolveAppVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }
}