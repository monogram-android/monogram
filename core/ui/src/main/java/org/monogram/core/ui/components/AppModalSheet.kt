package org.monogram.core.ui.components

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch

/**
 * In-window modal sheet. Avoids Material [androidx.compose.material3.ModalBottomSheet]
 * because that Dialog window owns a second navigation bar.
 */
@Composable
fun AppModalSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    // design decision (not in M3): M3 modal sheets dim with the scrim at 0.32 alpha; over a light
    // thread the content behind still read as active, so the sheet scrim is deepened here.
    scrimColor: Color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
    showDragHandle: Boolean = true,
    padNavigationBars: Boolean = true,
    padIme: Boolean = true,
    visible: Boolean = true,
    onExited: () -> Unit = {},
    enterSpec: FiniteAnimationSpec<Float> = tween(
        durationMillis = 220,
        easing = FastOutSlowInEasing,
    ),
    exitSpec: FiniteAnimationSpec<Float> = tween(
        durationMillis = 180,
        easing = FastOutSlowInEasing,
    ),
    fadeSheet: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val view = LocalView.current
    val overlayWidth = with(density) {
        view.rootView.width.takeIf { it > 0 }?.toDp() ?: configuration.screenWidthDp.dp
    }
    val overlayHeight = with(density) {
        view.rootView.height.takeIf { it > 0 }?.toDp() ?: configuration.screenHeightDp.dp
    }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val scope = rememberCoroutineScope()
    val dismissRequest = rememberUpdatedState(onDismissRequest)
    val exit = rememberUpdatedState(exitSpec)
    val exited = rememberUpdatedState(onExited)
    var closing by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            closing = false
            progress.animateTo(1f, enterSpec)
        } else {
            progress.animateTo(0f, exit.value)
            exited.value()
        }
    }
    val close = remember(scope, progress) {
        { after: () -> Unit ->
            if (!closing) {
                closing = true
                scope.launch {
                    progress.animateTo(0f, exit.value)
                    after()
                }
            }
        }
    }
    val requestDismiss = remember(close) { { close { dismissRequest.value() } } }
    Popup(
        popupPositionProvider = WindowOriginPositionProvider,
        onDismissRequest = requestDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        Box(modifier = Modifier.size(overlayWidth, overlayHeight)) {
            TransparentPopupSystemBars()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = scrimColor.alpha * progress.value.coerceIn(0f, 1f)
                    }
                    .background(scrimColor.copy(alpha = 1f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = requestDismiss,
                    ),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = ((1f - progress.value) * size.height).coerceAtLeast(0f)
                        if (fadeSheet) alpha = progress.value.coerceIn(0f, 1f)
                    }
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    )
                    .then(modifier),
                color = containerColor,
                contentColor = contentColor,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (padNavigationBars) Modifier.padding(bottom = navBottom) else Modifier,
                        )
                        .then(if (padIme) Modifier.imePadding() else Modifier)
                        .clipToBounds(),
                ) {
                    if (showDragHandle) {
                        Box(
                            // 48dp touch target for the 36x4dp handle.
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 36.dp, height = 4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    ),
                            )
                        }
                    }
                    content()
                }
            }
        }
    }
}

@Composable
fun SheetPanelHost(
    visible: Boolean,
    content: @Composable (visible: Boolean, onExited: () -> Unit) -> Unit,
) {
    var mounted by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) mounted = true
    }
    if (mounted) {
        content(visible) { mounted = false }
    }
}

@Composable
private fun TransparentPopupSystemBars() {
    val view = LocalView.current
    val lightNav = !isSystemInDarkTheme()
    SideEffect {
        val root = view.rootView
        val params = root.layoutParams as? WindowManager.LayoutParams
        if (params != null) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                params.setFitInsetsTypes(0)
            }
            runCatching {
                (view.context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager)
                    .updateViewLayout(root, params)
            }
        }
        var ctx = view.context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) {
                val window = ctx.window
                WindowCompat.setDecorFitsSystemWindows(window, false)
                @Suppress("DEPRECATION")
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = lightNav
                break
            }
            ctx = ctx.baseContext
        }
    }
}

private object WindowOriginPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}
