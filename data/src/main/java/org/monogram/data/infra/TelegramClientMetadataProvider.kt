package org.monogram.data.infra

import android.content.Context
import android.os.Build
import java.util.Locale

data class TelegramClientMetadata(
    val systemLanguageCode: String,
    val deviceModel: String,
    val systemVersion: String,
    val applicationVersion: String,
)

class TelegramClientMetadataProvider(
    private val context: Context,
) {
    fun create(): TelegramClientMetadata = TelegramClientMetadata(
        systemLanguageCode = Locale.getDefault().language,
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        systemVersion = Build.VERSION.RELEASE,
        applicationVersion = resolveAppVersion(),
    )

    private fun resolveAppVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }
}
