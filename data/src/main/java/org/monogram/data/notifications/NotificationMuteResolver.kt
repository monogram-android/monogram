package org.monogram.data.notifications

import org.drinkless.tdlib.TdApi
import org.monogram.data.db.model.NotificationSettingEntity
import org.monogram.data.notifications.NotificationMuteDecision.Reason
import org.monogram.domain.repository.NotificationSettingsRepository.TdNotificationScope

class NotificationMuteResolver {

    fun resolve(
        chat: TdApi.Chat,
        cachedSettings: NotificationSettingEntity?,
        scopeState: NotificationScopeState
    ): NotificationMuteDecision {
        val muteFor = cachedSettings?.muteFor ?: chat.notificationSettings?.muteFor ?: 0
        val useDefault =
            cachedSettings?.useDefault ?: chat.notificationSettings?.useDefaultMuteFor ?: true

        if (!useDefault) {
            return if (muteFor > 0) {
                NotificationMuteDecision(
                    isMuted = true,
                    scope = null,
                    reason = Reason.CHAT_MUTED,
                    muteFor = muteFor,
                    useDefault = false
                )
            } else {
                NotificationMuteDecision(
                    isMuted = false,
                    scope = null,
                    reason = Reason.NOT_MUTED,
                    muteFor = muteFor,
                    useDefault = false
                )
            }
        }

        val scope = resolveScope(chat.type)
        if (scope == null) {
            return NotificationMuteDecision(
                isMuted = muteFor > 0,
                scope = null,
                reason = if (muteFor > 0) Reason.CHAT_MUTED else Reason.UNKNOWN_SCOPE,
                muteFor = muteFor,
                useDefault = true
            )
        }

        if (!scopeState.loadedScopes.contains(scope)) {
            return NotificationMuteDecision(
                isMuted = false,
                scope = scope,
                reason = Reason.SCOPE_NOT_LOADED,
                muteFor = muteFor,
                useDefault = true
            )
        }

        val enabled = scopeState.enabledByScope[scope] ?: true
        return if (enabled) {
            NotificationMuteDecision(
                isMuted = false,
                scope = scope,
                reason = Reason.NOT_MUTED,
                muteFor = muteFor,
                useDefault = true
            )
        } else {
            NotificationMuteDecision(
                isMuted = true,
                scope = scope,
                reason = Reason.SCOPE_DISABLED,
                muteFor = muteFor,
                useDefault = true
            )
        }
    }

    private fun resolveScope(chatType: TdApi.ChatType?): TdNotificationScope? = when (chatType) {
        is TdApi.ChatTypePrivate -> TdNotificationScope.PRIVATE_CHATS
        is TdApi.ChatTypeBasicGroup -> TdNotificationScope.GROUPS
        is TdApi.ChatTypeSupergroup -> if (chatType.isChannel) TdNotificationScope.CHANNELS else TdNotificationScope.GROUPS
        else -> null
    }
}
