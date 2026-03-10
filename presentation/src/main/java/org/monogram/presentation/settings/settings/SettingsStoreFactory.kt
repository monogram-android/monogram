package org.monogram.presentation.settings.settings

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import org.monogram.presentation.settings.settings.SettingsStore.Intent
import org.monogram.presentation.settings.settings.SettingsStore.Label

class SettingsStoreFactory(
    private val storeFactory: StoreFactory,
    private val component: DefaultSettingsComponent
) {

    fun create(): SettingsStore =
        object : SettingsStore, Store<Intent, SettingsComponent.State, Label> by storeFactory.create(
            name = "SettingsStore",
            initialState = component.state.value,
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl
        ) {}

    private inner class ExecutorImpl : CoroutineExecutor<Intent, Nothing, SettingsComponent.State, Message, Label>() {
        override fun executeIntent(intent: Intent) {
            when (intent) {
                Intent.BackClicked -> component.onBackClicked()
                Intent.EditProfileClicked -> component.onEditProfileClicked()
                Intent.LogoutClicked -> component.onLogoutClicked()
                is Intent.NotificationToggled -> component.onNotificationToggled(intent.enabled)
                Intent.DevicesClicked -> component.onDevicesClicked()
                Intent.FoldersClicked -> component.onFoldersClicked()
                Intent.ChatSettingsClicked -> component.onChatSettingsClicked()
                Intent.DataStorageClicked -> component.onDataStorageClicked()
                Intent.PowerSavingClicked -> component.onPowerSavingClicked()
                Intent.PremiumClicked -> component.onPremiumClicked()
                Intent.PrivacyClicked -> component.onPrivacyClicked()
                Intent.NotificationsClicked -> component.onNotificationsClicked()
                Intent.LinkSettingsClicked -> component.onLinkSettingsClicked()
                Intent.CheckLinkStatus -> component.checkLinkStatus()
                Intent.QrCodeClicked -> component.onQrCodeClicked()
                Intent.QrCodeDismissed -> component.onQrCodeDismissed()
                Intent.ProxySettingsClicked -> component.onProxySettingsClicked()
                Intent.StickersClicked -> component.onStickersClicked()
                Intent.AboutClicked -> component.onAboutClicked()
                Intent.DebugClicked -> component.onDebugClicked()
                Intent.SupportClicked -> component.onSupportClicked()
                Intent.SupportDismissed -> component.onSupportDismissed()
                Intent.ShowSupportClicked -> component.onShowSupportClicked()
                is Intent.UpdateState -> dispatch(Message.UpdateState(intent.state))
            }
        }
    }

    private object ReducerImpl : Reducer<SettingsComponent.State, Message> {
        override fun SettingsComponent.State.reduce(msg: Message): SettingsComponent.State =
            when (msg) {
                is Message.UpdateState -> msg.state
            }
    }

    sealed class Message {
        data class UpdateState(val state: SettingsComponent.State) : Message()
    }
}
