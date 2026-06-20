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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.zIndex
import com.arkivanov.decompose.Child
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.StackAnimation
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.decompose.router.stack.ChildStack
import kotlinx.coroutines.launch
import org.monogram.presentation.core.ui.ScreenSwipeBackAction
import org.monogram.presentation.core.ui.ScreenSwipeBackState
import org.monogram.presentation.features.chats.conversation.ChatRenderMode
import org.monogram.presentation.features.chats.list.ChatListPreviewMode
import org.monogram.presentation.root.RootComponent
import kotlin.math.abs

@OptIn(ExperimentalDecomposeApi::class)
@Composable
fun MobileLayout(root: RootComponent) {
    val stack by root.childStack.subscribeAsState()
    val isDragToBackEnabled by root.appPreferences.isDragToBackEnabled.collectAsState()
    val showAllChatsFolder by root.appPreferences.showAllChatsFolder.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val activeEntry = stack.active
    val previousEntry = stack.items.dropLast(1).lastOrNull()
    val stackKeysAreUnique = stack.items.map { it.key }.toSet().size == stack.items.size
    val canRenderStackPreview =
        stackKeysAreUnique &&
                previousEntry != null &&
                previousEntry.key != activeEntry.key &&
                previousEntry.instance !== activeEntry.instance
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var isCompletingSwipeBack by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var swipeBackActiveKey by remember { mutableStateOf<Any?>(null) }
    var suppressNextPopAnimation by remember { mutableStateOf(false) }
    var activeScreenSwipeBackState by remember { mutableStateOf(ScreenSwipeBackState()) }
    val archivePreviewMode = resolveArchivePreviewMode(activeEntry.instance, showAllChatsFolder)
    val archivePreviewFolderId =
        (archivePreviewMode as? ChatListPreviewMode.FolderPreview)?.folderId
    val swipeBackResolution = resolveSwipeBack(
        child = activeEntry.instance,
        screenSwipeBackState = activeScreenSwipeBackState,
        archivePreviewFolderId = archivePreviewFolderId,
    )
    val canRenderLocalPreview =
        swipeBackResolution.preview == SwipeBackPreviewDescriptor.ChatForumList ||
                swipeBackResolution.preview is SwipeBackPreviewDescriptor.ChatListFolder
    val canUseDragToBack =
        isDragToBackEnabled &&
                stackKeysAreUnique &&
                swipeBackResolution.isSupported &&
                !swipeBackResolution.isBlocked &&
                (previousEntry != null || canRenderLocalPreview)
    val dragProgress = if (widthPx > 0f) (dragOffsetX / widthPx).coerceIn(0f, 1f) else 0f

    LaunchedEffect(activeEntry.key, stackKeysAreUnique) {
        activeScreenSwipeBackState = ScreenSwipeBackState()
        swipeBackActiveKey = null
        if (!stackKeysAreUnique) {
            dragOffsetX = 0f
            isCompletingSwipeBack = false
            suppressNextPopAnimation = false
        }
    }

    LaunchedEffect(canUseDragToBack) {
        if (!canUseDragToBack && dragOffsetX > 0f) {
            dragOffsetX = 0f
            isCompletingSwipeBack = false
            swipeBackActiveKey = null
        }
    }

    val isSwipeBackActive =
        dragOffsetX > 0f &&
                (canRenderStackPreview || canRenderLocalPreview) &&
                swipeBackActiveKey == activeEntry.key
    val retainedPreviousKey =
        if (
            swipeBackResolution.preview == SwipeBackPreviewDescriptor.PreviousStackEntry &&
            stackKeysAreUnique &&
            previousEntry != null &&
            previousEntry.key != activeEntry.key
        ) {
            previousEntry.key
        } else {
            null
        }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                widthPx = it.width.toFloat()
            }
            .then(
                if (canUseDragToBack) {
                    Modifier.pointerInput(canUseDragToBack, activeEntry.key, swipeBackResolution) {
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
                                                commitSwipeBack(
                                                    root = root,
                                                    activeChild = activeEntry.instance,
                                                    resolution = swipeBackResolution,
                                                    onSuppressStackPopAnimation = {
                                                        suppressNextPopAnimation = true
                                                    },
                                                )
                                                dragOffsetX = 0f
                                                isCompletingSwipeBack = false
                                                swipeBackActiveKey = null
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

                                    if (!canRenderStackPreview && !canRenderLocalPreview) {
                                        dragOffsetX = 0f
                                        break
                                    }

                                    isDragging = true
                                    swipeBackActiveKey = activeEntry.key
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
                                    swipeBackActiveKey = null
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            ),
    ) {
        if (isSwipeBackActive && swipeBackResolution.preview != SwipeBackPreviewDescriptor.PreviousStackEntry) {
            Box(
                modifier = Modifier.mobileStackLayerModifier(
                    role = StackLayerRole.LocalPreview,
                    dragOffsetX = dragOffsetX,
                    dragProgress = dragProgress,
                    transitionProgress = 1f,
                    isPop = false,
                ),
            ) {
                RenderChild(
                    child = activeEntry.instance,
                    chatRenderModeOverride = when (swipeBackResolution.preview) {
                        SwipeBackPreviewDescriptor.ChatForumList -> ChatRenderMode.ForumTopicSwipePreview
                        else -> null
                    },
                    chatListPreviewMode = when (val preview = swipeBackResolution.preview) {
                        is SwipeBackPreviewDescriptor.ChatListFolder -> ChatListPreviewMode.FolderPreview(
                            preview.folderId
                        )

                        else -> ChatListPreviewMode.Active
                    },
                )
            }
        }

        Children(
            stack = stack,
            animation = mobileStackAnimation(
                dragOffsetX = dragOffsetX,
                dragProgress = dragProgress,
                isSwipeBackActive = isSwipeBackActive,
                stackKeysAreUnique = stackKeysAreUnique,
                showRetainedPreviousChild = swipeBackResolution.preview == SwipeBackPreviewDescriptor.PreviousStackEntry,
                showLocalPreviewBackdrop = swipeBackResolution.preview != SwipeBackPreviewDescriptor.PreviousStackEntry,
                suppressNextPopAnimation = suppressNextPopAnimation,
                onSuppressNextPopAnimationConsumed = {
                    suppressNextPopAnimation = false
                },
            ),
        ) { child ->
            val layerRole = resolveSwipeLayerRole(
                child = child,
                activeKey = activeEntry.key,
                retainedPreviousKey = retainedPreviousKey,
            )

            RenderChild(
                child = child.instance,
                isOverlay = layerRole == StackLayerRole.RetainedPrevious,
                chatRenderModeOverride = when (layerRole) {
                    StackLayerRole.RetainedPrevious -> ChatRenderMode.SwipePreview
                    else -> null
                },
                chatListPreviewMode = ChatListPreviewMode.Active,
                onScreenSwipeBackStateChanged = { swipeState ->
                    if (stack.active.instance === child.instance) {
                        activeScreenSwipeBackState = swipeState
                    }
                },
            )
        }
    }
}

private fun resolveSwipeLayerRole(
    child: Child.Created<*, RootComponent.Child>,
    activeKey: Any,
    retainedPreviousKey: Any?,
): StackLayerRole? {
    return when {
        child.key == retainedPreviousKey -> StackLayerRole.RetainedPrevious
        child.key == activeKey -> StackLayerRole.Active
        else -> null
    }
}

private fun commitSwipeBack(
    root: RootComponent,
    activeChild: RootComponent.Child,
    resolution: SwipeBackResolution,
    onSuppressStackPopAnimation: () -> Unit,
) {
    when (resolution.action) {
        ScreenSwipeBackAction.StackPop -> {
            onSuppressStackPopAnimation()
            when (activeChild) {
                is RootComponent.Child.ChatDetailChild -> activeChild.component.onBackClicked()
                else -> root.onBack()
            }
        }

        ScreenSwipeBackAction.LocalChatTopicClose -> {
            when (activeChild) {
                is RootComponent.Child.ChatDetailChild -> activeChild.component.onTopicClick(0)
                else -> root.onBack()
            }
        }

        ScreenSwipeBackAction.LocalChatListArchiveReturn -> {
            when (activeChild) {
                is RootComponent.Child.ChatsChild -> activeChild.component.handleBack()
                else -> root.onBack()
            }
        }
    }
}

@Composable
private fun <C : Any, T : Any> mobileStackAnimation(
    dragOffsetX: Float,
    dragProgress: Float,
    isSwipeBackActive: Boolean,
    stackKeysAreUnique: Boolean,
    showRetainedPreviousChild: Boolean,
    showLocalPreviewBackdrop: Boolean,
    suppressNextPopAnimation: Boolean,
    onSuppressNextPopAnimationConsumed: () -> Unit,
): StackAnimation<C, T> {
    return StackAnimation { stack, modifier, content ->
        var previousStack by remember { mutableStateOf<ChildStack<C, T>?>(null) }
        var transition by remember { mutableStateOf<StackTransition<C, T>?>(null) }
        val oldStack = previousStack
        var shouldConsumeSuppressedPop = false

        if (oldStack == null) {
            previousStack = stack
        } else if (oldStack.active.key != stack.active.key) {
            val oldActive = oldStack.active
            val newActive = stack.active
            val isPop = stack.items.size < oldStack.items.size
            val shouldSuppressPop = suppressNextPopAnimation && isPop
            transition =
                if (!shouldSuppressPop && oldActive.key != newActive.key) {
                    StackTransition(
                        oldActive = oldActive,
                        newActive = newActive,
                        isPop = isPop,
                    )
                } else {
                    null
                }
            shouldConsumeSuppressedPop = shouldSuppressPop
            previousStack = stack
        }

        if (shouldConsumeSuppressedPop) {
            LaunchedEffect(stack.active.key) {
                onSuppressNextPopAnimationConsumed()
            }
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
        val retainedPreviousChild = if (stackKeysAreUnique && showRetainedPreviousChild) {
            stack.items.dropLast(1).lastOrNull()
        } else {
            null
        }?.takeIf { it.key != stack.active.key }

        val layers =
            if (!isSwipeBackActive &&
                currentTransition != null &&
                currentTransition.oldActive.key != currentTransition.newActive.key
            ) {
                listOf(
                    StackLayer(
                        child = currentTransition.oldActive,
                        role = StackLayerRole.TransitionOutgoing,
                        isPop = currentTransition.isPop,
                    ),
                    StackLayer(
                        child = currentTransition.newActive,
                        role = StackLayerRole.TransitionIncoming,
                        isPop = currentTransition.isPop,
                    ),
                )
            } else {
                buildList {
                    if (retainedPreviousChild != null) {
                        add(
                            StackLayer(
                                child = retainedPreviousChild,
                                role = StackLayerRole.RetainedPrevious,
                            ),
                        )
                    }
                    add(
                        StackLayer(
                            child = stack.active,
                            role = StackLayerRole.Active,
                        ),
                    )
                }
            }

        Box(modifier = modifier) {
            layers.forEach { layer ->
                key("${layer.child.key}:${layer.role}") {
                    StackLayerChild(
                        layer = layer,
                        dragOffsetX = if (isSwipeBackActive) dragOffsetX else 0f,
                        dragProgress = dragProgress,
                        transitionProgress = animationProgress,
                        content = content,
                    )
                }
            }

            if (isSwipeBackActive &&
                (retainedPreviousChild != null || showLocalPreviewBackdrop)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(0.5f)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                        .background(
                            Color.Black.copy(
                                alpha = 0.3f * (1f - dragProgress),
                            ),
                        ),
                )
            }
        }
    }
}

@Composable
private fun <C : Any, T : Any> StackLayerChild(
    layer: StackLayer<C, T>,
    dragOffsetX: Float,
    dragProgress: Float,
    transitionProgress: Float,
    content: @Composable (child: Child.Created<C, T>) -> Unit,
) {
    Box(
        modifier = Modifier.mobileStackLayerModifier(
            role = layer.role,
            dragOffsetX = dragOffsetX,
            dragProgress = dragProgress,
            transitionProgress = transitionProgress,
            isPop = layer.isPop,
        ),
    ) {
        content(layer.child)
    }
}

private fun Modifier.mobileStackLayerModifier(
    role: StackLayerRole,
    dragOffsetX: Float,
    dragProgress: Float,
    transitionProgress: Float,
    isPop: Boolean,
): Modifier =
    fillMaxSize()
        .then(
            if (role == StackLayerRole.RetainedPrevious || role == StackLayerRole.LocalPreview) {
                Modifier.clearAndSetSemantics {}
            } else {
                Modifier
            },
        )
        .zIndex(
            when (role) {
                StackLayerRole.Active,
                StackLayerRole.TransitionIncoming -> 1f

                StackLayerRole.LocalPreview,
                StackLayerRole.RetainedPrevious,
                StackLayerRole.TransitionOutgoing -> 0f
            },
        )
        .graphicsLayer {
            when (role) {
                StackLayerRole.Active -> {
                    translationX = dragOffsetX
                    shadowElevation = if (dragOffsetX > 0f) 12f else 0f
                }

                StackLayerRole.RetainedPrevious,
                StackLayerRole.LocalPreview -> {
                    translationX =
                        if (dragOffsetX > 0f) {
                            (dragProgress - 1f) * size.width * 0.08f
                        } else {
                            -size.width
                        }
                    alpha = if (dragOffsetX > 0f) 1f else 0f
                }

                StackLayerRole.TransitionOutgoing,
                StackLayerRole.TransitionIncoming -> {
                    val isOutgoing = role == StackLayerRole.TransitionOutgoing
                    val direction = if (isPop) -1f else 1f
                    val translationFactor =
                        if (isOutgoing) {
                            -direction * 0.08f * (1f - transitionProgress)
                        } else {
                            direction * transitionProgress
                        }
                    translationX = size.width * translationFactor
                    alpha =
                        if (isOutgoing) {
                            1f - 0.18f * (1f - transitionProgress)
                        } else {
                            1f
                        }
                }
            }
        }

private data class StackTransition<C : Any, T : Any>(
    val oldActive: Child.Created<C, T>,
    val newActive: Child.Created<C, T>,
    val isPop: Boolean,
)

private data class StackLayer<C : Any, T : Any>(
    val child: Child.Created<C, T>,
    val role: StackLayerRole,
    val isPop: Boolean = false,
)

private enum class StackLayerRole {
    Active,
    LocalPreview,
    RetainedPrevious,
    TransitionOutgoing,
    TransitionIncoming,
}
