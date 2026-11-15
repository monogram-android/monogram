package org.monogram.mtproto

import android.content.Context
import android.util.Log
import androidx.annotation.RestrictTo
import java.io.File

object MtprotoNativeLoader {
    @Volatile
    private var cached: MtprotoNative? = null

    private var platformVerifierInitialized = false

    @Synchronized
    fun initializePlatformVerifier(context: Context) {
        if (platformVerifierInitialized || loadOrStub() === MtprotoNative.Stub) return
        System.loadLibrary("monogram_mtproto")
        initPlatformVerifier(context.applicationContext)
        platformVerifierInitialized = true
    }

    private external fun initPlatformVerifier(context: Context)

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

    /** Call only after closing the client that owns the source session. */
    fun promoteSession(source: File, destination: File) =
        SessionKeyStore.promote(source, destination)

    /** Only a journaled destination owner may call this with both clients closed. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun recoverSessionPromotion(source: File, destination: File) =
        SessionKeyStore.recoverPromotion(source, destination)

    fun isUsingStub(): Boolean = cached === MtprotoNative.Stub
}
