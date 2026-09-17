package org.monogram.feature.settings

import kotlinx.serialization.Serializable

@Serializable
sealed interface SettingsPage {

    @Serializable
    data object Home : SettingsPage

    @Serializable
    data object Data : SettingsPage

    @Serializable
    data object Appearance : SettingsPage

    @Serializable
    data object Wallpaper : SettingsPage

    @Serializable
    data object Folders : SettingsPage

    @Serializable
    data object Notifications : SettingsPage

    /** Notification rules for one peer kind: `users`, `chats` or `broadcasts`. */
    @Serializable
    data class NotificationCategory(val kind: String) : SettingsPage

    @Serializable
    data object NotificationExceptions : SettingsPage

    @Serializable
    data object NotificationDebug : SettingsPage
}
