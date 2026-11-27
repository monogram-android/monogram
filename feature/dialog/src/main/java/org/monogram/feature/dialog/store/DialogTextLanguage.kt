package org.monogram.feature.dialog.store

import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

internal object DialogTextLanguage {
    private var contextRef: WeakReference<Context>? = null

    fun install(context: Context) {
        contextRef = WeakReference(context.applicationContext)
    }

    suspend fun isExcluded(
        text: String,
        excluded: Set<String>,
    ): Boolean {
        if (excluded.isEmpty() || Build.VERSION.SDK_INT < 29) return false
        val appContext = contextRef?.get() ?: return false
        val classifier =
            appContext.getSystemService(TextClassificationManager::class.java)?.textClassifier
                ?: return false
        return withContext(Dispatchers.IO) {
            val result = try {
                classifier.detectLanguage(TextLanguage.Request.Builder(text.take(2000)).build())
            } catch (_: RuntimeException) {
                return@withContext false
            }
            if (result.localeHypothesisCount == 0) return@withContext false
            val locale = result.getLocale(0)
            isExcludedLanguage(
                locale.language,
                locale.toLanguageTag(),
                result.getConfidenceScore(locale),
                excluded,
            )
        }
    }
}

/** Exclusion is only trusted for confident detections, matching the server side source-language rules. */
internal fun isExcludedLanguage(
    detectedLanguage: String?,
    detectedTag: String?,
    confidence: Float,
    excluded: Set<String>,
): Boolean {
    if (detectedLanguage == null || excluded.isEmpty() || confidence < 0.8f) return false
    return detectedLanguage in excluded || (detectedTag != null && detectedTag in excluded)
}
