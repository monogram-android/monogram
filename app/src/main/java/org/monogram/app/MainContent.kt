package org.monogram.app

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInBack
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.window.core.layout.WindowWidthSizeClass
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.plus
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.androidPredictiveBackAnimatableV2
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.predictiveBackAnimation
import com.arkivanov.decompose.extensions.compose.stack.animation.slide
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.decompose.router.stack.ChildStack
import kotlinx.coroutines.launch
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.presentation.features.auth.AuthContent
import org.monogram.presentation.features.chats.chatList.ChatListContent
import org.monogram.presentation.features.chats.chatList.components.AvatarTopAppBar
import org.monogram.presentation.features.chats.currentChat.ChatContent
import org.monogram.presentation.features.chats.currentChat.components.StickerSetSheet
import org.monogram.presentation.features.chats.newChat.NewChatContent
import org.monogram.presentation.features.profile.ProfileContent
import org.monogram.presentation.features.profile.admin.AdminManageContent
import org.monogram.presentation.features.profile.admin.ChatEditContent
import org.monogram.presentation.features.profile.admin.ChatPermissionsContent
import org.monogram.presentation.features.profile.admin.MemberListContent
import org.monogram.presentation.features.profile.logs.ProfileLogsContent
import org.monogram.presentation.features.stickers.core.toDomain
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(
    root: RootComponent
) {
    val childStack by root.childStack.subscribeAsState()
    val isLocked by root.isLocked.collectAsState()
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val isExpanded = adaptiveInfo.windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.EXPANDED

    val activeChild = childStack.active.instance

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val contentScale by animateFloatAsState(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 300),
            label = "ContentScale"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = contentScale
                    scaleY = contentScale
                }
        ) {
            if (isExpanded && activeChild !is RootComponent.Child.AuthChild && activeChild !is RootComponent.Child.StartupChild) {
                TabletLayout(root, childStack)
            } else {
                MobileLayout(root)
            }
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