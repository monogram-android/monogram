package org.monogram.mtproto

import android.util.Log

object MtprotoNativeLoader {
    @Volatile
    private var cached: MtprotoNative? = null

    /**
     * Loads the real UniFFI backend. Falls back to [MtprotoNative.Stub] only if the
     * native library cannot initialize; Stub then fails all network calls explicitly.
     */
    fun loadOrStub(): MtprotoNative {
        cached?.let { return it }
        return synchronized(this) {
            cached?.let { return it }
            val loaded = runCatching {
                MtprotoNative.UniFfi.also { check(it.libraryVersion().isNotBlank()) }
            }.getOrElse { error ->
                Log.e("monogram.native", "UniFFI backend unavailable", error)
                MtprotoNative.Stub
            }
            cached = loaded
            loaded
        }
    }

    fun isUsingStub(): Boolean = cached === MtprotoNative.Stub
}
