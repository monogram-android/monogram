package org.monogram.feature.dialog.ui

import android.text.format.DateFormat.getTimeFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.DateFormat.getDateInstance
import java.util.Locale
import org.monogram.core.common.Outcome
import org.monogram.core.models.Message
import org.monogram.core.models.Profile
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.ui.components.PeerAvatar
import org.monogram.core.ui.localizedServiceMessage
import org.monogram.core.ui.rememberEnsuredFile
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaRepository

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    sender: Profile?,
    senderTag: String? = null,
    showSender: Boolean,
    showAvatar: Boolean = false,
    /** Channels show views, not checkmarks. */
    showReadStatus: Boolean = true,
    joinsMessageAbove: Boolean,
    joinsMessageBelow: Boolean,
    mediaRepository: MediaRepository?,
    reserveAvatarGutter: Boolean = false,
    onOpenSender: (() -> Unit)? = null,
    onOpenForwardSource: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onOpenMenu: ((Offset) -> Unit)? = null,
    quoted: Message? = null,
    quotedSender: String? = null,
    album: List<Message> = emptyList(),
    onReact: ((emoticon: String, documentId: Long) -> Unit)? = null,
    onAddReaction: (() -> Unit)? = null,
    onShowReactionUsers: (() -> Unit)? = null,
    onShowPollVoters: (() -> Unit)? = null,
    onComments: (() -> Unit)? = null,
    onQuoteClick: (() -> Unit)? = null,
    onOpenStickerPack: ((Long) -> Unit)? = null,
    onInstantView: ((org.monogram.core.models.WebpagePreview) -> Unit)? = null,
    onMarkupButton: ((org.monogram.core.models.ReplyButton) -> Unit)? = null,
    onToggleChecklist: ((Int) -> Unit)? = null,
    onPollVote: ((Int, List<ByteArray>) -> Unit)? = null,
    onAddChecklistItem: ((Int, Int) -> Unit)? = null,
    hideTopicRootReply: Boolean = false,
    selectable: Boolean = false,
    selectAllNonce: Int = 0,
    onSelectedText: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val outgoing = message.outgoing
    var bubblePosition by remember { mutableStateOf(Offset.Zero) }
    if (message.mediaKind == "service") {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = localizedServiceMessage(
                    raw = message.text.orEmpty(),
                    outgoing = outgoing,
                ),
                modifier = Modifier
                    .then(
                        if (onQuoteClick != null && message.replyToMsgId != null) {
                            Modifier.clickable(onClick = onQuoteClick)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val stickerOnly = isStickerOnly(message)
    var appearancePlayed by rememberSaveable(message.id) {
        mutableStateOf(false)
    }
    val appear = remember(message.id) {
        Animatable(if (!appearancePlayed && message.shouldPlayAppear()) 0f else 1f)
    }
    LaunchedEffect(message.id) {
        appearancePlayed = true
        if (appear.value < 1f) {
            appear.animateTo(
                1f,
                animationSpec = spring(
                    dampingRatio = if (outgoing || message.pending) 0.68f else Spring.DampingRatioNoBouncy,
                    stiffness = if (outgoing || message.pending) {
                        Spring.StiffnessMedium
                    } else {
                        Spring.StiffnessMediumLow
                    },
                ),
            )
        }
    }
    var spoilersVisible by remember(message.id) { mutableStateOf(false) }
    val renderedBlocks = rememberMessageBlocks(message.text.orEmpty(), message.entities)
    val hasSpoiler = renderedBlocks.containsSpoilers()
    val senderTitle = sender?.title?.takeIf { it.isNotBlank() }
        ?: message.senderName?.takeIf { it.isNotBlank() }
    val quote = if (quoted?.mediaKind == "service") {
        // Service payloads are deliberately compact on the wire; never expose
        // their unit separators in a reply preview.
        localizedServiceMessage(
            raw = quoted.text.orEmpty(),
            outgoing = quoted.outgoing,
        )
    } else {
        message.replyQuote?.takeIf { it.isNotBlank() }
            ?: quoted?.text?.takeIf { it.isNotBlank() }
    }
    val showReplyQuote = !hideTopicRootReply &&
        (message.replyToMsgId != null || !quote.isNullOrBlank())
    val bubbleHeader = (showSender && senderTitle != null) ||
        !message.fwdFrom.isNullOrBlank() ||
        message.fwdFromId != null ||
        message.fwdDate != null ||
        !message.viaBot.isNullOrBlank()
    val mediaCaption = shouldShowMessageCaption(message.mediaKind, message.text, message.fileName)
    val groupedAlbum = album.size > 1
    val singleVisualMedia = album.size <= 1 && isEdgeMediaKind(message.mediaKind)
    val edgeVisualMedia = singleVisualMedia || groupedAlbum
    // A picture, video, GIF or album alone in its bubble drops the bubble fill entirely.
    val mediaOnlyBubble = edgeVisualMedia &&
        !mediaCaption &&
        !bubbleHeader &&
        !showReplyQuote &&
        !(onComments != null && message.discussionPeerId != null)
    // Captioned or chromed media still reaches the bubble edge, over the content inset.
    val edgeMedia = edgeVisualMedia && !mediaOnlyBubble
    val mediaBleedTop = edgeMedia && !bubbleHeader && !showReplyQuote
    // Picture bubbles let the media own the horizontal edges, so the text around it is
    // padded on its own instead of the whole bubble.
    val edgeContentPad = if (edgeVisualMedia) BUBBLE_CONTENT_PAD else 0.dp
    // A caption never widens a picture bubble: the bubble hugs the picture and the
    // caption wraps, so the picture stays flush with both edges.
    val mediaFrameWidth = if (singleVisualMedia) {
        bubbleEdgeMediaDisplaySize(message.mediaKind, message.mediaWidth, message.mediaHeight)
            .first
            .dp
    } else {
        null
    }
    val shape = remember(outgoing, joinsMessageAbove, joinsMessageBelow) {
        messageShape(outgoing, joinsMessageAbove, joinsMessageBelow)
    }
    val container = if (outgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val onContainer = if (outgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val cacheGeneration = mediaRepository?.cacheGeneration?.collectAsState()?.value ?: 0L
    val senderAvatar = rememberEnsuredFile(
        generation = cacheGeneration,
        identity = sender?.id?.value to sender?.avatarCacheKey,
        resolve = {
            sender?.avatarCacheKey?.let { mediaRepository?.cachedFile(it) }
                ?: sender?.id?.let { mediaRepository?.cachedAvatar(it) }
        },
        ensure = {
            val repo = mediaRepository ?: return@rememberEnsuredFile null
            val peer = sender?.id ?: return@rememberEnsuredFile null
            val key = peerAvatarCacheKey(peer, sender.avatarCacheKey)
            when (val result = repo.ensureLocalAvatar(peer, key)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> null
            }
        },
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!outgoing && showAvatar) {
            PeerAvatar(
                title = senderTitle.orEmpty(),
                size = 28.dp,
                imageFile = senderAvatar,
                modifier = Modifier
                    .padding(end = 6.dp, bottom = 2.dp)
                    .then(
                        if (onOpenSender != null) {
                            Modifier.clickable(onClick = onOpenSender)
                        } else {
                            Modifier
                        },
                    ),
            )
        } else if (!outgoing && reserveAvatarGutter) {
            Spacer(Modifier.size(width = 34.dp, height = 28.dp))
        }
        val centerMedia = !stickerOnly && when (message.mediaKind) {
            "photo", "video", "gif" -> true
            else -> false
        }
        Column(
            modifier = Modifier
                .widthIn(max = BUBBLE_MAX_WIDTH_DP.dp)
                .wrapContentWidth(
                    align = if (outgoing) Alignment.End else Alignment.Start,
                )
                .width(IntrinsicSize.Max)
                .graphicsLayer {
                    val t = appear.value
                    val start = if (outgoing) 0.82f else 0.92f
                    val scale = start + (1f - start) * t
                    scaleX = scale
                    scaleY = scale
                    alpha = t
                    transformOrigin = TransformOrigin(
                        pivotFractionX = if (outgoing) 1f else 0f,
                        pivotFractionY = 1f,
                    )
                },
            horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
        ) {
        Column(
            modifier = Modifier
                .then(if (stickerOnly) Modifier else Modifier.clip(shape))
                .then(mediaFrameWidth?.let { Modifier.width(it) } ?: Modifier)
                .then(
                    if (stickerOnly || mediaOnlyBubble) {
                        Modifier
                    } else {
                        Modifier.background(container)
                    },
                )
                .then(
                    if (!selectable && onOpenMenu != null) {
                        Modifier
                            .onGloballyPositioned { bubblePosition = it.positionInWindow() }
                            .pointerInput(onOpenMenu, hasSpoiler) {
                                if (hasSpoiler) {
                                    detectTapGestures(
                                        onLongPress = { onOpenMenu(bubblePosition) },
                                        onTap = { spoilersVisible = !spoilersVisible },
                                    )
                                } else {
                                    detectTapGestures(
                                        onLongPress = { onOpenMenu(bubblePosition) },
                                    )
                                }
                            }
                    } else if (!selectable && (onLongPress != null || hasSpoiler)) {
                        Modifier.combinedClickable(
                            onClick = {
                                if (hasSpoiler) {
                                    spoilersVisible = !spoilersVisible
                                }
                            },
                            onLongClick = { onLongPress?.invoke() },
                            onClickLabel = null,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(
                    horizontal = if (stickerOnly || edgeVisualMedia) 0.dp else BUBBLE_CONTENT_PAD,
                    vertical = when {
                        stickerOnly -> 2.dp
                        mediaOnlyBubble -> 0.dp
                        else -> BUBBLE_CONTENT_VPAD
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(if (showSender) 1.dp else 4.dp),
        ) {
            if (showSender && senderTitle != null) {
                Row(
                    modifier = Modifier.padding(horizontal = edgeContentPad),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = senderTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(
                                if (onOpenSender != null) {
                                    Modifier.clickable(onClick = onOpenSender)
                                } else {
                                    Modifier
                                },
                            ),
                    )
                    sender?.emojiStatusDocumentId?.let { documentId ->
                        CustomEmojiGlyph(
                            documentId = documentId,
                            size = 16.dp,
                            onClick = onOpenStickerPack?.let { open -> { open(documentId) } },
                        )
                    }
                    senderTagLabel(senderTag)?.let { tag ->
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            if (!message.fwdFrom.isNullOrBlank() || message.fwdFromId != null || message.fwdDate != null) {
                Column(
                    modifier = Modifier
                        .then(
                            if (onOpenForwardSource != null) {
                                Modifier.heightIn(min = 48.dp).clickable(onClick = onOpenForwardSource)
                            } else Modifier,
                        )
                        .padding(vertical = 3.dp, horizontal = edgeContentPad),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = message.fwdFrom?.takeIf { it.isNotBlank() }?.let {
                            stringResource(R.string.dialog_forwarded_from, it)
                        } ?: stringResource(R.string.dialog_forwarded_message),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    message.fwdDate?.takeIf { it > 0 }?.let { date ->
                        val locale = Locale.forLanguageTag(LocalLocale.current.toLanguageTag())
                        val context = LocalContext.current
                        val formatted = remember(date, locale, context.resources.configuration) {
                            val original = java.util.Date(date * 1_000L)
                            getDateInstance(java.text.DateFormat.MEDIUM, locale).format(original) +
                                " " + getTimeFormat(context).format(original)
                        }
                        Text(
                            text = formatted,
                            style = MaterialTheme.typography.labelSmall,
                            color = onContainer.copy(alpha = 0.72f),
                        )
                    }
                }
            }
            message.viaBot?.takeIf { it.isNotBlank() }?.let { bot ->
                Text(
                    text = stringResource(R.string.dialog_via_bot, bot.removePrefix("@")),
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainer.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = edgeContentPad),
                )
            }
            if (showReplyQuote) {
                MessageReplyQuote(
                    quote = quote,
                    quoteKind = quoted?.mediaKind,
                    quotedOutgoing = quoted?.outgoing,
                    quotedSender = quotedSender,
                    onContainer = onContainer,
                    onQuoteClick = onQuoteClick,
                    modifier = Modifier.padding(horizontal = edgeContentPad),
                )
            }
            val mediaOverlayMeta = stickerOnly || mediaOnlyBubble ||
                (album.size > 1 && !mediaCaption)
            Box(
                modifier = if (centerMedia || album.size > 1) Modifier.fillMaxWidthInBubble() else Modifier,
                contentAlignment = Alignment.Center,
            ) {
                if (groupedAlbum) {
                    AlbumMosaic(
                        messages = album,
                        mediaRepository = mediaRepository,
                        shape = shape,
                        onLongPress = onOpenMenu?.let { open -> { open(bubblePosition) } },
                        modifier = if (mediaOnlyBubble) {
                            Modifier
                        } else {
                            Modifier.bleedBubbleMedia(
                                horizontal = 0.dp,
                                bleedTop = mediaBleedTop,
                            )
                        },
                    )
                } else {
                ServiceMediaCard(
                    message = message,
                    mediaRepository = mediaRepository,
                    onPollVote = onPollVote?.let { vote -> { options -> vote(message.id.id, options) } },
                    onShowPollVoters = onShowPollVoters,
                )
                MessageMedia(
                    message = message,
                    mediaRepository = mediaRepository,
                    edgeToEdge = singleVisualMedia,
                    onStickerClick = onOpenStickerPack?.let { open ->
                        {
                            stickerDocumentId(message.mediaCacheKey)?.let(open)
                        }
                    },
                    onInstantView = onInstantView,
                    onLongPress = onOpenMenu?.let { open -> { open(bubblePosition) } },
                    modifier = if (singleVisualMedia) {
                        Modifier.bleedBubbleMedia(horizontal = 0.dp, bleedTop = mediaBleedTop)
                    } else {
                        Modifier
                    },
                )
                }
                if (mediaOverlayMeta) {
                    MessageMetadata(
                        message = message,
                        time = messageTime(message.date),
                        color = Color.White,
                        showReadStatus = showReadStatus,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            if (message.mediaKind == "unsupported" && message.text.isNullOrEmpty()) {
                Text(
                    text = stringResource(R.string.dialog_unsupported_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer,
                    modifier = Modifier.padding(horizontal = edgeContentPad),
                )
            }
            if (mediaCaption && !mediaOverlayMeta) {
                var textLayoutInfo by remember { mutableStateOf<MessageTextLayoutInfo?>(null) }
                val hasWideChrome = message.replyToMsgId != null ||
                    !quote.isNullOrBlank() ||
                    message.mediaKind != null ||
                    showSender ||
                    !message.fwdFrom.isNullOrBlank() ||
                    !message.viaBot.isNullOrBlank()
                TextWithTimestampLayout(
                    textLayoutInfo = textLayoutInfo,
                    modifier = Modifier
                        .padding(horizontal = edgeContentPad)
                        .then(if (hasWideChrome) Modifier.fillMaxWidthInBubble() else Modifier),
                    horizontalPadding = 8,
                    stackedTopPadding = 2,
                    textContent = {
                        RichMessageContent(
                            text = message.text.orEmpty(),
                            entities = message.entities,
                            preparedBlocks = renderedBlocks,
                            contentColor = onContainer,
                            linkColor = MaterialTheme.colorScheme.primary,
                            revealSpoilers = spoilersVisible,
                            onSpoilerClick = { spoilersVisible = !spoilersVisible },
                            selectable = selectable,
                            selectAllNonce = selectAllNonce,
                            onSelectedText = onSelectedText,
                            onTextLayout = { textLayoutInfo = it.toMessageTextLayoutInfo() },
                            onOpenStickerPack = onOpenStickerPack,
                            hostMessage = message,
                            mediaRepository = mediaRepository,
                        )
                    },
                    timestampContent = {
                        MessageMetadata(
                            message = message,
                            time = messageTime(message.date),
                            color = onContainer.copy(alpha = 0.72f),
                            showReadStatus = showReadStatus,
                        )
                    },
                )
            } else if (!mediaOverlayMeta) {
                MessageMetadata(
                    message = message,
                    time = messageTime(message.date),
                    color = onContainer.copy(alpha = 0.72f),
                    modifier = Modifier.align(Alignment.End),
                    showReadStatus = showReadStatus,
                )
            }
            if (onComments != null && message.discussionPeerId != null) {
                Text(
                    text = if (message.repliesCount > 0) {
                        stringResource(R.string.dialog_comments, message.repliesCount)
                    } else {
                        stringResource(R.string.dialog_leave_comment)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = edgeContentPad)
                        .fillMaxWidthInBubble()
                        .clickable(onClick = onComments)
                        .padding(top = 4.dp, bottom = 2.dp),
                )
            }
        }
            message.checklist?.let { list ->
                Column(
                    modifier = Modifier
                        .fillMaxWidthInBubble()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (list.title.isNotBlank()) {
                        Text(
                            text = list.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = onContainer,
                        )
                    }

                    val addEnabled = onAddChecklistItem != null &&
                        (message.outgoing || list.othersCanAppend) && !message.pending
                    if (addEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 36.dp)
                                .clickable {
                                    val nextId = (list.items.maxOfOrNull { it.id } ?: 0) + 1
                                    onAddChecklistItem.invoke(message.id.id, nextId)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.dialog_checklist_add),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    list.items.forEach { item ->
                        val enabled = onToggleChecklist != null && (message.outgoing || list.othersCanComplete)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 40.dp)
                                .then(if (enabled) Modifier.clickable { onToggleChecklist.invoke(item.id) } else Modifier),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TelegramRoundCheck(
                                checked = item.done,
                                color = if (item.done) MaterialTheme.colorScheme.primary else onContainer,
                            )
                            Text(
                                text = item.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (item.done) onContainer.copy(alpha = 0.72f) else onContainer,
                            )
                        }
                    }
                }
            }
            val inlineMarkup = message.replyMarkup?.takeIf {
                it.kind == org.monogram.core.models.ReplyMarkupKind.Inline && it.rows.isNotEmpty()
            }
            if (inlineMarkup != null && onMarkupButton != null) {
                BotKeyboardGrid(
                    markup = inlineMarkup,
                    onClick = onMarkupButton,
                    modifier = Modifier.padding(top = 4.dp),
                    compact = true,
                )
            }
            if (!stickerOnly && onReact != null) {
                ReactionBar(
                    message = message,
                    onReact = onReact,
                    onAddReaction = onAddReaction,
                    onShowUsers = onShowReactionUsers,
                    modifier = Modifier
                        .offset(y = (-4).dp)
                        .padding(start = 2.dp, end = 2.dp, top = 6.dp),
                )
            }
        }
    }
}

private fun Message.shouldPlayAppear(): Boolean {
    if (pending || failed || id.id < 0) return true
    val ageSeconds = (System.currentTimeMillis() / 1000L) - date
    return ageSeconds in 0..6
}

/** Stretch to the bubble width without reporting parent max as intrinsic width. */
internal fun Modifier.fillMaxWidthInBubble(): Modifier = this.then(FillMaxWidthInBubble)

/** Vertical bubble inset; media bubbles pull their first child up by this much. */
internal val BUBBLE_CONTENT_VPAD = 7.dp

/**
 * Let one picture, video or GIF reach the bubble edge over the bubble's content inset,
 * so only the bubble clip rounds it. The slot keeps the bubble's layout width.
 */
internal fun Modifier.bleedBubbleMedia(
    horizontal: Dp = BUBBLE_CONTENT_PAD,
    vertical: Dp = BUBBLE_CONTENT_VPAD,
    bleedTop: Boolean = false,
): Modifier = layout { measurable, constraints ->
    val hInset = horizontal.roundToPx()
    val vInset = vertical.roundToPx()
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val slot = constraints.maxWidth + hInset * 2
    val placeable = measurable.measure(
        constraints.copy(minWidth = 0, maxWidth = slot, minHeight = 0),
    )
    val x = -hInset + (slot - placeable.width) / 2
    val y = if (bleedTop) -vInset else 0
    val height = (placeable.height - (if (bleedTop) vInset else 0)).coerceAtLeast(0)
    layout(constraints.maxWidth, height) {
        placeable.place(x, y)
    }
}

private object FillMaxWidthInBubble : LayoutModifier {
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val childConstraints = if (constraints.hasBoundedWidth) {
            constraints.copy(minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth)
        } else {
            constraints.copy(minWidth = 0)
        }
        val placeable = measurable.measure(childConstraints)
        return layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurable: IntrinsicMeasurable,
        height: Int,
    ): Int = 0

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurable: IntrinsicMeasurable,
        height: Int,
    ): Int = measurable.maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurable: IntrinsicMeasurable,
        width: Int,
    ): Int = measurable.minIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurable: IntrinsicMeasurable,
        width: Int,
    ): Int = measurable.maxIntrinsicHeight(width)
}
