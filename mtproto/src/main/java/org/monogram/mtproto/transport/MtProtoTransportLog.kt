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

    private const val TAG = "MonogramMtProto"
}
