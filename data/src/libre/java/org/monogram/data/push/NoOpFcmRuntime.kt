package org.monogram.data.push

class NoOpFcmRuntime : FcmRuntime {
    override val isSupported: Boolean = false

    override suspend fun fetchToken(): String? = null
}
