package org.monogram.data.service

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.koin.android.ext.android.inject
import org.monogram.data.gateway.TelegramGateway
import org.monogram.domain.repository.AppPreferencesProvider

class FcmPushService : FirebaseMessagingService() {
    private val gateway: TelegramGateway by inject()
    private val appPreferences: AppPreferencesProvider by inject()
    private val delegate by lazy {
        BaseFcmPushService(
            context = this,
            gateway = gateway,
            appPreferences = appPreferences
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
