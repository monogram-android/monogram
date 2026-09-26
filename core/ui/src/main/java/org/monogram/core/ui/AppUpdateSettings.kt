package org.monogram.core.ui

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppUpdateSettings {
    private const val PREFS = "monogram_updates"
    private const val KEY_BETA = "beta_updates"
    private const val KEY_SKIPPED = "skipped_update"

    private val beta = MutableStateFlow(false)
    val betaUpdates: StateFlow<Boolean> = beta.asStateFlow()

    private val skipped = MutableStateFlow("")
    val skippedUpdateKey: StateFlow<String> = skipped.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        beta.value = prefs.getBoolean(KEY_BETA, false)
        skipped.value = prefs.getString(KEY_SKIPPED, "").orEmpty()
    }

    fun setBetaUpdates(enabled: Boolean) {
        beta.value = enabled
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit {
            putBoolean(KEY_BETA, enabled)
        }
    }

    fun skipUpdate(key: String) {
        skipped.value = key
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit {
            putString(KEY_SKIPPED, key)
        }
    }
}
