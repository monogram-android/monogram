package org.monogram.presentation.root

import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.backhandler.BackHandler
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import org.monogram.domain.models.ChatFullInfoModel
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.features.auth.AuthComponent
import org.monogram.presentation.features.chats.ChatListComponent
import org.monogram.presentation.features.chats.currentChat.ChatComponent
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.chats.newChat.NewChatComponent
import org.monogram.presentation.settings.folders.FoldersComponent
import org.monogram.presentation.features.profile.ProfileComponent
import org.monogram.presentation.features.profile.admin.AdminManageComponent
import org.monogram.presentation.features.profile.admin.ChatEditComponent
import org.monogram.presentation.features.profile.admin.ChatPermissionsComponent
import org.monogram.presentation.features.profile.admin.MemberListComponent
import org.monogram.presentation.features.profile.logs.ProfileLogsComponent
import org.monogram.presentation.features.stickers.core.StickerSetUiModel
import org.monogram.presentation.features.webview.WebViewComponent
import org.monogram.presentation.settings.about.AboutComponent
import org.monogram.presentation.settings.adblock.AdBlockComponent
import org.monogram.presentation.settings.chatSettings.ChatSettingsComponent
import org.monogram.presentation.settings.dataStorage.DataStorageComponent
import org.monogram.presentation.settings.debug.DebugComponent
import org.monogram.presentation.settings.networkUsage.NetworkUsageComponent
import org.monogram.presentation.settings.notifications.NotificationsComponent
import org.monogram.presentation.settings.powersaving.PowerSavingComponent
import org.monogram.presentation.settings.premium.PremiumComponent
import org.monogram.presentation.settings.privacy.PasscodeComponent
import org.monogram.presentation.settings.privacy.PrivacyComponent
import org.monogram.presentation.settings.profile.EditProfileComponent
import org.monogram.presentation.settings.proxy.ProxyComponent
import org.monogram.presentation.settings.sessions.SessionsComponent
import org.monogram.presentation.settings.settings.SettingsComponent
import org.monogram.presentation.settings.stickers.StickersComponent
import org.monogram.presentation.settings.storage.StorageUsageComponent

interface RootComponent {
    val backHandler: BackHandler
    val childStack: Value<ChildStack<*, Child>>
    val stickerSetToPreview: StateFlow<StickerPreviewState>
    val proxyToConfirm: StateFlow<ProxyConfirmState>
    val chatToConfirmJoin: StateFlow<ChatConfirmJoinState>
    val isLocked: StateFlow<Boolean>
    val isBiometricEnabled: StateFlow<Boolean>
    val videoPlayerPool: VideoPlayerPool
    val appPreferences: AppPreferences

    fun onBack()
    fun handleLink(link: String)
    fun dismissStickerPreview()
    fun onSettingsClick()
    fun onChatsClick()
    fun dismissProxyConfirm()
    fun confirmProxy(server: String, port: Int, type: ProxyTypeModel)
    fun recheckProxyPing()
    fun dismissChatConfirmJoin()
    fun confirmJoinChat(chatId: Long)
    fun confirmJoinInviteLink(inviteLink: String)
    fun unlock(passcode: String): Boolean
    fun unlockWithBiometrics()
    fun logout()
    fun navigateToChat(chatId: Long, messageId: Long? = null)

    sealed class Child {
        class StartupChild(val component: StartupComponent) : Child()
        class AuthChild(val component: AuthComponent) : Child()
        class ChatsChild(val component: ChatListComponent) : Child()
        class NewChatChild(val component: NewChatComponent) : Child()
        class ChatDetailChild(val component: ChatComponent) : Child()
        class SettingsChild(val component: SettingsComponent) : Child()
        class EditProfileChild(val component: EditProfileComponent) : Child()
        class SessionsChild(val component: SessionsComponent) : Child()
        class FoldersChild(val component: FoldersComponent) : Child()
        class ChatSettingsChild(val component: ChatSettingsComponent) : Child()
        class DataStorageChild(val component: DataStorageComponent) : Child()
        class StorageUsageChild(val component: StorageUsageComponent) : Child()
        class NetworkUsageChild(val component: NetworkUsageComponent) : Child()
        class ProfileChild(val component: ProfileComponent) : Child()
        class PremiumChild(val component: PremiumComponent) : Child()
        class PrivacyChild(val component: PrivacyComponent) : Child()
        class AdBlockChild(val component: AdBlockComponent) : Child()
        class PowerSavingChild(val component: PowerSavingComponent) : Child()
        class NotificationsChild(val component: NotificationsComponent) : Child()
        class ProxyChild(val component: ProxyComponent) : Child()
        class ProfileLogsChild(val component: ProfileLogsComponent) : Child()
        class AdminManageChild(val component: AdminManageComponent) : Child()
        class ChatEditChild(val component: ChatEditComponent) : Child()
        class MemberListChild(val component: MemberListComponent) : Child()
        class ChatPermissionsChild(val component: ChatPermissionsComponent) : Child()
        class PasscodeChild(val component: PasscodeComponent) : Child()
        class StickersChild(val component: StickersComponent) : Child()
        class AboutChild(val component: AboutComponent) : Child()
        class DebugChild(val component: DebugComponent) : Child()
        class WebViewChild(val component: WebViewComponent) : Child()
    }

    @Serializable
    data class StickerPreviewState(
        val stickerSet: StickerSetUiModel? = null
    )

    data class ProxyConfirmState(
        val server: String? = null,
        val port: Int? = null,
        val type: ProxyTypeModel? = null,
        val ping: Long? = null,
        val isChecking: Boolean = false
    )

    data class ChatConfirmJoinState(
        val chat: ChatModel? = null,
        val fullInfo: ChatFullInfoModel? = null,
        val inviteLink: String? = null,
        val inviteTitle: String? = null,
        val inviteDescription: String? = null,
        val inviteMemberCount: Int = 0,
        val inviteAvatarPath: String? = null,
        val inviteIsChannel: Boolean = false
    )
}