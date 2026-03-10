package org.monogram.presentation.settings.settings

import com.arkivanov.mvikotlin.core.store.Store

interface SettingsStore : Store<SettingsStore.Intent, SettingsComponent.State, SettingsStore.Label> {

    sealed class Intent {
        object BackClicked : Intent()
        object EditProfileClicked : Intent()
        object LogoutClicked : Intent()
        data class NotificationToggled(val enabled: Boolean) : Intent()
        object DevicesClicked : Intent()
        object FoldersClicked : Intent()
        object ChatSettingsClicked : Intent()
        object DataStorageClicked : Intent()
        object PowerSavingClicked : Intent()
        object PremiumClicked : Intent()
        object PrivacyClicked : Intent()
        object NotificationsClicked : Intent()
        object LinkSettingsClicked : Intent()
        object CheckLinkStatus : Intent()
        object QrCodeClicked : Intent()
        object QrCodeDismissed : Intent()
        object ProxySettingsClicked : Intent()
        object StickersClicked : Intent()
        object AboutClicked : Intent()
        object DebugClicked : Intent()
        object SupportClicked : Intent()
        object SupportDismissed : Intent()
        object ShowSupportClicked : Intent()
        data class UpdateState(val state: SettingsComponent.State) : Intent()
    }

    sealed class Label {
        object Back : Label()
    }
}
