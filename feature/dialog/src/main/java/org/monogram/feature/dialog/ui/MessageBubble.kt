package org.monogram.feature.dialog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.outlined.MoreVert
import org.monogram.core.ui.menu.AppMenuItem
import org.monogram.core.ui.menu.AppMenuPopup
import org.monogram.core.ui.menu.AppMenuSurface

import android.text.format.DateFormat.getTimeFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
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
import org.monogram.core.common.Outcome
import org.monogram.core.models.Message
import org.monogram.core.models.MessageReaction
import org.monogram.core.models.Profile
import org.monogram.core.models.ReplyMarkupKind
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.ui.components.PeerAvatar
import org.monogram.core.ui.localizedServiceMessage
import org.monogram.core.ui.rememberEnsuredFile
import org.monogram.core.ui.rememberStrippedPreview
import org.monogram.feature.dialog.R
import org.monogram.feature.dialog.isNonVisualAlbum
import org.monogram.feature.dialog.isVisualAlbum
import org.monogram.network.http.MediaRepository
import java.text.DateFormat.getDateInstance
import java.util.Locale

@OptIn(
    ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)
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
    passClicks: Boolean = false,
    quoted: Message? = null,
    quotedSender: String? = null,
    album: List<Message> = emptyList(),
    onReact: ((emoticon: String, documentId: Long) -> Unit)? = null,
    onAddReaction: (() -> Unit)? = null,
    onShowReactionUsers: ((MessageReaction) -> Unit)? = null,
    onShowPollVoters: (() -> Unit)? = null,
    onComments: (() -> Unit)? = null,
    onQuoteClick: (() -> Unit)? = null,
    onOpenStickerPack: ((documentId: Long, emojiPack: Boolean) -> Unit)? = null,
    onInstantView: ((org.monogram.core.models.WebpagePreview) -> Unit)? = null,
    onMarkupButton: ((org.monogram.core.models.ReplyButton) -> Unit)? = null,
    checklistBusy: Boolean = false,
    onToggleChecklist: ((Int) -> Unit)? = null,
    onRemoveChecklistItem: ((Int) -> Unit)? = null,
    onEditChecklistItem: ((Int) -> Unit)? = null,
    onClosePoll: ((Int) -> Unit)? = null,
    pollVoting: Boolean = false,
    onPollVote: ((Int, List<ByteArray>) -> Unit)? = null,
    onAddChecklistItem: ((Int, Int) -> Unit)? = null,
    onRetrySend: (() -> Unit)? = null,
    sendUncertain: Boolean = false,
    sendError: String? = null,
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
    val appearanceKey = message.randomId?.takeIf { it != 0L } ?: message.id.id.toLong()
    var appearancePlayed by rememberSaveable(appearanceKey) {
        mutableStateOf(false)
    }
    val appear = remember(appearanceKey) {
        Animatable(if (!appearancePlayed && message.shouldPlayAppear()) 0f else 1f)
    }
    LaunchedEffect(appearanceKey) {
        appearancePlayed = true
        if (appear.value < 1f) {
            appear.animateTo(
                1f,
                animationSpec = tween(durationMillis = 180),
            )
        }
    }
    var spoilersVisible by remember(message.id) { mutableStateOf(false) }
    val renderedBlocks = rememberMessageBlocks(message.text.orEmpty(), message.entities)
    val hasSpoiler = renderedBlocks.containsSpoilers()
    val senderTitle =
        sender?.title?.takeIf { it.isNotBlank() } ?: message.senderName?.takeIf { it.isNotBlank() }
    val quote = if (quoted?.mediaKind == "service") {
        // Service payloads are deliberately compact on the wire; never expose
        // their unit separators in a reply preview.
        localizedServiceMessage(
            raw = quoted.text.orEmpty(),
            outgoing = quoted.outgoing,
        )
    } else {
        message.replyQuote?.takeIf { it.isNotBlank() } ?: quoted?.text?.takeIf { it.isNotBlank() }
    }
    val showReplyQuote =
        !hideTopicRootReply && (message.replyToMsgId != null || !quote.isNullOrBlank())
    val bubbleHeader =
        (showSender && senderTitle != null) || !message.fwdFrom.isNullOrBlank() || message.fwdFromId != null || message.fwdDate != null || !message.viaBot.isNullOrBlank()
    val mediaCaption = shouldShowMessageCaption(message.mediaKind, message.text, message.fileName)
    val isVisual = isVisualAlbum(album)
    val isNonVisual = isNonVisualAlbum(album)
    val groupedAlbum = isVisual
    val singleVisualMedia = album.size <= 1 && isEdgeMediaKind(message.mediaKind)
    val edgeVisualMedia = singleVisualMedia || groupedAlbum
    // A picture, video, GIF or album alone in its bubble drops the bubble fill entirely.
    val hasComments = onComments != null && message.discussionPeerId != null
    val mediaOnlyBubble =
        edgeVisualMedia && !mediaCaption && !bubbleHeader && !showReplyQuote && !hasComments
    // Captioned or chromed media still reaches the bubble edge, over the content inset.
    val edgeMedia = edgeVisualMedia && !mediaOnlyBubble
    val mediaBleedTop = edgeMedia && !bubbleHeader && !showReplyQuote
    val mediaBleedBottom = shouldBleedMediaBottom(edgeMedia, mediaCaption, hasComments)
    val mediaOverlayMeta = shouldOverlayMediaMeta(
        stickerOnly = stickerOnly,
        edgeVisualMedia = edgeVisualMedia,
        mediaCaption = mediaCaption,
    )
    // Picture bubbles let the media own the horizontal edges, so the text around it is
    // padded on its own instead of the whole bubble.
    val edgeContentPad = if (edgeVisualMedia) BUBBLE_CONTENT_PAD else 0.dp
    // A caption never widens a picture bubble: the bubble hugs the picture and the
    // caption wraps, so the picture stays flush with both edges.
    val mediaFrameWidth = if (singleVisualMedia) {
        bubbleEdgeMediaDisplaySize(
            message.mediaKind,
            message.mediaWidth,
            message.mediaHeight
        ).first.dp
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
                previewBytes = rememberStrippedPreview(sender?.id) {
                    sender?.id?.let { mediaRepository?.peerInlineThumbJpeg(it) }
                },
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
                    translationY = (1f - t) * (if (outgoing) 6f else 10f)
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
                        if (!selectable && !passClicks && onOpenMenu != null) {
                            val openMenu = rememberUpdatedState(onOpenMenu)
                            val hold = rememberUpdatedState(onLongPress)
                            Modifier
                                .onGloballyPositioned { bubblePosition = it.positionInWindow() }
                                .pointerInput(hasSpoiler) {
                                    if (hasSpoiler) {
                                        menuOrHold(
                                            onTap = {
                                                spoilersVisible = !spoilersVisible
                                                openMenu.value.invoke(bubblePosition)
                                            },
                                            onLongPress = {
                                                hold.value?.invoke() ?: openMenu.value.invoke(
                                                    bubblePosition
                                                )
                                            },
                                        )
                                    } else {
                                        menuOrHold(
                                            onTap = { openMenu.value.invoke(bubblePosition + it) },
                                            onLongPress = {
                                                hold.value?.invoke() ?: openMenu.value.invoke(
                                                    bubblePosition
                                                )
                                            },
                                        )
                                    }
                                }
                        } else if (!selectable && !passClicks && (onLongPress != null || hasSpoiler)) {
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
                                onClick = onOpenStickerPack?.let { open ->
                                    {
                                        open(
                                            documentId, true
                                        )
                                    }
                                },
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
                                    Modifier
                                        .heightIn(min = 48.dp)
                                        .clickable(onClick = onOpenForwardSource)
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
                            val formatted =
                                remember(date, locale, context.resources.configuration) {
                                    val original = java.util.Date(date * 1_000L)
                                    getDateInstance(java.text.DateFormat.MEDIUM, locale).format(
                                        original
                                    ) + " " + getTimeFormat(context).format(original)
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
                        quoteEntities = message.replyQuoteEntities,
                        quoteKind = quoted?.mediaKind,
                        quotedOutgoing = quoted?.outgoing,
                        quotedSender = quotedSender,
                        onContainer = onContainer,
                        onQuoteClick = onQuoteClick,
                        modifier = Modifier.padding(horizontal = edgeContentPad),
                    )
                }
                Box(
                    modifier = if (centerMedia || isVisual || isNonVisual) Modifier.fillMaxWidthInBubble() else Modifier,
                    contentAlignment = Alignment.Center,
                ) {
                    if (isVisual) {
                        AlbumMosaic(
                            messages = album,
                            mediaRepository = mediaRepository,
                            shape = shape,
                            onLongPress = onLongPress ?: onOpenMenu?.let { open ->
                                {
                                    open(
                                        bubblePosition
                                    )
                                }
                            },
                            modifier = if (mediaOnlyBubble) {
                                Modifier
                            } else {
                                Modifier.bleedBubbleMedia(
                                    horizontal = 0.dp,
                                    bleedTop = mediaBleedTop,
                                    bleedBottom = mediaBleedBottom,
                                )
                            },
                        )
                    } else if (isNonVisual) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            album.asReversed().forEach { docMessage ->
                                MessageMedia(
                                    message = docMessage,
                                    mediaRepository = mediaRepository,
                                    edgeToEdge = false,
                                    onStickerClick = onOpenStickerPack?.let { open ->
                                        {
                                            stickerDocumentId(docMessage.mediaCacheKey)?.let { id ->
                                                open(id, false)
                                            }
                                        }
                                    },
                                    onInstantView = onInstantView,
                                    onLongPress = onLongPress ?: onOpenMenu?.let { open ->
                                        {
                                            open(
                                                bubblePosition
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    } else {
                        ServiceMediaCard(
                            message = message,
                            mediaRepository = mediaRepository,
                            onClosePoll = onClosePoll?.let { close -> { close(message.id.id) } },
                            pollVoting = pollVoting,
                            onPollVote = onPollVote?.let { vote ->
                                { options ->
                                    vote(
                                        message.id.id, options
                                    )
                                }
                            },
                            onShowPollVoters = onShowPollVoters,
                        )
                        MessageMedia(
                            message = message,
                            mediaRepository = mediaRepository,
                            edgeToEdge = singleVisualMedia,
                            onStickerClick = onOpenStickerPack?.let { open ->
                                {
                                    stickerDocumentId(message.mediaCacheKey)?.let { id ->
                                        open(id, false)
                                    }
                                }
                            },
                            onInstantView = onInstantView,
                            onLongPress = onLongPress ?: onOpenMenu?.let { open ->
                                {
                                    open(
                                        bubblePosition
                                    )
                                }
                            },
                            modifier = if (singleVisualMedia) {
                                Modifier.bleedBubbleMedia(
                                    horizontal = 0.dp,
                                    bleedTop = mediaBleedTop,
                                    bleedBottom = mediaBleedBottom,
                                )
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
                                .padding(8.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                message.checklist?.let { list ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidthInBubble()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .animateContentSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (list.title.isNotBlank()) {
                            MessageText(
                                text = list.title,
                                entities = list.entities,
                                textStyle = MaterialTheme.typography.titleMediumEmphasized,
                                contentColor = onContainer,
                                linkColor = MaterialTheme.colorScheme.primary,
                                revealSpoilers = spoilersVisible,
                                onSpoilerClick = { spoilersVisible = !spoilersVisible },
                                onOpenStickerPack = onOpenStickerPack,
                            )
                        }

                        val completed = list.items.count { it.done }
                        val completion by androidx.compose.animation.core.animateFloatAsState(
                            if (list.items.isEmpty()) 0f else completed.toFloat() / list.items.size,
                            label = "checklistCompletion",
                        )
                        Text(
                            stringResource(
                                R.string.dialog_checklist_progress,
                                completed,
                                list.items.size
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = onContainer.copy(alpha = 0.72f)
                        )
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { completion },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            drawStopIndicator = {},
                        )
                        if (checklistBusy) org.monogram.core.ui.loading.MonogramLoading()
                        val addEnabled =
                            onAddChecklistItem != null && (message.outgoing || list.othersCanAppend) && !message.pending && message.id.id > 0 && list.items.none { it.id == Int.MAX_VALUE }
                        list.items.forEach { item ->
                            androidx.compose.runtime.key(item.id) {
                                val itemColor by androidx.compose.animation.animateColorAsState(
                                    if (item.done) onContainer.copy(alpha = 0.65f) else onContainer,
                                    label = "taskColor"
                                )
                                val enabled =
                                    onToggleChecklist != null && !checklistBusy && !message.pending && message.id.id > 0 && (message.outgoing || list.othersCanComplete)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .toggleable(
                                            value = item.done,
                                            enabled = enabled,
                                            role = androidx.compose.ui.semantics.Role.Checkbox,
                                            onValueChange = { onToggleChecklist?.invoke(item.id) }),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TelegramRoundCheck(
                                        checked = item.done,
                                        color = if (item.done) MaterialTheme.colorScheme.primary else onContainer,
                                    )
                                    MessageText(
                                        modifier = Modifier.weight(1f),
                                        text = item.text,
                                        entities = item.entities,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                            textDecoration = if (item.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                                        ),
                                        contentColor = itemColor,
                                        linkColor = MaterialTheme.colorScheme.primary,
                                        revealSpoilers = spoilersVisible,
                                        onSpoilerClick = { spoilersVisible = !spoilersVisible },
                                        onOpenStickerPack = onOpenStickerPack,
                                    )
                                    val canManage =
                                        message.outgoing && !message.pending && message.id.id > 0
                                    val canRemove =
                                        canManage && onRemoveChecklistItem != null && list.items.size > 1
                                    val canEdit = canManage && onEditChecklistItem != null
                                    var confirmRemoval by rememberSaveable(
                                        message.id,
                                        item.id
                                    ) { mutableStateOf(false) }
                                    if (canRemove || canEdit) {
                                        var menuOpen by remember { mutableStateOf(false) }
                                        Box {
                                            IconButton(
                                                onClick = { menuOpen = true },
                                                enabled = !checklistBusy,
                                                shapes = IconButtonDefaults.shapes()
                                            ) {
                                                Icon(
                                                    Icons.Outlined.MoreVert,
                                                    contentDescription = stringResource(
                                                        org.monogram.core.ui.R.string.media_more_actions
                                                    )
                                                )
                                            }
                                            AppMenuPopup(
                                                menuOpen,
                                                { menuOpen = false },
                                                alignToAnchorEnd = true,
                                                scrim = true
                                            ) {
                                                AppMenuSurface {
                                                    Column {
                                                        if (canEdit) AppMenuItem(
                                                            text = stringResource(
                                                                R.string.dialog_checklist_edit_item,
                                                                item.text
                                                            ),
                                                            icon = Icons.Outlined.Edit,
                                                            onClick = {
                                                                menuOpen =
                                                                    false; onEditChecklistItem?.invoke(
                                                                item.id
                                                            )
                                                            },
                                                        )
                                                        if (canRemove) AppMenuItem(
                                                            text = stringResource(
                                                                R.string.dialog_checklist_remove_item,
                                                                item.text
                                                            ),
                                                            icon = Icons.Outlined.Delete,
                                                            onClick = {
                                                                menuOpen = false; confirmRemoval =
                                                                true
                                                            },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (confirmRemoval && canRemove) androidx.compose.material3.AlertDialog(
                                        onDismissRequest = { confirmRemoval = false },
                                        text = {
                                            Text(
                                                stringResource(
                                                    R.string.dialog_checklist_remove_confirm,
                                                    item.text
                                                )
                                            )
                                        },
                                        confirmButton = {
                                            androidx.compose.material3.TextButton(
                                                enabled = !checklistBusy,
                                                onClick = {
                                                    confirmRemoval =
                                                        false; onRemoveChecklistItem?.invoke(item.id)
                                                }) { Text(stringResource(R.string.dialog_delete)) }
                                        },
                                        dismissButton = {
                                            androidx.compose.material3.TextButton(onClick = {
                                                confirmRemoval = false
                                            }) {
                                                Text(stringResource(R.string.dialog_cancel))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        if (addEnabled) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.large)
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .heightIn(min = 48.dp)
                                    .clickable(enabled = !checklistBusy) {
                                        val nextId =
                                            (list.items.maxOfOrNull { it.id } ?: 0).toLong() + 1
                                        if (nextId <= Int.MAX_VALUE) onAddChecklistItem.invoke(
                                            message.id.id, nextId.toInt()
                                        )
                                    }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(
                                    8.dp,
                                    Alignment.CenterHorizontally
                                ),
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
                if (mediaCaption && !mediaOverlayMeta && message.text != message.checklist?.title) {
                    var textLayoutInfo by remember { mutableStateOf<MessageTextLayoutInfo?>(null) }
                    val hasWideChrome =
                        message.replyToMsgId != null || !quote.isNullOrBlank() || message.mediaKind != null || showSender || !message.fwdFrom.isNullOrBlank() || !message.viaBot.isNullOrBlank()
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
                AnimatedVisibility(
                    visible = onRetrySend != null,
                    enter = fadeIn(tween(160)) + expandVertically(
                        tween(220),
                        expandFrom = Alignment.Top
                    ),
                    exit = fadeOut(tween(100)) + shrinkVertically(
                        tween(180),
                        shrinkTowards = Alignment.Top
                    ),
                ) {
                    OutgoingBubbleFailure(sendUncertain, sendError) { onRetrySend?.invoke() }
                }
                if (onComments != null && message.discussionPeerId != null) {
                    CommentFooter(
                        repliesCount = message.repliesCount,
                        onClick = onComments,
                        contentColor = onContainer,
                        modifier = Modifier
                            .fillMaxWidthInBubble()
                            .bleedCommentFooter(edgeVisualMedia),
                    )
                }
            }
            val inlineMarkup = message.replyMarkup?.takeIf {
                it.kind == ReplyMarkupKind.Inline && it.rows.isNotEmpty()
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
                        .fillMaxWidthInBubble()
                        .then(
                            if (hasComments) {
                                Modifier.padding(start = 2.dp, end = 2.dp, top = 4.dp)
                            } else {
                                Modifier
                                    .offset(y = (-4).dp)
                                    .padding(start = 2.dp, end = 2.dp, top = 6.dp)
                            },
                        ),
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
    bleedBottom: Boolean = false,
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
    val height =
        (placeable.height - (if (bleedTop) vInset else 0) - (if (bleedBottom) vInset else 0)).coerceAtLeast(
            0
        )
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

private suspend fun PointerInputScope.menuOrHold(
    onTap: (Offset) -> Unit,
    onLongPress: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var claimed = false
        val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis + 48) {
            var current = down
            while (current.pressed) {
                val event = awaitPointerEvent()
                val change =
                    event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                if (change.isConsumed) {
                    claimed = true
                    return@withTimeoutOrNull false
                }
                val delta = change.position - down.position
                if (delta.x * delta.x + delta.y * delta.y > slop * slop) {
                    return@withTimeoutOrNull false
                }
                current = change
            }
            true
        }
        when {
            released == true -> {
                down.consume()
                onTap(down.position)
            }

            released == false || claimed || down.isConsumed -> return@awaitEachGesture
            else -> onLongPress()
        }
        if (released != null) return@awaitEachGesture
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            change.consume()
        }
    }
}