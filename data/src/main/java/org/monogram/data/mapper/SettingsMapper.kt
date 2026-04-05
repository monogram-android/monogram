package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.domain.repository.NotificationSettingsRepository.TdNotificationScope

fun TdNotificationScope.toApi(): TdApi.NotificationSettingsScope = when (this) {
    TdNotificationScope.PRIVATE_CHATS -> TdApi.NotificationSettingsScopePrivateChats()
    TdNotificationScope.GROUPS -> TdApi.NotificationSettingsScopeGroupChats()
    TdNotificationScope.CHANNELS -> TdApi.NotificationSettingsScopeChannelChats()
}