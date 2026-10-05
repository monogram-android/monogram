package org.monogram.feature.dialog.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import org.monogram.core.common.Outcome
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.canForwardFrom
import org.monogram.core.ui.media.MediaAlbumState
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.MediaSource
import org.monogram.core.ui.media.MediaViewerActions
import org.monogram.core.ui.media.MediaViewerHost
import org.monogram.core.ui.media.MediaViewerItem
import org.monogram.core.ui.media.LocalPictureInPictureController
import org.monogram.core.ui.media.MediaViewerKind
import org.monogram.core.ui.media.CaptionEntity
import org.monogram.core.ui.media.MediaHdr
import org.monogram.core.ui.media.rememberAlbumState
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository
import org.monogram.network.http.photoDisplayCacheKey
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Mutable holder so the dialog screen can publish the chat title into the viewer. */
internal class ChatTitleHolder {
    var value by mutableStateOf<String?>(null)
}

/** Chat title, published by the dialog screen so the viewer never says "Unknown sender". */
internal val LocalChatTitleHolder = staticCompositionLocalOf<ChatTitleHolder?> { null }

/** The album the tapped cell belongs to, published by the mosaic. */
internal val LocalAlbumMessages = staticCompositionLocalOf<List<Message>> { emptyList() }

internal val LocalOpenMessageMedia = staticCompositionLocalOf<(Message, List<Message>) -> Unit> {
    { _, _ -> error("Message media must be hosted by MessageMediaViewerScope") }
}

internal class ReadReceiptHolder {
    var label: ((Message) -> String?)? by mutableStateOf(null)
    var viewers: ((Message) -> org.monogram.core.models.MessageViewers?)? by mutableStateOf(null)
    var avatar: ((org.monogram.core.models.MessageViewer) -> File?)? by mutableStateOf(null)
}

internal val LocalReadReceiptHolder = staticCompositionLocalOf<ReadReceiptHolder?> { null }

/** Brings the still-running playback session back to the full viewer from the mini player. */
internal val LocalReopenMediaViewer = staticCompositionLocalOf<() -> Boolean> { { false } }

private fun messageKey(message: Message): String = "${message.id.chatId.value}:${message.id.id}"

private fun MediaRepository.streamUri(message: Message): Uri =
    Uri.parse("telegram://media/${message.id.chatId.value}/${message.id.id}")

/** Only a small descriptor is saved, never file bytes or the chat's history. */
internal val MessageSaver = listSaver<Message?, Any>(
    save = { message ->
        if (message == null) emptyList() else encodeViewerMessages(listOf(message))
    },
    restore = { values -> if (values.isEmpty()) null else decodeMessages(values).firstOrNull() },
)

/** Album identity survives rotation without keeping the whole history in a Bundle. */
internal val AlbumSaver = listSaver<List<Message>, Any>(
    save = { messages -> encodeViewerMessages(messages) },
    restore = { values -> decodeMessages(values) },
)

private const val VIEWER_MESSAGE_FORMAT = "message-media-viewer"
private const val VIEWER_MESSAGE_VERSION = 1

private fun encodeViewerMessages(messages: List<Message>): List<Any> =
    listOf(VIEWER_MESSAGE_FORMAT, VIEWER_MESSAGE_VERSION) + messages.map { message ->
        arrayListOf<Any?>(
            message.id.chatId.value, message.id.id, message.mediaKind,
            message.mediaCacheKey, message.thumbCacheKey, message.text, message.fileSize,
            message.mediaDuration, message.mediaWidth, message.mediaHeight,
            message.date, message.noforwards, message.groupedId, message.senderId?.value,
            message.senderName, message.outgoing, message.fileName, message.supportsStreaming,
            message.reactionsJson,
            ArrayList(message.entities.map { entity ->
                arrayListOf<Any?>(entity.kind, entity.offset, entity.length, entity.url)
            }),
        )
    }

private fun decodeMessages(values: List<Any>): List<Message> {
    if (values.firstOrNull() == VIEWER_MESSAGE_FORMAT) {
        if (values.getOrNull(1) != VIEWER_MESSAGE_VERSION) return emptyList()
        return values.drop(2).map { saved ->
            val fields = saved as List<*>
            Message(
                id = MessageId(PeerId(fields[0] as Long), fields[1] as Int),
                mediaKind = fields[2] as String?,
                mediaCacheKey = fields[3] as String?,
                thumbCacheKey = fields[4] as String?,
                text = fields[5] as String?,
                fileSize = fields[6] as Long?,
                mediaDuration = fields[7] as Int?,
                mediaWidth = fields[8] as Int?,
                mediaHeight = fields[9] as Int?,
                date = fields[10] as Long,
                noforwards = fields[11] as Boolean,
                groupedId = fields[12] as Long?,
                senderId = (fields[13] as Long?)?.let(::PeerId),
                senderName = fields[14] as String?,
                outgoing = fields[15] as Boolean,
                fileName = fields[16] as String?,
                supportsStreaming = fields[17] as Boolean,
                reactionsJson = fields[18] as String?,
                entities = (fields[19] as List<*>).map { savedEntity ->
                    val entity = savedEntity as List<*>
                    org.monogram.core.models.TextEntity(
                        kind = entity[0] as String,
                        offset = entity[1] as Int,
                        length = entity[2] as Int,
                        url = entity[3] as String?,
                    )
                },
            )
        }
    }
    val recordSize = if (values.size == 7) 7 else 12
    if (values.size % recordSize != 0) return emptyList()
    return values.chunked(recordSize).map { fields ->
        Message(
            id = MessageId(PeerId(fields[0] as Long), fields[1] as Int),
            senderId = null,
            outgoing = false,
            mediaKind = (fields[2] as String).ifEmpty { null },
            mediaCacheKey = (fields[3] as String).ifEmpty { null },
            thumbCacheKey = (fields[4] as String).ifEmpty { null },
            text = (fields[5] as String).ifEmpty { null },
            fileSize = (fields[6] as Long).takeIf { it >= 0L },
            mediaDuration = (fields.getOrNull(7) as? Int)?.takeIf { it > 0 },
            mediaWidth = (fields.getOrNull(8) as? Int)?.takeIf { it > 0 },
            mediaHeight = (fields.getOrNull(9) as? Int)?.takeIf { it > 0 },
            date = fields.getOrNull(10) as? Long ?: 0L,
            noforwards = fields.getOrNull(11) == 1L,
        )
    }
}

private class Resolved(
    val file: File?,
    val loading: Boolean,
    val failed: Boolean,
)

/**
 * Owns the modal outside lazy rows so resizing or recycling a row cannot close it.
 * Resolves only the current page and its immediate neighbors on demand.
 */
@Composable
internal fun MessageMediaViewerScope(
    repository: MediaRepository?,
    chatCanForward: Boolean = true,
    onForward: (List<Message>) -> Unit = {},
    onDelete: (List<Message>, Boolean) -> Unit = { _, _ -> },
    deleteOffer: (List<Message>) -> DeleteOffer = { DeleteOffer(forMe = true, forEveryone = false) },
    onShowInChat: (Message) -> Unit = {},
    onReply: ((Message) -> Unit)? = null,
    onEnsureReceipts: (Message) -> Unit = {},
    seenByLabel: ((Message) -> String?)? = null,
    onOpenSeenBy: ((Message) -> Unit)? = null,
    knownAlbumMessages: ((Message) -> List<Message>)? = null,
    onCaptionUrl: (String) -> Unit = {},
    onCaptionMention: (String) -> Unit = {},
    content: @Composable () -> Unit,
) {
    var selected by rememberSaveable(stateSaver = MessageSaver) { mutableStateOf<Message?>(null) }
    var album by rememberSaveable(stateSaver = AlbumSaver) { mutableStateOf<List<Message>>(emptyList()) }
    val titleHolder = remember { ChatTitleHolder() }
    val receiptHolder = LocalReadReceiptHolder.current
    val context = LocalContext.current
    val session = remember(context) { MediaPlaybackHolder.session(context) }
    val reopen: () -> Boolean = {
        val target = session.current?.id
        val message = album.firstOrNull { messageKey(it) == target } ?: selected
        if (message != null) {
            selected = message
            true
        } else {
            false
        }
    }
    val open: (Message, List<Message>) -> Unit = { message, albumMessages ->
        album = (albumMessages + message).distinctBy { it.id }
        selected = message
    }
    val groupedMessage = selected?.takeIf { it.groupedId != null }
    val knownSiblings = groupedMessage?.let { knownAlbumMessages?.invoke(it) }.orEmpty()
    LaunchedEffect(groupedMessage?.id, knownSiblings) {
        val message = groupedMessage ?: return@LaunchedEffect
        val siblings = knownSiblings.filter {
            it.id.chatId == message.id.chatId && it.groupedId == message.groupedId
        }
        if (siblings.isNotEmpty()) {
            album = (album + siblings).distinctBy { it.id }.sortedBy { it.id.id }
        }
    }
    CompositionLocalProvider(
        LocalOpenMessageMedia provides open,
        LocalChatTitleHolder provides titleHolder,
        LocalReadReceiptHolder provides receiptHolder,
        LocalReopenMediaViewer provides reopen,
        content = content,
    )
    selected?.let { message ->
        val startIndex = album.indexOfFirst { it.id == message.id }.coerceAtLeast(0)
        org.monogram.core.ui.media.MediaViewerOverlay {
            key(message.id) {
                MessageMediaViewer(
                    album = album.ifEmpty { listOf(message) },
                    startIndex = startIndex,
                    repository = repository,
                    chatTitle = titleHolder.value,
                    chatCanForward = chatCanForward,
                    onForward = onForward,
                    onDelete = onDelete,
                    deleteOffer = deleteOffer,
                    onShowInChat = onShowInChat,
                    onReply = onReply,
                    onEnsureReceipts = onEnsureReceipts,
                    seenByLabel = seenByLabel ?: receiptHolder?.label,
                    onOpenSeenBy = onOpenSeenBy,
                    onCaptionUrl = onCaptionUrl,
                    onCaptionMention = onCaptionMention,
                    onDismiss = { selected = null },
                )
            }
        }
    }
}

@Composable
private fun MessageMediaViewer(
    album: List<Message>,
    startIndex: Int,
    repository: MediaRepository?,
    chatTitle: String?,
    chatCanForward: Boolean,
    onForward: (List<Message>) -> Unit,
    onDelete: (List<Message>, Boolean) -> Unit,
    deleteOffer: (List<Message>) -> DeleteOffer,
    onShowInChat: (Message) -> Unit,
    onReply: ((Message) -> Unit)?,
    onEnsureReceipts: (Message) -> Unit,
    seenByLabel: ((Message) -> String?)?,
    onOpenSeenBy: ((Message) -> Unit)?,
    onCaptionUrl: (String) -> Unit,
    onCaptionMention: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resolved = remember { mutableStateMapOf<String, Resolved>() }
    var pendingDelete by remember { mutableStateOf<List<Message>?>(null) }
    var pendingSaveAsPath by rememberSaveable { mutableStateOf<String?>(null) }
    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val source = pendingSaveAsPath?.let(::File)
        pendingSaveAsPath = null
        val destination = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && source != null && destination != null) {
            scope.launch {
                val saved = MediaShareActions.saveToDocument(context, source, destination)
                Toast.makeText(
                    context,
                    if (saved) R.string.media_action_saved else R.string.media_action_save_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val inFlight = remember { mutableMapOf<String, Pair<Int, Job>>() }
    val session = remember(context) { MediaPlaybackHolder.session(context) }

    fun resolve(message: Message, force: Boolean = false, priority: Int = MediaPriority.USER) {
        val key = messageKey(message)
        val video = message.mediaKind == "video" || message.mediaKind == "gif" || message.mediaKind == "video_note"
        val cached = message.mediaCacheKey?.let { repository?.cachedFile(it) }
        if (cached != null) {
            resolved[key] = Resolved(cached, loading = false, failed = false)
            return
        }
        if (repository == null) return
        val existing = inFlight[key]
        if (!force && existing != null && existing.first >= priority) return
        existing?.second?.cancel()
        resolved[key] = Resolved(null, loading = !video, failed = false)
        lateinit var request: Job
        request = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.ensureLocalMessageMedia(message, priority)
                }
                when (result) {
                    is Outcome.Ok -> resolved[key] = Resolved(result.value, loading = false, failed = false)
                    is Outcome.Err -> resolved[key] = Resolved(null, loading = false, failed = true)
                }
            } finally {
                if (inFlight[key]?.second === request) inFlight.remove(key)
            }
        }
        inFlight[key] = priority to request
        request.start()
    }

    val progressFlow = remember(repository) { repository?.downloadProgress ?: flowOf(emptyMap()) }
    val downloadProgress by progressFlow.collectAsStateWithLifecycle(initialValue = emptyMap())
    val items = album.mapIndexed { albumIndex, message ->
        val key = messageKey(message)
        val video = message.mediaKind == "video" || message.mediaKind == "gif" || message.mediaKind == "video_note"
        val kind = viewerKind(message.mediaKind)
        val state = resolved[key]
        val localFile = state?.file
        val preview = remember(key, repository) {
            message.mediaCacheKey?.let { repository?.cachedFile(photoDisplayCacheKey(it)) }
                ?: message.thumbCacheKey?.let { repository?.cachedFile(it) }
        }
        MediaViewerItem(
            id = key,
            kind = kind,
            source = when {
                localFile != null -> MediaSource.Local(localFile)
                kind in listOf(MediaViewerKind.VIDEO, MediaViewerKind.VIDEO_NOTE, MediaViewerKind.AUDIO, MediaViewerKind.VOICE) && repository != null -> MediaSource.Stream(
                    uri = repository.streamUri(message),
                    factory = repository.createMessageDataSourceFactory(message),
                )
                else -> null
            },
            // Photos keep their cached thumbnail as the placeholder and the filmstrip
            // cell, so an album strip is never a row of empty squares.
            preview = preview,
            caption = message.text,
            captionEntities = message.entities.mapNotNull { entity ->
                when (entity.kind.lowercase(Locale.ROOT)) {
                    "text_url", "url" -> CaptionEntity(entity.offset, entity.length, CaptionEntity.Type.URL, entity.url)
                    "mention" -> CaptionEntity(entity.offset, entity.length, CaptionEntity.Type.MENTION)
                    "mention_name", "text_mention" -> CaptionEntity(
                        entity.offset, entity.length, CaptionEntity.Type.MENTION,
                        entity.url?.toLongOrNull()?.takeIf { it > 0 }?.let { "tg://user?id=$it" },
                    )
                    "bold" -> CaptionEntity(entity.offset, entity.length, CaptionEntity.Type.BOLD)
                    else -> null
                }
            },
            durationSeconds = message.mediaDuration,
            albumIndex = albumIndex,
            albumCount = album.size,
            groupedId = message.groupedId,
            spoiler = message.entities.any { it.kind.equals("spoiler", ignoreCase = true) },
            width = message.mediaWidth,
            height = message.mediaHeight,
            progress = message.fileSize?.takeIf { it > 0L }?.let { size ->
                downloadProgress[message.mediaCacheKey]?.let { (it.toDouble() / size).toFloat().coerceIn(0f, 1f) }
            },
            aspectRatio = mediaAspectRatio(message.mediaWidth, message.mediaHeight),
            senderName = chatTitle ?: message.senderName,
            dateLabel = relativeDateLabel(message.date),
            dateMillis = message.date * 1000L,
            protectedContent = !chatCanForward || message.noforwards,
            loading = kind != MediaViewerKind.UNKNOWN && (state?.loading ?: !video),
            failed = kind == MediaViewerKind.UNKNOWN || state?.failed == true,
            sourceChatId = message.id.chatId.value,
            sourceMessageId = message.id.id,
            fileSize = message.fileSize,
            fileName = message.fileName,
            forceLoop = message.mediaKind == "gif",
        )
    }
    val albumState = rememberAlbumState(items, startIndex)
    val activeId = albumState.current?.id
    LaunchedEffect(activeId, album.map { it.id }) {
        val currentIndex = album.indexOfFirst { messageKey(it) == activeId }
        if (currentIndex < 0) return@LaunchedEffect
        val window = (currentIndex - 1..currentIndex + 1).mapNotNull(album::getOrNull)
        val windowIds = window.mapTo(mutableSetOf(), ::messageKey)
        inFlight.toMap().forEach { (id, request) ->
            if (request.first == MediaPriority.IDLE && id !in windowIds) {
                inFlight.remove(id)
                request.second.cancel()
                album.firstOrNull { messageKey(it) == id }?.mediaCacheKey?.let {
                    repository?.cancelRunning(it)
                }
            }
        }
        resolve(album[currentIndex], priority = MediaPriority.USER)
        window.filter { messageKey(it) != activeId }.forEach {
            resolve(it, priority = MediaPriority.IDLE)
        }
    }
    val latestAlbum by rememberUpdatedState(album)
    DisposableEffect(repository) {
        onDispose {
            inFlight.forEach { (id, request) ->
                if (request.first == MediaPriority.IDLE) {
                    latestAlbum.firstOrNull { messageKey(it) == id }?.mediaCacheKey?.let {
                        repository?.cancelRunning(it)
                    }
                }
            }
        }
    }
    val pictureInPicture = LocalPictureInPictureController.current

    fun withLocalMedia(item: MediaViewerItem, action: (File) -> Unit) {
        val message = album.firstOrNull { messageKey(it) == item.id } ?: return
        val cached = resolved[item.id]?.file ?: message.mediaCacheKey?.let { repository?.cachedFile(it) }
        if (cached != null && cached.exists()) {
            action(cached)
            return
        }
        if (repository == null) return
        Toast.makeText(context, R.string.media_action_preparing, Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.ensureLocalMessageMedia(message, MediaPriority.USER)
            }
            val file = (result as? Outcome.Ok)?.value
            if (file == null || !file.exists()) {
                Toast.makeText(context, R.string.dialog_media_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            resolved[item.id] = Resolved(file, loading = false, failed = false)
            action(file)
        }
    }

    fun mediaName(item: MediaViewerItem, file: File): String {
        val named = item.fileName?.takeIf { it.isNotBlank() }
        val base = named ?: "monogram_${item.id.replace(':', '_')}"
        val mime = MediaShareActions.mimeFor(base, item.isVideo)
        return if (base.substringAfterLast('.', "").isNotBlank()) {
            base
        } else {
            "$base.${MediaShareActions.extensionFor(mime)}"
        }
    }

    val actions = MediaViewerActions(
        onCaptionUrl = { url -> onDismiss(); onCaptionUrl(url) },
        onCaptionMention = { mention -> onDismiss(); onCaptionMention(mention) },
        onReply = messageMediaReplyAction(
            album = album,
            onDismiss = onDismiss,
            onReply = onReply.takeIf {
                album.firstOrNull { messageKey(it) == activeId }
                    ?.let { !it.pending && it.id.id > 0 } == true
            },
        ),
        onShare = { item ->
            val targets = albumState.selectedItems().filterNot { it.protectedContent }
            scope.launch {
                val files = withContext(Dispatchers.IO) {
                    targets.mapNotNull { target ->
                        val message = album.firstOrNull { messageKey(it) == target.id } ?: return@mapNotNull null
                        val cached = (target.source as? MediaSource.Local)?.file
                        cached ?: (repository?.ensureLocalMessageMedia(message, MediaPriority.USER) as? Outcome.Ok)?.value
                    }
                }
                if (files.size != targets.size || files.isEmpty()) {
                    Toast.makeText(context, R.string.dialog_media_failed, Toast.LENGTH_SHORT).show()
                } else {
                    val mime = if (targets.all { it.kind == MediaViewerKind.PHOTO }) "image/*"
                        else if (targets.all { it.isVideo }) "video/*" else "*/*"
                    val shared = if (files.size == 1) MediaShareActions.share(context, files.first(), mime)
                        else MediaShareActions.shareMultiple(context, files, mime)
                    if (!shared) Toast.makeText(context, R.string.media_action_no_app, Toast.LENGTH_SHORT).show()
                }
            }
        },
        onSaveAs = { item ->
            if (!item.protectedContent && pendingSaveAsPath == null) {
                withLocalMedia(item) { file ->
                    pendingSaveAsPath = file.absolutePath
                    val name = mediaName(item, file)
                    try {
                        saveAsLauncher.launch(MediaShareActions.createDocumentIntent(
                            MediaShareActions.mimeFor(name, item.isVideo), name,
                        ))
                    } catch (_: android.content.ActivityNotFoundException) {
                        pendingSaveAsPath = null
                        Toast.makeText(context, R.string.media_action_no_app, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        },
        onSave = { item ->
            albumState.selectedItems().filterNot { it.protectedContent }.forEach { target ->
            withLocalMedia(target) { file ->
                scope.launch {
                    val mime = MediaShareActions.mimeFor(mediaName(target, file), target.isVideo)
                    val saved = MediaShareActions.saveToGallery(context, file, mime, mediaName(target, file))
                    Toast.makeText(
                        context,
                        if (saved != null) R.string.media_action_saved else R.string.media_action_save_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            }
        },
        onCopyMedia = { item ->
            withLocalMedia(item) { file ->
                val mime = MediaShareActions.mimeFor(mediaName(item, file), item.isVideo)
                scope.launch {
                    val copied = withContext(Dispatchers.IO) { MediaShareActions.copyToClipboard(context, file, mime) }
                    Toast.makeText(
                        context,
                        when {
                            !copied -> R.string.media_action_save_failed
                            item.hdr == MediaHdr.GainMap -> org.monogram.core.ui.R.string.media_copy_hdr_warning
                            item.isVideo -> R.string.media_action_copied_video
                            else -> R.string.media_action_copied_image
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        },
        onCopyCaption = { item ->
            val caption = item.caption
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (!caption.isNullOrBlank()) {
                clipboard.setPrimaryClip(ClipData.newPlainText("caption", caption))
            }
        },
        onForward = { item, wholeAlbum ->
            val ids = albumState.selectedItems().mapTo(mutableSetOf()) { it.id }
            val messages = (if (wholeAlbum) album else {
                album.filter { messageKey(it) in ids }
            }).filter { it.canForwardFrom(chatCanForward) }
            if (messages.isNotEmpty()) {
                onForward(messages)
                onDismiss()
            }
        },
        onDelete = { item, wholeAlbum ->
            val ids = albumState.selectedItems().mapTo(mutableSetOf()) { it.id }
            val targets = if (wholeAlbum) album else album.filter { messageKey(it) in ids }
            if (deleteOffer(targets).visible) pendingDelete = targets
        },
        onShowInChat = { item ->
            album.firstOrNull { messageKey(it) == item.id }?.let(onShowInChat)
            onDismiss()
        },
        onOpenExternally = { item ->
            withLocalMedia(item) { file ->
                val mime = MediaShareActions.mimeFor(mediaName(item, file), item.isVideo)
                if (!MediaShareActions.openExternally(context, file, mime)) {
                    Toast.makeText(context, R.string.media_action_no_app, Toast.LENGTH_SHORT).show()
                }
            }
        },
        onRetry = { item -> album.firstOrNull { messageKey(it) == item.id }?.let { resolve(it, force = true) } },
        seenByLabel = seenByLabel?.let { label ->
            { item -> album.firstOrNull { messageKey(it) == item.id }?.let(label) }
        },
        onOpenSeenBy = onOpenSeenBy?.let { open ->
            { item -> album.firstOrNull { messageKey(it) == item.id }?.let(open) }
        },
        canRetry = true,
        canDelete = album.any { deleteOffer(listOf(it)).visible },
        canForward = chatCanForward,
        canPictureInPicture = pictureInPicture?.supported == true,
        onEnterPictureInPicture = { pictureInPicture?.enter() },
        onListenInBackground = {
            session.listenInBackground()
            onDismiss()
        },
    )

    MediaViewerHost(
        album = albumState,
        onDismiss = onDismiss,
        actions = actions,
        chatKey = album.firstOrNull()?.id?.chatId?.value?.toString().orEmpty(),
        headerTitle = chatTitle,
        session = session,
        onRequestItem = { item ->
            val target = album.firstOrNull { messageKey(it) == item.id } ?: return@MediaViewerHost
            val targetIndex = album.indexOfFirst { it.id == target.id }
            if (kotlin.math.abs(targetIndex - albumState.index) <= 1) {
                val priority = if (targetIndex == albumState.index) MediaPriority.USER else MediaPriority.IDLE
                resolve(target, priority = priority)
            }
        },
        onIndexChange = { index -> album.getOrNull(index)?.let(onEnsureReceipts) },
        testTagPrefix = "media-video",
    )

    pendingDelete?.let { targets ->
        val offer = deleteOffer(targets)
        if (offer.visible) {
            DeleteMessagesDialog(
                count = targets.size,
                offer = offer,
                onDismiss = { pendingDelete = null },
                onConfirm = { forEveryone ->
                    pendingDelete = null
                    onDelete(targets, deleteRevoke(forEveryone))
                    onDismiss()
                },
            )
        }
    }
}

internal fun messageMediaReplyAction(
    album: List<Message>,
    onDismiss: () -> Unit,
    onReply: ((Message) -> Unit)?,
): ((MediaViewerItem) -> Unit)? = onReply?.let { reply ->
    { item ->
        album.firstOrNull { messageKey(it) == item.id && !it.pending && it.id.id > 0 }?.let { message ->
            onDismiss()
            reply(message)
        }
    }
}

internal fun viewerKind(mediaKind: String?): MediaViewerKind = when (mediaKind?.lowercase(Locale.ROOT)) {
    "photo" -> MediaViewerKind.PHOTO
    "video" -> MediaViewerKind.VIDEO
    "gif" -> MediaViewerKind.VIDEO
    "video_note" -> MediaViewerKind.VIDEO_NOTE
    "voice" -> MediaViewerKind.VOICE
    "audio" -> MediaViewerKind.AUDIO
    else -> MediaViewerKind.UNKNOWN
}


/** "14:32" today, "yesterday", "12 Sep" this year, otherwise a dated label. */
internal fun relativeDateLabel(epochSeconds: Long, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
    if (epochSeconds <= 0L) return ""
    val zone = ZoneId.systemDefault()
    val moment = Instant.ofEpochSecond(epochSeconds).atZone(zone)
    val today = Instant.ofEpochSecond(nowSeconds).atZone(zone).toLocalDate()
    val day = moment.toLocalDate()
    return when {
        day == today -> moment.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
        day == today.minusDays(1) -> "yesterday"
        day.isAfter(today.minusDays(7)) -> moment.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
        day.year == today.year -> moment.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        else -> moment.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
    }
}
