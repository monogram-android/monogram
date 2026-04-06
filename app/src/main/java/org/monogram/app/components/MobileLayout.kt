package org.monogram.app.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.plus
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.predictiveBackAnimation
import com.arkivanov.decompose.extensions.compose.stack.animation.slide
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import kotlinx.coroutines.launch
import org.monogram.presentation.root.RootComponent

@OptIn(ExperimentalDecomposeApi::class)
@Composable
fun MobileLayout(root: RootComponent) {
    val stack by root.childStack.subscribeAsState()
    val isDragToBackEnabled by root.appPreferences.isDragToBackEnabled.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val dragOffsetX = remember { Animatable(0f) }
    val previous = stack.items.dropLast(1).lastOrNull()?.instance
    var swipeBackInProgress by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val canUseDragToBack =
        isDragToBackEnabled && stack.active.instance is RootComponent.Child.ChatDetailChild

    LaunchedEffect(canUseDragToBack) {
        if (!canUseDragToBack && dragOffsetX.value > 0f) {
            dragOffsetX.snapTo(0f)
        }
    }

    if (dragOffsetX.value > 0 && previous != null) {
        Box(modifier = Modifier.fillMaxSize()) {
            RenderChild(previous)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color.Black.copy(
                            alpha = 0.3f * (1f - (dragOffsetX.value / widthPx).coerceIn(0f, 1f)),
                        ),
                    ),
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                widthPx = it.width.toFloat()
            }
            .then(
                if (canUseDragToBack) {
                    Modifier.pointerInput(canUseDragToBack) {
                        var isDragging = false
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                isDragging = offset.x > 48.dp.toPx()
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                if (isDragging) {
                                    change.consume()
                                    coroutineScope.launch {
                                        val newOffset = dragOffsetX.value + dragAmount
                                        dragOffsetX.snapTo(newOffset.coerceAtLeast(0f))
                                    }
                                }
                            },
                            onDragEnd = {
                                if (isDragging) {
                                    coroutineScope.launch {
                                        val width = size.width.toFloat()
                                        if (dragOffsetX.value > width * 0.15f) {
                                            swipeBackInProgress = true
                                            dragOffsetX.animateTo(width, tween(200))
                                            root.onBack()
                                            dragOffsetX.snapTo(0f)
                                            swipeBackInProgress = false
                                        } else {
                                            dragOffsetX.animateTo(0f, spring())
                                        }
                                    }
                                    isDragging = false
                                }
                            },
                            onDragCancel = {
                                if (isDragging) {
                                    coroutineScope.launch { dragOffsetX.animateTo(0f) }
                                    isDragging = false
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                }
            )
            .graphicsLayer {
                translationX = dragOffsetX.value
                shadowElevation = if (dragOffsetX.value > 0) 20f else 0f
            },
    ) {
        Children(
            stack = root.childStack,
            animation = predictiveBackAnimation(
                backHandler = root.backHandler,
                onBack = root::onBack,
                fallbackAnimation = if (!swipeBackInProgress) stackAnimation(slide() + fade()) else null,
            ),
        ) {
            RenderChild(it.instance, isOverlay = false)
        }
    }
}
