package org.monogram.app.components

import androidx.compose.runtime.Composable
import org.monogram.presentation.features.auth.AuthContent
import org.monogram.presentation.features.chats.conversation.ChatContent
import org.monogram.presentation.features.chats.creation.NewChatContent
import org.monogram.presentation.features.chats.list.ChatListContent
import org.monogram.presentation.features.profile.ProfileContent
import org.monogram.presentation.features.profile.admin.AdminManageContent
import org.monogram.presentation.features.profile.admin.ChatEditContent
import org.monogram.presentation.features.profile.admin.ChatPermissionsContent
import org.monogram.presentation.features.profile.admin.MemberListContent
import org.monogram.presentation.features.profile.logs.ProfileLogsContent
import org.monogram.presentation.features.webview.InternalWebView
import org.monogram.presentation.root.RootComponent
import org.monogram.presentation.root.StartupContent
import org.monogram.presentation.settings.about.AboutContent
import org.monogram.presentation.settings.adblock.AdBlockContent
import org.monogram.presentation.settings.chatSettings.ChatSettingsContent
import org.monogram.presentation.settings.dataStorage.DataStorageContent
import org.monogram.presentation.settings.debug.DebugContent
import org.monogram.presentation.settings.folders.FoldersContent
import org.monogram.presentation.settings.networkUsage.NetworkUsageContent
import org.monogram.presentation.settings.notifications.NotificationsContent
import org.monogram.presentation.settings.powersaving.PowerSavingContent
import org.monogram.presentation.settings.premium.PremiumContent
import org.monogram.presentation.settings.privacy.PasscodeContent
import org.monogram.presentation.settings.privacy.PrivacyContent
import org.monogram.presentation.settings.profile.EditProfileContent
import org.monogram.presentation.settings.proxy.ProxyContent
import org.monogram.presentation.settings.sessions.SessionsContent
import org.monogram.presentation.settings.settings.SettingsContent
import org.monogram.presentation.settings.stickers.StickersContent
import org.monogram.presentation.settings.storage.StorageUsageContent

@Composable
fun RenderChild(child: RootComponent.Child, isOverlay: Boolean = false) {
    when (child) {
        is RootComponent.Child.StartupChild -> StartupContent(child.component)
        is RootComponent.Child.AuthChild -> AuthContent(child.component)
        is RootComponent.Child.ChatsChild -> ChatListContent(child.component)
        is RootComponent.Child.NewChatChild -> NewChatContent(child.component)
        is RootComponent.Child.ChatDetailChild -> ChatContent(
            component = child.component,
            isOverlay = isOverlay,
        )

        is RootComponent.Child.SettingsChild -> SettingsContent(child.component)
        is RootComponent.Child.EditProfileChild -> EditProfileContent(child.component)
        is RootComponent.Child.SessionsChild -> SessionsContent(child.component)
        is RootComponent.Child.FoldersChild -> FoldersContent(child.component)
        is RootComponent.Child.ChatSettingsChild -> ChatSettingsContent(child.component)
        is RootComponent.Child.DataStorageChild -> DataStorageContent(child.component)
        is RootComponent.Child.StorageUsageChild -> StorageUsageContent(child.component)
        is RootComponent.Child.NetworkUsageChild -> NetworkUsageContent(child.component)
        is RootComponent.Child.ProfileChild -> ProfileContent(child.component)
        is RootComponent.Child.PremiumChild -> PremiumContent(child.component)
        is RootComponent.Child.PrivacyChild -> PrivacyContent(child.component)
        is RootComponent.Child.AdBlockChild -> AdBlockContent(child.component)
        is RootComponent.Child.PowerSavingChild -> PowerSavingContent(child.component)
        is RootComponent.Child.NotificationsChild -> NotificationsContent(child.component)
        is RootComponent.Child.ProxyChild -> ProxyContent(child.component)
        is RootComponent.Child.ProfileLogsChild -> ProfileLogsContent(child.component)
        is RootComponent.Child.AdminManageChild -> AdminManageContent(child.component)
        is RootComponent.Child.ChatEditChild -> ChatEditContent(child.component)
        is RootComponent.Child.MemberListChild -> MemberListContent(child.component)
        is RootComponent.Child.ChatPermissionsChild -> ChatPermissionsContent(child.component)
        is RootComponent.Child.PasscodeChild -> PasscodeContent(child.component)
        is RootComponent.Child.StickersChild -> StickersContent(child.component)
        is RootComponent.Child.AboutChild -> AboutContent(child.component)
        is RootComponent.Child.DebugChild -> DebugContent(child.component)
        is RootComponent.Child.WebViewChild -> InternalWebView(
            url = child.component.url,
            onDismiss = child.component::onDismiss,
        )
    }
}
