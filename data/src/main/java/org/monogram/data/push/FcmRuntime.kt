package org.monogram.data.push

interface FcmRuntime {
    val isSupported: Boolean

    suspend fun fetchToken(): String?
}
