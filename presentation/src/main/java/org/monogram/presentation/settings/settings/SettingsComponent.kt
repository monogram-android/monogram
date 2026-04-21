package org.monogram.presentation.settings.settings

import com.arkivanov.decompose.value.Value
import org.monogram.domain.models.UserModel
import org.monogram.presentation.core.util.IDownloadUtils

interface SettingsComponent {
    val state: Value<State>
    val downloadUtils: IDownloadUtils

    fun onBackClicked()
    fun onEditProfileClicked()
    fun onLogoutClicked()
    fun onNotificationToggled(enabled: Boolean)
    fun onDevicesClicked()
    fun onFoldersClicked()
    fun onChatSettingsClicked()
    fun onDataStorageClicked()
    fun onPowerSavingClicked()
    fun onPremiumClicked()
    fun onPrivacyClicked()
    fun onNotificationsClicked()
    fun onLinkSettingsClicked()
    fun checkLinkStatus()
    fun onQrCodeClicked()
    fun onQrCodeDismissed()
    fun onProxySettingsClicked()
    fun onStickersClicked()
    fun onAboutClicked()
    fun onDebugClicked()
    fun onSupportClicked()
    fun onSupportDismissed()
    fun onShowSupportClicked()
    fun onMoreOptionsClicked()
    fun onMoreOptionsDismissed()
    fun onSetEmojiStatus(customEmojiId: Long, statusPath: String?)
    fun onAvatarClick()
    fun onDismissAvatarViewer()

    data class State(
        val currentUser: UserModel? = null,
        val areNotificationsEnabled: Boolean = true,
        val isTMeLinkEnabled: Boolean = true,
        val isQrVisible: Boolean = false,
        val qrContent: String = "",
        val isSupportVisible: Boolean = false,
        val isMoreOptionsVisible: Boolean = false,
        val fullScreenImages: List<String>? = null,
        val fullScreenVideoPath: String? = null
    )
}
