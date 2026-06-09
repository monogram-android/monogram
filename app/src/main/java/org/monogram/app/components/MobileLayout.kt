package org.monogram.app.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.zIndex
import com.arkivanov.decompose.Child
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.StackAnimation
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.decompose.router.stack.ChildStack
import kotlinx.coroutines.launch
import org.monogram.presentation.root.RootComponent
import kotlin.math.abs

@OptIn(ExperimentalDecomposeApi::class)
@Composable
fun MobileLayout(root: RootComponent) {
    val stack by root.childStack.subscribeAsState()
    val isDragToBackEnabled by root.appPreferences.isDragToBackEnabled.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val activeEntry = stack.active
    val previousEntry = stack.items.dropLast(1).lastOrNull()
    val stackKeysAreUnique = stack.items.map { it.key }.toSet().size == stack.items.size
    val canRenderSwipePreview =
        stackKeysAreUnique &&
                previousEntry != null &&
                previousEntry.key != activeEntry.key &&
                previousEntry.instance !== activeEntry.instance
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var isCompletingSwipeBack by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var isSwipeBackBlocked by remember { mutableStateOf(false) }
    val canUseDragToBack =
        isDragToBackEnabled &&
                previousEntry != null &&
                stackKeysAreUnique &&
                isSwipeBackSupported(activeEntry.instance) &&
                !isSwipeBackBlocked
    val dragProgress = if (widthPx > 0f) (dragOffsetX / widthPx).coerceIn(0f, 1f) else 0f

    LaunchedEffect(activeEntry.key, stackKeysAreUnique) {
        isSwipeBackBlocked = false
        if (!stackKeysAreUnique) {
            dragOffsetX = 0f
            isCompletingSwipeBack = false
        }
    }

    LaunchedEffect(canUseDragToBack) {
        if (!canUseDragToBack && dragOffsetX > 0f) {
            dragOffsetX = 0f
            isCompletingSwipeBack = false
        }
    }

    if (dragOffsetX > 0f && canRenderSwipePreview) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = ((dragProgress - 1f) * widthPx * 0.08f)
                    },
            ) {
                key("swipe-preview:${previousEntry.key}") {
                    RenderChild(
                        child = previousEntry.instance,
                        isOverlay = true,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color.Black.copy(
                            alpha = 0.3f * (1f - dragProgress),
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
                    Modifier.pointerInput(canUseDragToBack, activeEntry.key) {
                        awaitEachGesture {
                            if (size.width == 0) return@awaitEachGesture

                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Main,
                            )
                            val pointerId = down.id
                            val touchSlop = viewConfiguration.touchSlop
                            val velocityTracker = VelocityTracker()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)

                            var totalDx = 0f
                            var totalDy = 0f
                            var isDragging = false
                            var shouldAnimateBack = false

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                if (event.changes.any { it.isConsumed } && !isDragging) {
                                    dragOffsetX = 0f
                                    break
                                }

                                val change =
                                    event.changes.fastFirstOrNull { it.id == pointerId } ?: break
                                velocityTracker.addPosition(change.uptimeMillis, change.position)

                                if (change.changedToUpIgnoreConsumed()) {
                                    if (isDragging) {
                                        val width = size.width.toFloat()
                                        val progress = (dragOffsetX / width).coerceIn(0f, 1f)
                                        val velocityX = velocityTracker.calculateVelocity().x
                                        val shouldCommit = progress >= 0.22f || velocityX >= 1400f

                                        if (shouldCommit) {
                                            coroutineScope.launch {
                                                isCompletingSwipeBack = true
                                                animate(
                                                    initialValue = dragOffsetX,
                                                    targetValue = width,
                                                    animationSpec = tween(durationMillis = 180),
                                                ) { value, _ ->
                                                    dragOffsetX = value
                                                }
                                                root.onBack()
                                                dragOffsetX = 0f
                                                isCompletingSwipeBack = false
                                            }
                                        } else {
                                            shouldAnimateBack = true
                                        }
                                    }

                                    break
                                }

                                val delta = change.position - change.previousPosition

                                if (!isDragging) {
                                    totalDx += delta.x
                                    totalDy += delta.y

                                    val passedHorizontalSlop =
                                        totalDx > touchSlop && abs(totalDx) > abs(totalDy)
                                    val movedLeft = totalDx < -touchSlop

                                    if (movedLeft) {
                                        dragOffsetX = 0f
                                        break
                                    }

                                    if (!passedHorizontalSlop) {
                                        continue
                                    }

                                    if (!canRenderSwipePreview) {
                                        dragOffsetX = 0f
                                        break
                                    }

                                    isDragging = true
                                }

                                if (delta != Offset.Zero) {
                                    change.consume()
                                }
                                dragOffsetX =
                                    (dragOffsetX + delta.x).coerceIn(0f, size.width.toFloat())
                            }

                            if (shouldAnimateBack && dragOffsetX > 0f && !isCompletingSwipeBack) {
                                coroutineScope.launch {
                                    animate(
                                        initialValue = dragOffsetX,
                                        targetValue = 0f,
                                        animationSpec = spring(),
                                    ) { value, _ ->
                                        dragOffsetX = value
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
            .graphicsLayer {
                translationX = dragOffsetX
                shadowElevation = if (dragOffsetX > 0f) 12f else 0f
            },
    ) {
        Children(
            stack = stack,
            animation = safeStackAnimation(
                enabled = dragOffsetX == 0f && !isCompletingSwipeBack,
            ),
        ) { child ->
            key(child.key) {
                RenderChild(
                    child = child.instance,
                    isOverlay = false,
                    onSwipeBackBlockedChanged = { blocked ->
                        if (stack.active.instance === child.instance) {
                            isSwipeBackBlocked = blocked
                        }
                    },
                )
            }
        }
    }
}

private fun isSwipeBackSupported(child: RootComponent.Child): Boolean =
    when (child) {
        is RootComponent.Child.ChatDetailChild,
        is RootComponent.Child.ProfileChild,
        is RootComponent.Child.SettingsChild,
        is RootComponent.Child.EditProfileChild,
        is RootComponent.Child.SessionsChild,
        is RootComponent.Child.FoldersChild,
        is RootComponent.Child.ChatSettingsChild,
        is RootComponent.Child.DataStorageChild,
        is RootComponent.Child.StorageUsageChild,
        is RootComponent.Child.NetworkUsageChild,
        is RootComponent.Child.PremiumChild,
        is RootComponent.Child.PrivacyChild,
        is RootComponent.Child.AdBlockChild,
        is RootComponent.Child.PowerSavingChild,
        is RootComponent.Child.NotificationsChild,
        is RootComponent.Child.ProxyChild,
        is RootComponent.Child.ProfileLogsChild,
        is RootComponent.Child.AdminManageChild,
        is RootComponent.Child.ChatEditChild,
        is RootComponent.Child.MemberListChild,
        is RootComponent.Child.ChatPermissionsChild,
        is RootComponent.Child.StickersChild,
        is RootComponent.Child.AboutChild,
        is RootComponent.Child.NewChatChild -> true
        is RootComponent.Child.DebugChild -> true

        else -> false
    }

private fun <C : Any, T : Any> activeOnlyStackAnimation(): StackAnimation<C, T> =
    StackAnimation { stack, modifier, content ->
        Box(modifier = modifier) {
            content(stack.active)
        }
    }

@Composable
private fun <C : Any, T : Any> safeStackAnimation(enabled: Boolean): StackAnimation<C, T> {
    if (!enabled) {
        return activeOnlyStackAnimation()
    }

    return StackAnimation { stack, modifier, content ->
        var previousStack by remember { mutableStateOf<ChildStack<C, T>?>(null) }
        var transition by remember { mutableStateOf<StackTransition<C, T>?>(null) }
        val oldStack = previousStack

        if (oldStack == null) {
            previousStack = stack
        } else if (oldStack.active.key != stack.active.key) {
            val oldActive = oldStack.active
            val newActive = stack.active
            transition =
                if (oldActive.key != newActive.key) {
                    StackTransition(
                        oldActive = oldActive,
                        newActive = newActive,
                        isPop = stack.items.size < oldStack.items.size,
                    )
                } else {
                    null
                }
            previousStack = stack
        }

        val currentTransition = transition
        val animationProgress by animateFloatAsState(
            targetValue = if (currentTransition == null) 1f else 0f,
            animationSpec = tween(durationMillis = 220),
            label = "MobileStackAnimation",
            finishedListener = {
                transition = null
            },
        )

        Box(modifier = modifier) {
            if (
                currentTransition != null &&
                currentTransition.oldActive.key != currentTransition.newActive.key
            ) {
                StackAnimatedChild(
                    child = currentTransition.oldActive,
                    progress = animationProgress,
                    isPop = currentTransition.isPop,
                    isOutgoing = true,
                    content = content,
                )
                StackAnimatedChild(
                    child = currentTransition.newActive,
                    progress = animationProgress,
                    isPop = currentTransition.isPop,
                    isOutgoing = false,
                    content = content,
                )
            } else {
                content(stack.active)
            }
        }
    }
}

@Composable
private fun <C : Any, T : Any> StackAnimatedChild(
    child: Child.Created<C, T>,
    progress: Float,
    isPop: Boolean,
    isOutgoing: Boolean,
    content: @Composable (child: Child.Created<C, T>) -> Unit,
) {
    val direction = if (isPop) -1f else 1f
    val translationFactor =
        if (isOutgoing) {
            -direction * 0.08f * (1f - progress)
        } else {
            direction * progress
        }
    val alpha =
        if (isOutgoing) {
            1f - 0.18f * (1f - progress)
        } else {
            1f
        }

    key("stack-animation:${child.key}:${if (isOutgoing) "out" else "in"}") {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (isOutgoing) 0f else 1f)
                .graphicsLayer {
                    translationX = size.width * translationFactor
                    this.alpha = alpha
                },
        ) {
            content(child)
        }
    }
}

private data class StackTransition<C : Any, T : Any>(
    val oldActive: Child.Created<C, T>,
    val newActive: Child.Created<C, T>,
    val isPop: Boolean,
)