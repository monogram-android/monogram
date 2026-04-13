package org.monogram.data.notifications

import org.monogram.domain.repository.NotificationSettingsRepository.TdNotificationScope

data class NotificationScopeState(
    val loadedScopes: Set<TdNotificationScope>,
    val enabledByScope: Map<TdNotificationScope, Boolean>
)
