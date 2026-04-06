package org.monogram.app.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import com.arkivanov.decompose.router.stack.ChildStack
import org.monogram.app.R
import org.monogram.presentation.root.RootComponent

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

    Row(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .width(animatedWidth)
                .fillMaxHeight(),
        ) {
            if (listChild != null) {
                RenderChild(listChild)
            }
        }

        HorizontalDivider(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
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
