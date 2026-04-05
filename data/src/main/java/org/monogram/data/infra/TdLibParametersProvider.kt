package org.monogram.data.infra

import android.content.Context
import android.os.Build
import org.drinkless.tdlib.TdApi
import org.monogram.data.BuildConfig
import java.io.File
import java.util.*

class TdLibParametersProvider(
    private val context: Context
) {
    fun create(): TdApi.SetTdlibParameters {
        return TdApi.SetTdlibParameters().apply {
            databaseDirectory = File(context.filesDir, "td-db").absolutePath
            filesDirectory = File(context.filesDir, "td-files").absolutePath
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