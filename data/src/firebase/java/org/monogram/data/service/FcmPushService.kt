package org.monogram.data.service

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.monogram.data.push.MtProtoPushSync
import org.koin.android.ext.android.inject
import org.monogram.domain.repository.AppPreferencesProvider

internal class FcmPushService : FirebaseMessagingService() {
    private val appPreferences: AppPreferencesProvider by inject()
    private val pushSync: MtProtoPushSync by inject()
    private val delegate by lazy {
        BaseFcmPushService(
            context = this,
            appPreferences = appPreferences,
            pushSync = pushSync
        )
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        delegate.handleNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        delegate.handleMessage(message.data)
    }

    override fun onDeletedMessages() {
        super.onDeletedMessages()
        delegate.handleDeletedMessages()
    }
}
