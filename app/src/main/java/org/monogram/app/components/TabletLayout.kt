package org.monogram.app.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import com.arkivanov.decompose.router.stack.ChildStack
import org.monogram.app.R
import org.monogram.presentation.root.RootComponent
import android.os.Build
import android.view.RoundedCorner

@Composable
fun TabletLayout(
    childStack: ChildStack<*, RootComponent.Child>,
    windowLayoutInfo: WindowLayoutInfo?
) {
    val activeChild = childStack.active.instance
    val isSettings = isSettingsSelected(childStack)
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val screenWidthDp = with(density) { windowInfo.containerSize.width.toDp() }

    val view = LocalView.current
    val deviceCornerRadius = remember(view, density, screenWidthDp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val insets = view.rootWindowInsets
            val corners = listOfNotNull(
                insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT),
                insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT),
                insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT),
                insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
            )

            if (corners.isNotEmpty()) {
                // Use the reported radius if available. On sharp devices, this is 0.
                val radiusPx = corners.maxOf { it.radius }
                with(density) { radiusPx.toDp() }
            } else {
                // Fallback only if the OS provides no corner information at all.
                if (screenWidthDp > 600.dp) 28.dp else 16.dp
            }
        } else {
            if (screenWidthDp > 600.dp) 28.dp else 16.dp
        }
    }

    val foldingFeature = windowLayoutInfo?.displayFeatures
        ?.filterIsInstance<FoldingFeature>()
        ?.find { it.orientation == FoldingFeature.Orientation.VERTICAL }

    val targetListWidth = remember(foldingFeature, screenWidthDp) {
        if (foldingFeature != null && foldingFeature.isSeparating) {
            val hingeBounds = foldingFeature.bounds
            if (hingeBounds.left > 0) {
                with(density) { hingeBounds.left.toDp() }
            } else {
                screenWidthDp / 2
            }
        } else {
            350.dp
        }
    }

    val animatedWidth by animateDpAsState(
        targetValue = targetListWidth,
        animationSpec = tween(durationMillis = 500),
        label = "ListPaneWidth"
    )

    val listChild = remember(childStack) {
        val settingsChild = childStack.backStack.find {
            it.instance is RootComponent.Child.SettingsChild
        }?.instance ?: (activeChild as? RootComponent.Child.SettingsChild)
        val chatsChild = childStack.backStack.find {
            it.instance is RootComponent.Child.ChatsChild
        }?.instance ?: (activeChild as? RootComponent.Child.ChatsChild)

        if (isSettings && settingsChild != null) {
            settingsChild
        } else {
            chatsChild
        }
    }

    val paneCornerRadius = remember(deviceCornerRadius) {
        val opticalScaling = 1.5f
        (deviceCornerRadius * opticalScaling - 6.dp).coerceAtLeast(0.dp)
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Box(
            modifier = Modifier
                .width(animatedWidth)
                .fillMaxHeight()
                .padding(start = 6.dp, end = 6.dp,  top = 6.dp, bottom = 6.dp)
                .clip(RoundedCornerShape(paneCornerRadius))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (listChild != null) {
                RenderChild(listChild)
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(end = 6.dp, top = 6.dp, bottom = 6.dp)
                .clip(RoundedCornerShape(paneCornerRadius))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            val isListOnly = activeChild == listChild

            if (!isListOnly) {
                RenderChild(activeChild)
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isSettings) {
                            stringResource(R.string.tablet_select_setting)
                        } else {
                            stringResource(R.string.tablet_select_chat)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun isSettingsSelected(stack: ChildStack<*, RootComponent.Child>): Boolean {
    return when (stack.active.instance) {
        is RootComponent.Child.SettingsChild,
        is RootComponent.Child.EditProfileChild,
        is RootComponent.Child.SessionsChild,
        is RootComponent.Child.FoldersChild,
        is RootComponent.Child.ChatSettingsChild,
        is RootComponent.Child.DataStorageChild,
        is RootComponent.Child.StorageUsageChild,
        is RootComponent.Child.NetworkUsageChild,
        is RootComponent.Child.PrivacyChild,
        is RootComponent.Child.AdBlockChild,
        is RootComponent.Child.PowerSavingChild,
        is RootComponent.Child.NotificationsChild,
        is RootComponent.Child.PremiumChild,
        is RootComponent.Child.ProxyChild,
        is RootComponent.Child.StickersChild,
        is RootComponent.Child.AboutChild,
        is RootComponent.Child.DebugChild -> true

        else -> false
    }
}
