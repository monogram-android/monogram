package org.monogram.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInBack
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass as WindowSizeClassCore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.zIndex
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.app.components.ChatConfirmJoinSheet
import org.monogram.app.components.LockScreen
import org.monogram.app.components.MobileLayout
import org.monogram.app.components.ProxyConfirmSheet
import org.monogram.app.components.TabletLayout
import org.monogram.presentation.features.chats.currentChat.chatContent.ChatContentViewers
import org.monogram.presentation.features.chats.currentChat.components.StickerSetSheet
import org.monogram.presentation.features.profile.ProfileViewers
import org.monogram.presentation.features.stickers.core.toDomain
import org.monogram.presentation.root.RootComponent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(
    root: RootComponent,
    windowLayoutInfo: WindowLayoutInfo?
) {
    val childStack by root.childStack.subscribeAsState()
    val isLocked by root.isLocked.collectAsState()
    val localClipboard = LocalClipboard.current
    
    val isExpanded = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClassCore.WIDTH_DP_MEDIUM_LOWER_BOUND) ||
            windowLayoutInfo?.displayFeatures?.filterIsInstance<FoldingFeature>()?.any {
                it.orientation == FoldingFeature.Orientation.VERTICAL && it.isSeparating
            } == true

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
                },
        ) {
            if (isExpanded &&
                activeChild !is RootComponent.Child.AuthChild &&
                activeChild !is RootComponent.Child.StartupChild
            ) {
                TabletLayout(childStack, windowLayoutInfo)
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

        if (activeChild !is RootComponent.Child.AuthChild &&
            activeChild !is RootComponent.Child.StartupChild
        ) {
            ProxyConfirmSheet(root)
            ChatConfirmJoinSheet(root)
        }

        AnimatedVisibility(
            visible = isLocked,
            enter = fadeIn(tween(400)) + scaleIn(
                animationSpec = tween(400, easing = EaseOutBack),
                initialScale = 0.9f
            ),
            exit = fadeOut(tween(400)) + scaleOut(
                animationSpec = tween(400, easing = EaseInBack),
                targetScale = 0.9f
            ),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1000f)
        ) {
            LockScreen(root)
        }

        // Hoisted Viewers
        when (activeChild) {
            is RootComponent.Child.ChatDetailChild -> {
                val chatState by activeChild.component.state.collectAsState()
                ChatContentViewers(
                    state = chatState,
                    component = activeChild.component,
                    localClipboard = localClipboard
                )
            }
            is RootComponent.Child.ProfileChild -> {
                val profileState by activeChild.component.state.subscribeAsState()
                ProfileViewers(
                    state = profileState,
                    component = activeChild.component
                )
            }
            else -> {}
        }
    }
}
