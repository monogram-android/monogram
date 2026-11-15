package org.monogram.push

import org.monogram.core.common.Outcome

interface AccountPushHandler {
    fun setForeground(value: Boolean)
    suspend fun unregisterPush()
    fun stop()
    fun onFcmToken(token: String, onComplete: (() -> Unit)? = null)
    fun handleFcm(payload: String?, onComplete: (() -> Unit)? = null)
    fun decryptFcmPayload(cipher: String): Outcome<String>
    fun handleDecryptedFcmPayload(json: String, onComplete: (() -> Unit)? = null)
    fun handleNotificationAction(intent: android.content.Intent, done: () -> Unit)
}