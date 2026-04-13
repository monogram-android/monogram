package org.monogram.data.notifications

import org.monogram.domain.repository.NotificationSettingsRepository.TdNotificationScope

data class NotificationMuteDecision(
    val isMuted: Boolean,
    val scope: TdNotificationScope?,
    val reason: Reason,
    val muteFor: Int,
    val useDefault: Boolean
) {
    enum class Reason {
        CHAT_MUTED,
        SCOPE_DISABLED,
        NOT_MUTED,
        SCOPE_NOT_LOADED,
        UNKNOWN_SCOPE
    }
}
