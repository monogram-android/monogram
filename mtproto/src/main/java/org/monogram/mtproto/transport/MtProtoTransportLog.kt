package org.monogram.mtproto.transport

import android.util.Log

/** Emits metadata-only MTProto diagnostics when Android logging is available. */
internal object MtProtoTransportLog {
    fun debug(message: () -> String) {
        try {
            Log.d(TAG, message())
        } catch (_: RuntimeException) {
            // Android's local-unit-test Log stub throws instead of writing a log entry.
        }
    }

    fun warn(message: () -> String) {
        try {
            Log.w(TAG, message())
        } catch (_: RuntimeException) {
            // Android's local-unit-test Log stub throws instead of writing a log entry.
        }
    }

    fun localDetail(failure: Throwable): String {
        val detail = failure.message
            ?.replace(Regex("[\\r\\n\\t]"), " ")
            ?.take(MAX_DETAIL_CHARACTERS)
            ?.takeIf(String::isNotBlank)
        return detail?.let { "${failure.javaClass.simpleName}: $it" } ?: failure.javaClass.simpleName
    }

    private const val TAG = "MonogramMtProto"
    private const val MAX_DETAIL_CHARACTERS = 256
}
