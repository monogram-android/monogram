package org.monogram.core.ui

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DownloadConcurrency(val lanes: Int, val parts: Int)

data class DownloadState(
    val lanes: Int = 8,
    val parts: Int = 6,
    val speedUpUploads: Boolean = false,
) {
    val concurrency: DownloadConcurrency get() = DownloadConcurrency(lanes = lanes, parts = parts)
    val filePartKib: Int get() = if (speedUpUploads) 512 else 32
}

object DownloadSettings {
    private const val PREFS = "monogram_download"
    private const val KEY_SPEED_UP_UPLOADS = "speed_up_uploads"

    private val mutable = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = mutable.asStateFlow()

    val concurrency: DownloadConcurrency get() = mutable.value.concurrency

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var apply: ((DownloadState) -> Unit)? = null

    fun install(context: Context, apply: (DownloadState) -> Unit) {
        val app = context.applicationContext
        appContext = app
        this.apply = apply
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mutable.value = DownloadState(
            speedUpUploads = prefs.getBoolean(KEY_SPEED_UP_UPLOADS, false),
        )
        apply(mutable.value)
    }

    fun setSpeedUpUploads(enabled: Boolean) {
        mutable.update { it.copy(speedUpUploads = enabled) }
        persist()
        apply?.invoke(mutable.value)
    }

    private fun persist() {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        prefs.edit { putBoolean(KEY_SPEED_UP_UPLOADS, mutable.value.speedUpUploads) }
    }
}
