package org.monogram.data.push

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

class FirebaseFcmRuntime : FcmRuntime {
    override val isSupported: Boolean = true

    override suspend fun fetchToken(): String? = FirebaseMessaging.getInstance().token.await()
}
