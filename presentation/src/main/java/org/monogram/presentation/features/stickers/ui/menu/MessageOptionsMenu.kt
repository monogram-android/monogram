package org.monogram.presentation.features.stickers.ui.menu

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Forward
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageViewerModel
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.features.chats.currentChat.chatContent.DeleteMessagesSheet
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.chats.currentChat.components.chats.getEmojiFontFamily
import org.monogram.presentation.features.stickers.ui.view.StickerImage
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MessageOptionsMenu(
    message: MessageModel,
    canWrite: Boolean,
    canPinMessages: Boolean,
    isPinned: Boolean,
    messageOffset: Offset,
    messageSize: IntSize,
    clickOffset: Offset,
    contentRect: Rect,
    isSameSenderAbove: Boolean = false,
    isSameSenderBelow: Boolean = false,
    isMessageOutgoing: Boolean = message.isOutgoing,
    showReadInfo: Boolean = true,
    showViewsInfo: Boolean = true,
    showViewersList: Boolean = false,
    canReport: Boolean = true,
    canBlock: Boolean = false,
    canRestrict: Boolean = false,
    canCopyLink: Boolean = true,
    viewers: List<MessageViewerModel> = emptyList(),
    isLoadingViewers: Boolean = false,
    onReloadViewers: () -> Unit = {},
    onViewerClick: (Long) -> Unit = {},
    videoPlayerPool: VideoPlayerPool,
    bubbleRadius: Float = 18f,
    splitOffset: Int? = null,
    onReply: () -> Unit,
    onPin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (Boolean) -> Unit,
    onForward: () -> Unit,
    onSelect: () -> Unit = {},
    onCopyLink: () -> Unit,
    onCopy: () -> Unit,
    onSaveToDownloads: () -> Unit = {},
    onReaction: (String) -> Unit = {},
    onComments: () -> Unit = {},
    onReport: () -> Unit = {},
    onBlock: () -> Unit = {},
    onRestrict: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    with(density) { configuration.screenWidthDp.dp.toPx() }.toInt()
    val screenHeight = with(density) { configuration.screenHeightDp.dp.toPx() }.toInt()
    val windowInsets = WindowInsets.systemBars.union(WindowInsets.ime)
    val topInset = windowInsets.getTop(density)
    val bottomInset = windowInsets.getBottom(density)

    val horizontalMargin = with(density) { 16.dp.toPx() }.toInt()
    val verticalPadding = with(density) { 8.dp.toPx() }.toInt()

    val scale = remember { Animatable(0.6f) }
    val alpha = remember { Animatable(0f) }
    val dimAlpha = remember { Animatable(0f) }
    val runtimeContentScale = remember { Animatable(1f) }

    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    var firstScreenWidth by remember { mutableStateOf<Int?>(null) }
    var containerOffset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var hasTrackedViewersState by remember(message.id) { mutableStateOf(false) }
    var lastTrackedViewersCount by remember(message.id) { mutableIntStateOf(viewers.size) }
    var lastTrackedLoadingState by remember(message.id) { mutableStateOf(isLoadingViewers) }
    var hasTrackedReactions by remember(message.id) { mutableStateOf(false) }
    var lastTrackedReactionCount by remember(message.id) { mutableIntStateOf(0) }
    var hasReactionsInMessage by remember(message.id) { mutableStateOf(false) }
    var suppressNextReactionsAppearanceAnimation by remember(message.id) { mutableStateOf(false) }

    fun animateRuntimeContentScale() {
        scope.launch {
            runtimeContentScale.stop()
            runtimeContentScale.snapTo(0.97f)
            runtimeContentScale.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    fun animateOutAndDismiss(action: (() -> Unit)? = null) {
        scope.launch {
            launch { scale.animateTo(0.95f, tween(150)) }
            launch { alpha.animateTo(0f, tween(100)) }
            launch { dimAlpha.animateTo(0f, tween(150)) }
            delay(75)
            action?.invoke()
            onDismiss()
        }
    }

    val maxMenuHeight = remember(screenHeight, topInset, bottomInset) {
        with(density) {
            (screenHeight - topInset - bottomInset - 2 * verticalPadding).toFloat().toDp().coerceAtLeast(100.dp)
        }
    }

    val menuPosition by remember(
        menuSize,
        messageOffset,
        messageSize,
        clickOffset,
        containerSize,
        containerOffset,
        topInset,
        bottomInset
    ) {
        derivedStateOf {
            if (menuSize == IntSize.Zero || containerSize == IntSize.Zero) return@derivedStateOf IntOffset.Zero

            var x = (clickOffset.x - (menuSize.width / 2)).toInt()
            val minX = containerOffset.x.toInt() + horizontalMargin
            val maxX = containerOffset.x.toInt() + containerSize.width - menuSize.width - horizontalMargin
            x = x.coerceIn(minX, maxX)

            var y = (clickOffset.y - (menuSize.height / 2)).toInt()
            val minY = maxOf(containerOffset.y.toInt() + verticalPadding, topInset + verticalPadding)
            val maxY = minOf(
                containerOffset.y.toInt() + containerSize.height - menuSize.height - verticalPadding,
                screenHeight - bottomInset - menuSize.height - verticalPadding
            )
            y = y.coerceIn(minY, maxY)

            IntOffset(x - containerOffset.x.toInt(), y - containerOffset.y.toInt())
        }
    }

    val transformOrigin by remember(menuPosition, menuSize, clickOffset, containerOffset) {
        derivedStateOf {
            if (menuSize == IntSize.Zero) return@derivedStateOf TransformOrigin.Center

            val relativeClickX = clickOffset.x - containerOffset.x
            val relativeClickY = clickOffset.y - containerOffset.y

            val pivotX = ((relativeClickX - menuPosition.x) / menuSize.width).coerceIn(0f, 1f)
            val pivotY = ((relativeClickY - menuPosition.y) / menuSize.height).coerceIn(0f, 1f)
            TransformOrigin(pivotX, pivotY)
        }
    }

    LaunchedEffect(Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        launch {
            scale.animateTo(
                1f,
                spring(dampingRatio = 0.75f, stiffness = 400f)
            )
        }
        launch { alpha.animateTo(1f, tween(150, easing = LinearOutSlowInEasing)) }
        launch { dimAlpha.animateTo(1f, tween(200)) }
    }

    LaunchedEffect(isLoadingViewers, viewers.size) {
        if (hasTrackedViewersState &&
            (lastTrackedLoadingState != isLoadingViewers || lastTrackedViewersCount != viewers.size)
        ) {
            animateRuntimeContentScale()
        }

        hasTrackedViewersState = true
        lastTrackedLoadingState = isLoadingViewers
        lastTrackedViewersCount = viewers.size
    }

    var showDeleteSheet by remember { mutableStateOf(false) }
    var menuPage by remember { mutableStateOf(MenuPage.Main) }

    if (showDeleteSheet) {
        DeleteMessagesSheet(
            count = 1,
            canRevoke = message.canBeDeletedForAllUsers,
            onDismiss = { showDeleteSheet = false },
            onDelete = { revoke ->
                animateOutAndDismiss { onDelete(revoke) }
                showDeleteSheet = false
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f)
            .onGloballyPositioned { coordinates ->
                containerOffset = coordinates.positionInWindow()
                containerSize = coordinates.size
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { animateOutAndDismiss() }
            )
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                val alphaVal = dimAlpha.value
                if (alphaVal > 0f) {
                    drawRect(Color.Black.copy(alpha = 0.45f * alphaVal))
                    clipRect(
                        left = contentRect.left - containerOffset.x,
                        top = contentRect.top - containerOffset.y,
                        right = contentRect.right - containerOffset.x,
                        bottom = contentRect.bottom - containerOffset.y
                    ) {
                        val r = bubbleRadius.dp.toPx()
                        val s = (bubbleRadius / 4f).coerceAtLeast(4f).dp.toPx()
                        val t = 2.dp.toPx()
                        val path = Path().apply {
                            if (splitOffset != null && splitOffset > 0 && splitOffset < messageSize.height) {
                                val gap = 2.dp.toPx()
                                val topHeight = splitOffset.toFloat()
                                val bottomHeight = messageSize.height - topHeight - gap
                                val hasBottom = bottomHeight > 0

                                val currentGap = if (hasBottom) 0f else gap

                                // Top rect (Media)
                                addRoundRect(
                                    RoundRect(
                                        rect = Rect(
                                            offset = messageOffset - containerOffset,
                                            size = Size(messageSize.width.toFloat(), topHeight)
                                        ),
                                        topLeft = CornerRadius(if (!isMessageOutgoing && isSameSenderAbove) s else r),
                                        topRight = CornerRadius(if (isMessageOutgoing && isSameSenderAbove) s else r),
                                        bottomRight = if (hasBottom) CornerRadius.Zero else CornerRadius(if (isMessageOutgoing) s else r),
                                        bottomLeft = if (hasBottom) CornerRadius.Zero else CornerRadius(if (!isMessageOutgoing) s else r)
                                    )
                                )

                                // Bottom rect (Text)
                                if (hasBottom) {
                                    addRoundRect(
                                        RoundRect(
                                            rect = Rect(
                                                offset = messageOffset - containerOffset + Offset(
                                                    0f,
                                                    topHeight + currentGap
                                                ),
                                                size = Size(messageSize.width.toFloat(), bottomHeight)
                                            ),
                                            topLeft = CornerRadius.Zero,
                                            topRight = CornerRadius.Zero,
                                            bottomRight = CornerRadius(if (isMessageOutgoing) (if (isSameSenderBelow) s else t) else r),
                                            bottomLeft = CornerRadius(if (!isMessageOutgoing) (if (isSameSenderBelow) s else t) else r)
                                        )
                                    )
                                }
                            } else {
                                addRoundRect(
                                    RoundRect(
                                        rect = Rect(
                                            offset = messageOffset - containerOffset,
                                            size = Size(messageSize.width.toFloat(), messageSize.height.toFloat())
                                        ),
                                        topLeft = CornerRadius(if (!isMessageOutgoing && isSameSenderAbove) s else r),
                                        topRight = CornerRadius(if (isMessageOutgoing && isSameSenderAbove) s else r),
                                        bottomRight = CornerRadius(if (isMessageOutgoing) (if (isSameSenderBelow) s else t) else r),
                                        bottomLeft = CornerRadius(if (!isMessageOutgoing) (if (isSameSenderBelow) s else t) else r)
                                    )
                                )
                            }
                        }
                        drawPath(path, Color.Black, blendMode = BlendMode.DstOut)
                    }
                }
                drawContent()
            }
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { menuPosition }
                .width(IntrinsicSize.Min)
                .widthIn(min = 180.dp, max = 240.dp)
                .heightIn(max = maxMenuHeight)
                .graphicsLayer {
                    this.alpha = if (menuSize == IntSize.Zero || containerSize == IntSize.Zero) 0f else alpha.value

                    val currentScale = scale.value * runtimeContentScale.value
                    scaleX = currentScale
                    scaleY = currentScale
                    this.transformOrigin = transformOrigin
                    shadowElevation = 16.dp.toPx()
                    shape = RoundedCornerShape(16.dp)
                    clip = true
                }
                .onGloballyPositioned { coordinates ->
                    if (menuSize != coordinates.size) {
                        menuSize = coordinates.size
                    }
                }
                .clickable(enabled = false) {},
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 6.dp,
            shape = RoundedCornerShape(16.dp)
        ) {
            AnimatedContent(
                targetState = menuPage,
                transitionSpec = {
                    val duration = 300
                    val easing = FastOutSlowInEasing
                    val forward = targetState.ordinal > initialState.ordinal
                    if (forward) {
                        (slideInHorizontally(animationSpec = tween(duration, easing = easing)) { width -> width / 2 } +
                                fadeIn(animationSpec = tween(duration, easing = easing)))
                            .togetherWith(
                                slideOutHorizontally(
                                    animationSpec = tween(
                                        duration,
                                        easing = easing
                                    )
                                ) { width -> -width / 2 } +
                                        fadeOut(animationSpec = tween(duration, easing = easing)))
                    } else {
                        (slideInHorizontally(animationSpec = tween(duration, easing = easing)) { width -> -width / 2 } +
                                fadeIn(animationSpec = tween(duration, easing = easing)))
                            .togetherWith(
                                slideOutHorizontally(
                                    animationSpec = tween(
                                        duration,
                                        easing = easing
                                    )
                                ) { width -> width / 2 } +
                                        fadeOut(animationSpec = tween(duration, easing = easing)))
                    }.using(
                        SizeTransform(
                            clip = false,
                            sizeAnimationSpec = { _, _ ->
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            }
                        )
                    )
                },
                label = "MenuTransition"
            ) { page ->
                val isSubPage = page != MenuPage.Main
                val contentModifier = if (isSubPage && firstScreenWidth != null) {
                    Modifier.width(with(density) { firstScreenWidth!!.toDp() })
                } else {
                    Modifier.onGloballyPositioned { coords ->
                        if (page == MenuPage.Main) {
                            firstScreenWidth = coords.size.width
                        }
                    }
                }

                Column(
                    modifier = contentModifier
                        .animateContentSize(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                        .padding(vertical = 4.dp)
                ) {
                    if (page == MenuPage.Main) {
                        ReactionsRow(
                            message = message,
                            suppressAppearanceAnimation = suppressNextReactionsAppearanceAnimation,
                            onAppearanceAnimationConsumed = {
                                suppressNextReactionsAppearanceAnimation = false
                            },
                            onReactionsChanged = { reactionCount ->
                                hasReactionsInMessage = reactionCount > 0
                                if (hasTrackedReactions && lastTrackedReactionCount != reactionCount) {
                                    animateRuntimeContentScale()
                                }
                                hasTrackedReactions = true
                                lastTrackedReactionCount = reactionCount
                            },
                            onReaction = { reaction ->
                                animateOutAndDismiss { onReaction(reaction) }
                            }
                        )

                        InternalMenuHeaderInfo(
                            message = message,
                            showReadInfo = showReadInfo,
                            showViewsInfo = showViewsInfo
                        )

                        if (showViewersList) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.Visibility,
                                text = "${viewers.size} ${stringResource(R.string.info_views)}",
                                trailingContent = {
                                    if (isLoadingViewers) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.ChevronRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onReloadViewers()
                                    menuPage = MenuPage.Viewers
                                }
                            )
                        }

                        if (canWrite) {
                            InternalMenuOptionItem(
                                icon = Icons.AutoMirrored.Rounded.Reply,
                                text = stringResource(R.string.menu_reply),
                                onClick = { animateOutAndDismiss(onReply) }
                            )
                        }

                        if (canPinMessages) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.PushPin,
                                text = if (isPinned) stringResource(R.string.menu_unpin) else stringResource(R.string.menu_pin),
                                onClick = { animateOutAndDismiss(onPin) }
                            )
                        }

                        if (message.canBeEdited) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.Edit,
                                text = stringResource(R.string.menu_edit),
                                onClick = { animateOutAndDismiss(onEdit) }
                            )
                        }

                        if (shouldShowCopy(message)) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.ContentCopy,
                                text = stringResource(R.string.menu_copy),
                                onClick = { animateOutAndDismiss(onCopy) }
                            )
                        }

                        if (message.canBeForwarded) {
                            InternalMenuOptionItem(
                                icon = Icons.AutoMirrored.Rounded.Forward,
                                text = stringResource(R.string.menu_forward),
                                onClick = { animateOutAndDismiss(onForward) }
                            )
                        }

                        InternalMenuOptionItem(
                            icon = Icons.Rounded.CheckCircle,
                            text = stringResource(R.string.menu_select),
                            onClick = { animateOutAndDismiss(onSelect) }
                        )

                        InternalMenuOptionItem(
                            icon = Icons.Rounded.MoreHoriz,
                            text = stringResource(R.string.menu_more),
                            trailingIcon = Icons.Rounded.ChevronRight,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuPage = MenuPage.More
                            }
                        )

                        if (message.canBeDeletedOnlyForSelf || message.canBeDeletedForAllUsers) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.Delete,
                                text = stringResource(R.string.menu_delete),
                                textColor = MaterialTheme.colorScheme.error,
                                iconTint = MaterialTheme.colorScheme.error,
                                onClick = { showDeleteSheet = true }
                            )
                        }
                    } else if (page == MenuPage.More) {
                        InternalMenuOptionItem(
                            icon = Icons.AutoMirrored.Rounded.ArrowBack,
                            text = stringResource(R.string.cd_back),
                            iconTint = MaterialTheme.colorScheme.primary,
                            textColor = MaterialTheme.colorScheme.primary,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (hasReactionsInMessage) {
                                    suppressNextReactionsAppearanceAnimation = true
                                }
                                menuPage = MenuPage.Main
                            }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        if (message.canGetMessageThread) {
                            InternalMenuOptionItem(
                                icon = Icons.AutoMirrored.Rounded.Chat,
                                text = stringResource(R.string.menu_view_comments),
                                onClick = { animateOutAndDismiss(onComments) }
                            )
                        }

                        if (canCopyLink) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.Link,
                                text = stringResource(R.string.menu_copy_link),
                                onClick = { animateOutAndDismiss(onCopyLink) }
                            )
                        }

                        if (shouldShowDownload(message)) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.Download,
                                text = stringResource(R.string.menu_save_to_downloads),
                                onClick = { animateOutAndDismiss(onSaveToDownloads) }
                            )
                        }

                        if (canReport) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.Report,
                                text = stringResource(R.string.menu_report),
                                onClick = { animateOutAndDismiss(onReport) }
                            )
                        }

                        if (canBlock) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.Block,
                                text = stringResource(R.string.menu_block_user),
                                textColor = MaterialTheme.colorScheme.error,
                                iconTint = MaterialTheme.colorScheme.error,
                                onClick = { animateOutAndDismiss(onBlock) }
                            )
                            if (canRestrict) {
                                InternalMenuOptionItem(
                                    icon = Icons.Rounded.Gavel,
                                    text = stringResource(R.string.menu_restrict_user),
                                    textColor = MaterialTheme.colorScheme.error,
                                    iconTint = MaterialTheme.colorScheme.error,
                                    onClick = { animateOutAndDismiss(onRestrict) }
                                )
                            }
                        }
                    } else {
                        InternalMenuOptionItem(
                            icon = Icons.AutoMirrored.Rounded.ArrowBack,
                            text = stringResource(R.string.viewer_back),
                            iconTint = MaterialTheme.colorScheme.primary,
                            textColor = MaterialTheme.colorScheme.primary,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (hasReactionsInMessage) {
                                    suppressNextReactionsAppearanceAnimation = true
                                }
                                menuPage = MenuPage.Main
                            }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        when {
                            isLoadingViewers -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }

                            viewers.isEmpty() -> {
                                Text(
                                    text = stringResource(R.string.info_views),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            else -> {
                                val viewerDateFormat =
                                    remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
                                val scrollState = rememberScrollState()
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 260.dp)
                                        .verticalScroll(scrollState)
                                ) {
                                    viewers.forEach { viewer ->
                                        ViewerRow(
                                            viewer = viewer,
                                            dateFormat = viewerDateFormat,
                                            videoPlayerPool = videoPlayerPool,
                                            onClick = {
                                                animateOutAndDismiss {
                                                    onViewerClick(viewer.user.id)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class MenuPage {
    Main,
    More,
    Viewers
}

@Composable
private fun ReactionsRow(
    message: MessageModel,
    suppressAppearanceAnimation: Boolean,
    onAppearanceAnimationConsumed: () -> Unit,
    onReactionsChanged: (Int) -> Unit,
    onReaction: (String) -> Unit,
    stickerRepository: StickerRepository = koinInject(),
    appPreferences: AppPreferences = koinInject()
) {
    val haptic = LocalHapticFeedback.current

    val context = LocalContext.current
    val emojiStyle by appPreferences.emojiStyle.collectAsState()
    val emojiFontFamily = remember(context, emojiStyle) { getEmojiFontFamily(context, emojiStyle) }
    var availableReactions by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(message.chatId, message.id) {
        availableReactions = stickerRepository.getMessageAvailableReactions(message.chatId, message.id)
    }

    val reactions = remember(availableReactions) {
        if (availableReactions.isNotEmpty()) {
            availableReactions.map { RecentEmojiModel(it) }
        } else {
            emptyList()
        }
    }

    LaunchedEffect(reactions.size) {
        onReactionsChanged(reactions.size)
    }

    LaunchedEffect(suppressAppearanceAnimation, reactions.isNotEmpty()) {
        if (suppressAppearanceAnimation && reactions.isNotEmpty()) {
            onAppearanceAnimationConsumed()
        }
    }

    AnimatedVisibility(
        visible = reactions.isNotEmpty(),
        enter = if (suppressAppearanceAnimation) {
            EnterTransition.None
        } else {
            fadeIn(animationSpec = tween(180, easing = LinearOutSlowInEasing)) +
                    expandVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) +
                    scaleIn(
                        initialScale = 0.96f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    )
        },
        exit = fadeOut(animationSpec = tween(120)) +
                shrinkVertically(animationSpec = tween(120)) +
                scaleOut(targetScale = 0.96f, animationSpec = tween(120)),
        label = "ReactionsRowVisibility"
    ) {
        Column {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                reactions.forEach { reaction ->
                    val isChosen = message.reactions.any { it.isChosen && it.emoji == reaction.emoji }

                    val backgroundColor by animateColorAsState(
                        targetValue = if (isChosen) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        animationSpec = spring(stiffness = Spring.StiffnessLow),
                        label = "reactionBg"
                    )

                    val scale by animateFloatAsState(
                        targetValue = if (isChosen) 1.1f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        ),
                        label = "reactionScale"
                    )

                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .clip(CircleShape)
                            .background(backgroundColor)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onReaction(reaction.emoji)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val sticker = reaction.sticker
                        if (sticker != null) {
                            StickerImage(
                                path = sticker.path,
                                modifier = Modifier.size(28.dp),
                            )
                        } else {
                            Text(
                                text = reaction.emoji,
                                fontSize = 24.sp,
                                fontFamily = emojiFontFamily
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun ViewerRow(
    viewer: MessageViewerModel,
    dateFormat: SimpleDateFormat,
    videoPlayerPool: VideoPlayerPool,
    onClick: () -> Unit
) {
    val fullName = remember(viewer.user.firstName, viewer.user.lastName) {
        listOfNotNull(viewer.user.firstName, viewer.user.lastName)
            .joinToString(" ")
            .ifBlank { "Unknown" }
    }
    val subtitle = remember(viewer.viewedDate) {
        if (viewer.viewedDate > 0) {
            dateFormat.format(Date(viewer.viewedDate.toLong() * 1000))
        } else {
            ""
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(
            path = viewer.user.avatarPath,
            fallbackPath = viewer.user.personalAvatarPath,
            name = fullName,
            size = 32.dp,
            videoPlayerPool = videoPlayerPool,
            fontSize = 12,
            onClick = onClick
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fullName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InternalMenuHeaderInfo(
    message: MessageModel,
    showReadInfo: Boolean,
    showViewsInfo: Boolean
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    val editDate = if (message.editDate > 0) dateFormat.format(Date(message.editDate.toLong() * 1000)) else null
    val readDate = if (showReadInfo)
        if (message.isOutgoing && message.readDate > 0) dateFormat.format(Date(message.readDate.toLong() * 1000)) else null
    else null
    val views = if (showViewsInfo && message.views != null && message.views!! > 0) message.views.toString() else null

    val hasHeader = editDate != null || readDate != null || views != null

    if (hasHeader) {
        Column {
            if (editDate != null) {
                InternalMenuInfoRow(
                    icon = Icons.Rounded.Edit,
                    label = stringResource(R.string.info_edited),
                    value = editDate
                )
            }

            if (readDate != null) {
                InternalMenuInfoRow(
                    icon = Icons.Rounded.DoneAll,
                    label = stringResource(R.string.info_read),
                    value = readDate,
                    iconTint = MaterialTheme.colorScheme.primary
                )
            }

            if (views != null) {
                InternalMenuInfoRow(
                    icon = Icons.Rounded.Visibility,
                    label = stringResource(R.string.info_views),
                    value = views
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun InternalMenuInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = iconTint
        )
        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun InternalMenuOptionItem(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    trailingIcon: ImageVector? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            modifier = Modifier.weight(1f)
        )
        if (trailingContent != null) {
            trailingContent()
        } else if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = iconTint.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun shouldShowCopy(message: MessageModel): Boolean {
    return when (val content = message.content) {
        is MessageContent.Text -> content.text.isNotEmpty()
        is MessageContent.Photo -> content.caption.isNotEmpty()
        is MessageContent.Video -> content.caption.isNotEmpty()
        is MessageContent.Gif -> content.caption.isNotEmpty()
        else -> false
    }
}

private fun shouldShowDownload(message: MessageModel): Boolean {
    if (!message.canBeSaved) return false
    return when (val content = message.content) {
        is MessageContent.Photo -> content.path != null
        is MessageContent.Video -> content.path != null
        is MessageContent.Gif -> content.path != null
        is MessageContent.Document -> content.path != null
        is MessageContent.Voice -> content.path != null
        is MessageContent.VideoNote -> content.path != null
        else -> false
    }
}
