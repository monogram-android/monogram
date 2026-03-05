package org.monogram.app

import androidx.compose.animation.*
import androidx.compose.animation.core.EaseInBack
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.predictiveBackAnimation
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.presentation.auth.AuthContent
import org.monogram.presentation.chatsScreen.chatList.ChatListContent
import org.monogram.presentation.chatsScreen.chatList.NewChatContent
import org.monogram.presentation.chatsScreen.chatList.components.AvatarTopAppBar
import org.monogram.presentation.chatsScreen.currentChat.ChatContent
import org.monogram.presentation.chatsScreen.currentChat.components.StickerSetSheet
import org.monogram.presentation.chatsScreen.folders.FoldersContent
import org.monogram.presentation.profile.ProfileContent
import org.monogram.presentation.profile.admin.AdminManageContent
import org.monogram.presentation.profile.admin.ChatEditContent
import org.monogram.presentation.profile.admin.ChatPermissionsContent
import org.monogram.presentation.profile.admin.MemberListContent
import org.monogram.presentation.profile.logs.ProfileLogsContent
import org.monogram.presentation.root.RootComponent
import org.monogram.presentation.root.StartupContent
import org.monogram.presentation.settingsScreens.about.AboutContent
import org.monogram.presentation.settingsScreens.adblock.AdBlockContent
import org.monogram.presentation.settingsScreens.chatSettings.ChatSettingsContent
import org.monogram.presentation.settingsScreens.dataStorage.DataStorageContent
import org.monogram.presentation.settingsScreens.debug.DebugContent
import org.monogram.presentation.settingsScreens.networkUsage.NetworkUsageContent
import org.monogram.presentation.settingsScreens.notifications.NotificationsContent
import org.monogram.presentation.settingsScreens.powersaving.PowerSavingContent
import org.monogram.presentation.settingsScreens.premium.PremiumContent
import org.monogram.presentation.settingsScreens.privacy.PasscodeContent
import org.monogram.presentation.settingsScreens.privacy.PrivacyContent
import org.monogram.presentation.settingsScreens.profile.EditProfileContent
import org.monogram.presentation.settingsScreens.proxy.ProxyContent
import org.monogram.presentation.settingsScreens.sessions.SessionsContent
import org.monogram.presentation.settingsScreens.settings.SettingsContent
import org.monogram.presentation.settingsScreens.stickers.StickersContent
import org.monogram.presentation.settingsScreens.storage.StorageUsageContent
import org.monogram.presentation.stickers.core.toDomain

@OptIn(ExperimentalMaterial3Api::class, ExperimentalDecomposeApi::class)
@Composable
fun MainContent(
    root: RootComponent
) {
    val childStack by root.childStack.subscribeAsState()
    val isLocked by root.isLocked.collectAsState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Children(
            stack = root.childStack,
            animation = predictiveBackAnimation(
                backHandler = root.backHandler,
                onBack = root::onBack,
                fallbackAnimation = stackAnimation()
            )
        ) {
            RenderChild(root, it.instance)
        }

        val stickerPreviewState by root.stickerSetToPreview.collectAsState()
        stickerPreviewState.stickerSet?.let { set ->
            StickerSetSheet(
                stickerSet = set.toDomain(),
                onDismiss = root::dismissStickerPreview,
                onStickerClick = {}
            )
        }

        ProxyConfirmSheet(root)
        ChatConfirmJoinSheet(root)
        AnimatedVisibility(
            visible = isLocked,
            enter = fadeIn(tween(400)) + scaleIn(tween(400, easing = EaseOutBack), initialScale = 0.9f),
            exit = fadeOut(tween(400)) + scaleOut(tween(400, easing = EaseInBack), targetScale = 0.9f),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1000f)
        ) {
            LockScreen(root)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyConfirmSheet(root: RootComponent) {
    val proxyConfirmState by root.proxyToConfirm.collectAsState()
    if (proxyConfirmState.server != null) {
        ModalBottomSheet(
            onDismissRequest = root::dismissProxyConfirm,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "Proxy Details",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                Text(
                    text = "Add and connect to this proxy server",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        Modifier.padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        DetailRow("Server", proxyConfirmState.server!!)
                        DetailRow("Port", proxyConfirmState.port!!.toString())
                        val typeName = when (proxyConfirmState.type) {
                            is ProxyTypeModel.Mtproto -> "MTProto"
                            is ProxyTypeModel.Socks5 -> "SOCKS5"
                            is ProxyTypeModel.Http -> "HTTP"
                            else -> "Unknown"
                        }
                        DetailRow("Type", typeName)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = root::dismissProxyConfirm,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Cancel", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            root.confirmProxy(
                                proxyConfirmState.server!!,
                                proxyConfirmState.port!!,
                                proxyConfirmState.type!!
                            )
                        },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Connect", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatConfirmJoinSheet(root: RootComponent) {
    val chatConfirmJoinState by root.chatToConfirmJoin.collectAsState()
    if (chatConfirmJoinState.chat != null || chatConfirmJoinState.inviteLink != null) {
        ModalBottomSheet(
            onDismissRequest = root::dismissChatConfirmJoin,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val title = chatConfirmJoinState.chat?.title ?: chatConfirmJoinState.inviteTitle ?: ""
                val avatarPath = chatConfirmJoinState.chat?.avatarPath ?: chatConfirmJoinState.inviteAvatarPath
                val isChannel = chatConfirmJoinState.chat?.isChannel ?: chatConfirmJoinState.inviteIsChannel
                val memberCount = chatConfirmJoinState.chat?.memberCount ?: chatConfirmJoinState.inviteMemberCount
                val description = chatConfirmJoinState.fullInfo?.description ?: chatConfirmJoinState.inviteDescription

                AvatarTopAppBar(
                    path = avatarPath,
                    name = title,
                    size = 100.dp,
                    fontSize = 32,
                    videoPlayerPool = root.videoPlayerPool
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                val infoText = buildString {
                    if (isChannel) append("Channel") else append("Group")
                    if (memberCount > 0) append(" • $memberCount members")
                }

                Text(
                    text = infoText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                description?.let { bio ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = bio,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 3
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = root::dismissChatConfirmJoin,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Cancel", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            val chatId = chatConfirmJoinState.chat?.id
                            val inviteLink = chatConfirmJoinState.inviteLink
                            if (chatId != null) {
                                root.confirmJoinChat(chatId)
                            } else if (inviteLink != null) {
                                root.confirmJoinInviteLink(inviteLink)
                            }
                        },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Join", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun RenderChild(root: RootComponent, child: RootComponent.Child, isOverlay: Boolean = false) {
    val childStack by root.childStack.subscribeAsState()
    val previousChild = childStack.items.getOrNull(childStack.items.lastIndex - 1)?.instance

    when (child) {
        is RootComponent.Child.StartupChild -> StartupContent()
        is RootComponent.Child.AuthChild -> AuthContent(child.component)
        is RootComponent.Child.ChatsChild -> ChatListContent(child.component)
        is RootComponent.Child.NewChatChild -> NewChatContent(child.component)
        is RootComponent.Child.ChatDetailChild -> ChatContent(
            component = child.component,
            isOverlay = isOverlay,
            previousChild = previousChild,
            renderChild = { RenderChild(root, it) }
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
    }
}