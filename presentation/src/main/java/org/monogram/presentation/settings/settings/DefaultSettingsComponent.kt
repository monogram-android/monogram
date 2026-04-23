package org.monogram.presentation.settings.settings

import android.os.Build
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.monogram.domain.managers.DomainManager
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.ExternalNavigator
import org.monogram.domain.repository.UserProfileEditRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.core.util.coRunCatching
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

class DefaultSettingsComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit,
    private val onEditProfileClick: () -> Unit,
    private val onDevicesClick: () -> Unit,
    private val onFoldersClick: () -> Unit,
    private val onChatSettingsClick: () -> Unit,
    private val onDataStorageClick: () -> Unit,
    private val onPowerSavingClick: () -> Unit,
    private val onPremiumClick: () -> Unit,
    private val onPrivacyClick: () -> Unit,
    private val onNotificationsClick: () -> Unit,
    private val onProxySettingsClick: () -> Unit,
    private val onStickersClick: () -> Unit,
    private val onAboutClick: () -> Unit,
    private val onDebugClick: () -> Unit
) : SettingsComponent, AppComponentContext by context {

    private val repository: UserRepository = container.repositories.userRepository
    private val userProfileEditRepository: UserProfileEditRepository = container.repositories.userProfileEditRepository
    private val externalNavigator: ExternalNavigator = container.utils.externalNavigator()
    private val domainManager: DomainManager = container.utils.domainManager()
    private val preferences: AppPreferencesProvider = container.preferences.appPreferences
    override val downloadUtils: IDownloadUtils = container.utils.downloadUtils()

    private val _state = MutableValue(SettingsComponent.State())
    override val state: Value<SettingsComponent.State> = _state
    private val scope = componentScope

    init {
        scope.launch {
            try {
                val me = repository.getMe()
                val link = if (me.username?.isNotEmpty() == true) "https://t.me/${me.username}" else ""

                _state.update {
                    it.copy(
                        currentUser = me,
                        qrContent = link
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(currentUser = null) }
            }
        }

        scope.launch {
            preferences.isSupportViewed.collectLatest { viewed ->
                if (!viewed) {
                    _state.update { it.copy(isSupportVisible = true) }
                }
            }
        }
        checkLinkStatus()
    }

    override fun onBackClicked() {
        onBack()
    }

    override fun onEditProfileClicked() {
        onEditProfileClick()
    }

    override fun onLogoutClicked() {
        repository.logOut()
    }

    override fun onNotificationToggled(enabled: Boolean) {
        _state.update { it.copy(areNotificationsEnabled = enabled) }
    }

    override fun onDevicesClicked() {
        onDevicesClick()
    }

    override fun onFoldersClicked() {
        onFoldersClick()
    }

    override fun onChatSettingsClicked() {
        onChatSettingsClick()
    }

    override fun onDataStorageClicked() {
        onDataStorageClick()
    }

    override fun onPowerSavingClicked() {
        onPowerSavingClick()
    }

    override fun onPremiumClicked() {
        onPremiumClick()
    }

    override fun onPrivacyClicked() {
        onPrivacyClick()
    }

    override fun onNotificationsClicked() {
        onNotificationsClick()
    }

    override fun onLinkSettingsClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            externalNavigator.navigateToLinkSettings()
        }
    }

    override fun checkLinkStatus() {
        _state.update { it.copy(isTMeLinkEnabled = domainManager.isEnabled() ) }
    }

    override fun onQrCodeClicked() {
        _state.update { it.copy(isQrVisible = true) }
    }

    override fun onQrCodeDismissed() {
        _state.update { it.copy(isQrVisible = false) }
    }

    override fun onProxySettingsClicked() {
        onProxySettingsClick()
    }

    override fun onStickersClicked() {
        onStickersClick()
    }

    override fun onAboutClicked() {
        onAboutClick()
    }

    override fun onDebugClicked() {
        onDebugClick()
    }

    override fun onSupportClicked() {
        externalNavigator.openUrl("https://boosty.to/monogram")
        onSupportDismissed()
    }

    override fun onSupportDismissed() {
        preferences.setSupportViewed(true)
        _state.update { it.copy(isSupportVisible = false) }
    }

    override fun onShowSupportClicked() {
        _state.update { it.copy(isSupportVisible = true) }
    }

    override fun onMoreOptionsClicked() {
        _state.update { it.copy(isMoreOptionsVisible = true) }
    }

    override fun onMoreOptionsDismissed() {
        _state.update { it.copy(isMoreOptionsVisible = false) }
    }

    override fun onSetEmojiStatus(customEmojiId: Long, statusPath: String?) {
        _state.update { state ->
            val user = state.currentUser ?: return@update state
            state.copy(
                currentUser = user.copy(
                    statusEmojiId = customEmojiId,
                    statusEmojiPath = statusPath
                )
            )
        }

        scope.launch(Dispatchers.IO) {
            coRunCatching {
                userProfileEditRepository.setEmojiStatus(customEmojiId)
            }
        }
    }

    override fun onAvatarClick() {
        val user = _state.value.currentUser ?: return
        val avatarPath = user.avatarPath?.takeIf { it.isNotBlank() }
            ?: user.personalAvatarPath?.takeIf { it.isNotBlank() }
            ?: return

        _state.update { state ->
            if (avatarPath.endsWith(".mp4", ignoreCase = true)) {
                state.copy(
                    fullScreenImages = null,
                    fullScreenVideoPath = avatarPath
                )
            } else {
                state.copy(
                    fullScreenImages = listOf(avatarPath),
                    fullScreenVideoPath = null
                )
            }
        }
    }

    override fun onDismissAvatarViewer() {
        _state.update {
            it.copy(
                fullScreenImages = null,
                fullScreenVideoPath = null
            )
        }
    }
}
