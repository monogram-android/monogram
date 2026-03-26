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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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
    showTelegramSummary: Boolean = false,
    showTelegramTranslator: Boolean = false,
    showRestoreOriginalText: Boolean = false,
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
    onTelegramSummary: () -> Unit = {},
    onTelegramTranslator: () -> Unit = {},
    onRestoreOriginalText: () -> Unit = {},
    onReport: () -> Unit = {},
    onBlock: () -> Unit = {},
    onRestrict: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val stickerRepository: StickerRepository = koinInject()

    val screenHeight = with(density) { configuration.screenHeightDp.dp.toPx() }.toInt()
    val windowInsets = WindowInsets.systemBars.union(WindowInsets.ime)
    val topInset = windowInsets.getTop(density)
    val bottomInset = windowInsets.getBottom(density)

    val horizontalMargin = with(density) { 16.dp.toPx() }.toInt()
    val verticalPadding = with(density) { 8.dp.toPx() }.toInt()

    var menuVisible by remember { mutableStateOf(false) }
    val visibilityTransition = updateTransition(targetState = menuVisible, label = "MenuVisibility")
    val menuScale by visibilityTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            } else {
                tween(durationMillis = 160, easing = FastOutLinearInEasing)
            }
        },
        label = "MenuScale"
    ) { visible ->
        if (visible) 1f else 0.94f
    }
    val menuAlpha by visibilityTransition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = if (targetState) 170 else 120,
                easing = LinearOutSlowInEasing
            )
        },
        label = "MenuAlpha"
    ) { visible ->
        if (visible) 1f else 0f
    }
    val dimAlpha by visibilityTransition.animateFloat(
        transitionSpec = { tween(durationMillis = if (targetState) 190 else 140) },
        label = "MenuDimAlpha"
    ) { visible ->
        if (visible) 1f else 0f
    }

    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    var firstScreenWidth by remember { mutableStateOf<Int?>(null) }
    var containerOffset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val currentSections = remember(
        message.id,
        canWrite,
        canPinMessages,
        message.canBeEdited,
        message.canBeForwarded,
        message.canGetMessageThread,
        message.canBeDeletedOnlyForSelf,
        message.canBeDeletedForAllUsers,
        showViewersList,
        showTelegramSummary,
        showTelegramTranslator,
        showRestoreOriginalText,
        canCopyLink,
        canReport,
        canBlock,
        canRestrict,
        message.canBeSaved,
        message.content
    ) {
        buildMenuSections(
            message = message,
            canWrite = canWrite,
            canPinMessages = canPinMessages,
            showViewersList = showViewersList,
            canCopyLink = canCopyLink,
            canReport = canReport,
            canBlock = canBlock,
            canRestrict = canRestrict,
            showTelegramSummary = showTelegramSummary,
            showTelegramTranslator = showTelegramTranslator,
            showRestoreOriginalText = showRestoreOriginalText
        )
    }
    var savedSections by rememberSaveable(message.id, stateSaver = MessageMenuSections.Saver) {
        mutableStateOf(currentSections)
    }
    var hasReactionsInMessage by remember(message.id) { mutableStateOf(false) }
    var suppressNextReactionsAppearanceAnimation by remember(message.id) { mutableStateOf(false) }
    var availableReactions by remember(message.chatId, message.id) { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(message.chatId, message.id) {
        availableReactions = stickerRepository.getMessageAvailableReactions(message.chatId, message.id)
    }

    fun animateOutAndDismiss(action: (() -> Unit)? = null) {
        if (!menuVisible) return
        scope.launch {
            menuVisible = false
            delay(170)
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
        menuVisible = true
    }

    LaunchedEffect(currentSections) {
        savedSections = savedSections.merge(currentSections)
    }

    var showDeleteSheet by remember { mutableStateOf(false) }
    var menuPage by remember { mutableStateOf(MenuPage.Main) }
    val sections = savedSections

    LaunchedEffect(menuPage, sections.hasMoreSection) {
        if (menuPage == MenuPage.More && !sections.hasMoreSection) {
            menuPage = MenuPage.Main
        }
    }

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
                val alphaVal = dimAlpha
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
                .widthIn(min = 208.dp, max = 276.dp)
                .heightIn(max = maxMenuHeight)
                .graphicsLayer {
                    this.alpha = if (menuSize == IntSize.Zero || containerSize == IntSize.Zero) 0f else menuAlpha
                    scaleX = menuScale
                    scaleY = menuScale
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
                    val forwardDuration = 185
                    val backDuration = 210
                    val forward = targetState.ordinal > initialState.ordinal
                    if (forward) {
                        (slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(forwardDuration, easing = FastOutSlowInEasing)
                        ) + scaleIn(
                            initialScale = 0.985f,
                            animationSpec = tween(forwardDuration, easing = FastOutSlowInEasing)
                        ))
                            .togetherWith(
                                slideOutOfContainer(
                                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                                    animationSpec = tween(forwardDuration, easing = FastOutLinearInEasing)
                                ) + scaleOut(
                                    targetScale = 0.99f,
                                    animationSpec = tween(forwardDuration, easing = FastOutLinearInEasing)
                                )
                            )
                    } else {
                        (slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(backDuration, easing = FastOutSlowInEasing)
                        ) + scaleIn(
                            initialScale = 0.99f,
                            animationSpec = tween(backDuration, easing = FastOutSlowInEasing)
                        ))
                            .togetherWith(
                                slideOutOfContainer(
                                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                                    animationSpec = tween(backDuration, easing = FastOutLinearInEasing)
                                ) + scaleOut(
                                    targetScale = 0.99f,
                                    animationSpec = tween(backDuration, easing = FastOutLinearInEasing)
                                )
                            )
                    }
                        .using(
                            SizeTransform(
                                clip = true,
                                sizeAnimationSpec = { _, _ ->
                                    tween(
                                        durationMillis = if (forward) forwardDuration else backDuration,
                                        easing = LinearOutSlowInEasing
                                    )
                                }
                            )
                        )
                        .apply {
                            targetContentZIndex = if (forward) 1f else 0f
                        }
                },
                label = "MenuTransition"
            ) { page ->
                val lockedWidth = firstScreenWidth
                val contentModifier = Modifier
                    .then(
                        if (lockedWidth != null) {
                            Modifier.width(with(density) { lockedWidth.toDp() })
                        } else {
                            Modifier
                        }
                    )
                    .onGloballyPositioned { coords ->
                        if (page == MenuPage.Main && (firstScreenWidth == null || firstScreenWidth != coords.size.width)) {
                            firstScreenWidth = coords.size.width
                        }
                    }

                Column(
                    modifier = contentModifier
                        .padding(vertical = 4.dp)
                ) {
                    if (page == MenuPage.Main) {
                        ReactionsRow(
                            message = message,
                            availableReactions = availableReactions,
                            suppressAppearanceAnimation = suppressNextReactionsAppearanceAnimation,
                            onAppearanceAnimationConsumed = {
                                suppressNextReactionsAppearanceAnimation = false
                            },
                            onReactionsChanged = { reactionCount ->
                                hasReactionsInMessage = reactionCount > 0
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

                        if (sections.hasViewersSection) {
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

                        if (sections.hasReplyAction) {
                            InternalMenuOptionItem(
                                icon = Icons.AutoMirrored.Rounded.Reply,
                                text = stringResource(R.string.menu_reply),
                                onClick = { animateOutAndDismiss(onReply) }
                            )
                        }

                        if (sections.hasPinAction) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.PushPin,
                                text = if (isPinned) stringResource(R.string.menu_unpin) else stringResource(R.string.menu_pin),
                                onClick = { animateOutAndDismiss(onPin) }
                            )
                        }

                        if (sections.hasEditAction) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.Edit,
                                text = stringResource(R.string.menu_edit),
                                onClick = { animateOutAndDismiss(onEdit) }
                            )
                        }

                        if (sections.hasCopyAction) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.ContentCopy,
                                text = stringResource(R.string.menu_copy),
                                onClick = { animateOutAndDismiss(onCopy) }
                            )
                        }

                        if (sections.hasForwardAction) {
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

                        if (sections.hasCocoonSection) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.AutoAwesome,
                                text = stringResource(R.string.menu_cocoon),
                                trailingIcon = Icons.Rounded.ChevronRight,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuPage = MenuPage.Cocoon
                                }
                            )
                        }

                        if (sections.hasMoreSection) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.MoreHoriz,
                                text = stringResource(R.string.menu_more),
                                trailingIcon = Icons.Rounded.ChevronRight,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuPage = MenuPage.More
                                }
                            )
                        }

                        if (sections.hasDeleteAction) {
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

                        if (sections.hasCommentsAction) {
                            InternalMenuOptionItem(
                                icon = Icons.AutoMirrored.Rounded.Chat,
                                text = stringResource(R.string.menu_view_comments),
                                onClick = { animateOutAndDismiss(onComments) }
                            )
                        }

                        if (sections.hasCopyLinkAction) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.Link,
                                text = stringResource(R.string.menu_copy_link),
                                onClick = { animateOutAndDismiss(onCopyLink) }
                            )
                        }

                        if (sections.hasDownloadAction) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.Download,
                                text = stringResource(R.string.menu_save_to_downloads),
                                onClick = { animateOutAndDismiss(onSaveToDownloads) }
                            )
                        }

                        if (sections.hasReportAction) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.Report,
                                text = stringResource(R.string.menu_report),
                                onClick = { animateOutAndDismiss(onReport) }
                            )
                        }

                        if (sections.hasBlockAction) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.Block,
                                text = stringResource(R.string.menu_block_user),
                                textColor = MaterialTheme.colorScheme.error,
                                iconTint = MaterialTheme.colorScheme.error,
                                onClick = { animateOutAndDismiss(onBlock) }
                            )
                            if (sections.hasRestrictAction) {
                                InternalMenuOptionItem(
                                    icon = Icons.Rounded.Gavel,
                                    text = stringResource(R.string.menu_restrict_user),
                                    textColor = MaterialTheme.colorScheme.error,
                                    iconTint = MaterialTheme.colorScheme.error,
                                    onClick = { animateOutAndDismiss(onRestrict) }
                                )
                            }
                        }
                    } else if (page == MenuPage.Cocoon) {
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

                        if (sections.hasTelegramSummaryAction) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.AutoAwesome,
                                text = stringResource(R.string.menu_telegram_summary),
                                onClick = { animateOutAndDismiss(onTelegramSummary) }
                            )
                        }

                        if (sections.hasTelegramTranslatorAction) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.Translate,
                                text = stringResource(R.string.menu_telegram_translate),
                                onClick = { animateOutAndDismiss(onTelegramTranslator) }
                            )
                        }

                        if (sections.hasRestoreOriginalTextAction) {
                            InternalMenuOptionItem(
                                icon = Icons.Rounded.Undo,
                                text = stringResource(R.string.menu_restore_original_text),
                                onClick = { animateOutAndDismiss(onRestoreOriginalText) }
                            )
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

private data class MessageMenuSections(
    val hasViewersSection: Boolean,
    val hasReplyAction: Boolean,
    val hasPinAction: Boolean,
    val hasEditAction: Boolean,
    val hasCopyAction: Boolean,
    val hasForwardAction: Boolean,
    val hasCocoonSection: Boolean,
    val hasMoreSection: Boolean,
    val hasDeleteAction: Boolean,
    val hasCommentsAction: Boolean,
    val hasCopyLinkAction: Boolean,
    val hasDownloadAction: Boolean,
    val hasReportAction: Boolean,
    val hasBlockAction: Boolean,
    val hasRestrictAction: Boolean,
    val hasTelegramSummaryAction: Boolean,
    val hasTelegramTranslatorAction: Boolean,
    val hasRestoreOriginalTextAction: Boolean
) {
    fun merge(other: MessageMenuSections): MessageMenuSections {
        return MessageMenuSections(
            hasViewersSection = hasViewersSection || other.hasViewersSection,
            hasReplyAction = hasReplyAction || other.hasReplyAction,
            hasPinAction = hasPinAction || other.hasPinAction,
            hasEditAction = hasEditAction || other.hasEditAction,
            hasCopyAction = hasCopyAction || other.hasCopyAction,
            hasForwardAction = hasForwardAction || other.hasForwardAction,
            hasCocoonSection = hasCocoonSection || other.hasCocoonSection,
            hasMoreSection = hasMoreSection || other.hasMoreSection,
            hasDeleteAction = hasDeleteAction || other.hasDeleteAction,
            hasCommentsAction = hasCommentsAction || other.hasCommentsAction,
            hasCopyLinkAction = hasCopyLinkAction || other.hasCopyLinkAction,
            hasDownloadAction = hasDownloadAction || other.hasDownloadAction,
            hasReportAction = hasReportAction || other.hasReportAction,
            hasBlockAction = hasBlockAction || other.hasBlockAction,
            hasRestrictAction = hasRestrictAction || other.hasRestrictAction,
            hasTelegramSummaryAction = hasTelegramSummaryAction || other.hasTelegramSummaryAction,
            hasTelegramTranslatorAction = hasTelegramTranslatorAction || other.hasTelegramTranslatorAction,
            hasRestoreOriginalTextAction = hasRestoreOriginalTextAction || other.hasRestoreOriginalTextAction
        )
    }

    companion object {
        val Saver = listSaver<MessageMenuSections, Boolean>(
            save = {
                listOf(
                    it.hasViewersSection,
                    it.hasReplyAction,
                    it.hasPinAction,
                    it.hasEditAction,
                    it.hasCopyAction,
                    it.hasForwardAction,
                    it.hasCocoonSection,
                    it.hasMoreSection,
                    it.hasDeleteAction,
                    it.hasCommentsAction,
                    it.hasCopyLinkAction,
                    it.hasDownloadAction,
                    it.hasReportAction,
                    it.hasBlockAction,
                    it.hasRestrictAction,
                    it.hasTelegramSummaryAction,
                    it.hasTelegramTranslatorAction,
                    it.hasRestoreOriginalTextAction
                )
            },
            restore = { values ->
                MessageMenuSections(
                    hasViewersSection = values[0],
                    hasReplyAction = values[1],
                    hasPinAction = values[2],
                    hasEditAction = values[3],
                    hasCopyAction = values[4],
                    hasForwardAction = values[5],
                    hasCocoonSection = values[6],
                    hasMoreSection = values[7],
                    hasDeleteAction = values[8],
                    hasCommentsAction = values[9],
                    hasCopyLinkAction = values[10],
                    hasDownloadAction = values[11],
                    hasReportAction = values[12],
                    hasBlockAction = values[13],
                    hasRestrictAction = values[14],
                    hasTelegramSummaryAction = values[15],
                    hasTelegramTranslatorAction = values[16],
                    hasRestoreOriginalTextAction = values[17]
                )
            }
        )
    }
}

private fun buildMenuSections(
    message: MessageModel,
    canWrite: Boolean,
    canPinMessages: Boolean,
    showViewersList: Boolean,
    canCopyLink: Boolean,
    canReport: Boolean,
    canBlock: Boolean,
    canRestrict: Boolean,
    showTelegramSummary: Boolean,
    showTelegramTranslator: Boolean,
    showRestoreOriginalText: Boolean
): MessageMenuSections {
    val hasCocoonActions = showTelegramSummary || showTelegramTranslator || showRestoreOriginalText
    val hasMoreActions = message.canGetMessageThread ||
            canCopyLink ||
            shouldShowDownload(message) ||
            canReport ||
            canBlock
    return MessageMenuSections(
        hasViewersSection = showViewersList,
        hasReplyAction = canWrite,
        hasPinAction = canPinMessages,
        hasEditAction = message.canBeEdited,
        hasCopyAction = shouldShowCopy(message),
        hasForwardAction = message.canBeForwarded,
        hasCocoonSection = hasCocoonActions,
        hasMoreSection = hasMoreActions,
        hasDeleteAction = message.canBeDeletedOnlyForSelf || message.canBeDeletedForAllUsers,
        hasCommentsAction = message.canGetMessageThread,
        hasCopyLinkAction = canCopyLink,
        hasDownloadAction = shouldShowDownload(message),
        hasReportAction = canReport,
        hasBlockAction = canBlock,
        hasRestrictAction = canBlock && canRestrict,
        hasTelegramSummaryAction = showTelegramSummary,
        hasTelegramTranslatorAction = showTelegramTranslator,
        hasRestoreOriginalTextAction = showRestoreOriginalText
    )
}

private enum class MenuPage {
    Main,
    More,
    Cocoon,
    Viewers
}

@Composable
private fun ReactionsRow(
    message: MessageModel,
    availableReactions: List<String>,
    suppressAppearanceAnimation: Boolean,
    onAppearanceAnimationConsumed: () -> Unit,
    onReactionsChanged: (Int) -> Unit,
    onReaction: (String) -> Unit,
    appPreferences: AppPreferences = koinInject()
) {
    val haptic = LocalHapticFeedback.current

    val context = LocalContext.current
    val emojiStyle by appPreferences.emojiStyle.collectAsState()
    val emojiFontFamily = remember(context, emojiStyle) { getEmojiFontFamily(context, emojiStyle) }

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
            fadeIn(animationSpec = tween(150, easing = LinearOutSlowInEasing))
        },
        exit = fadeOut(animationSpec = tween(100, easing = FastOutLinearInEasing)),
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
                        targetValue = if (isChosen) 1.06f else 1f,
                        animationSpec = tween(durationMillis = 160, easing = LinearOutSlowInEasing),
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
