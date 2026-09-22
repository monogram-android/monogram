package org.monogram.feature.dialog.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
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
private val MessageSaver = listSaver<Message?, Any>(
    save = { message ->
        if (message == null) emptyList() else listOf(
            message.id.chatId.value, message.id.id, message.mediaKind.orEmpty(),
            message.mediaCacheKey.orEmpty(), message.thumbCacheKey.orEmpty(),
            message.text.orEmpty(), message.fileSize ?: -1L,
        )
    },
    restore = { values -> if (values.isEmpty()) null else decodeMessages(values).firstOrNull() },
)

/** Album identity survives rotation without keeping the whole history in a Bundle. */
private val AlbumSaver = listSaver<List<Message>, Any>(
    save = { messages -> messages.flatMap { encodeMessage(it) } },
    restore = { values -> decodeMessages(values) },
)

private fun encodeMessage(message: Message): List<Any> = listOf(
    message.id.chatId.value, message.id.id, message.mediaKind.orEmpty(),
    message.mediaCacheKey.orEmpty(), message.thumbCacheKey.orEmpty(),
    message.text.orEmpty(), message.fileSize ?: -1L,
    message.mediaDuration ?: -1, message.mediaWidth ?: -1, message.mediaHeight ?: -1,
    message.date, if (message.noforwards) 1L else 0L,
)

private fun decodeMessages(values: List<Any>): List<Message> {
    val out = ArrayList<Message>()
    var index = 0
    while (index + 7 <= values.size) {
        val chatId = values[index] as Long
        val id = values[index + 1] as Int
        val kind = values[index + 2] as String
        val cacheKey = values[index + 3] as String
        val thumbKey = values[index + 4] as String
        val text = values[index + 5] as String
        val size = values[index + 6] as Long
        val duration = values.getOrNull(index + 7) as? Int ?: -1
        val width = values.getOrNull(index + 8) as? Int ?: -1
        val height = values.getOrNull(index + 9) as? Int ?: -1
        val date = values.getOrNull(index + 10) as? Long ?: 0L
        val noforwards = (values.getOrNull(index + 11) as? Long ?: 0L) == 1L
        out += Message(
            id = MessageId(PeerId(chatId), id),
            senderId = null, date = date, outgoing = false,
            mediaKind = kind.ifEmpty { null },
            mediaCacheKey = cacheKey.ifEmpty { null },
            thumbCacheKey = thumbKey.ifEmpty { null },
            text = text.ifEmpty { null },
            fileSize = size.takeIf { it >= 0L },
            mediaDuration = duration.takeIf { it > 0 },
            mediaWidth = width.takeIf { it > 0 },
            mediaHeight = height.takeIf { it > 0 },
            noforwards = noforwards,
        )
        index += 12
    }
    return out
}

private class Resolved(
    val file: File?,
    val loading: Boolean,
    val failed: Boolean,
)

/**
 * Owns the modal outside lazy rows so resizing or recycling a row cannot close it.
 * Resolves album media on demand: the shell only asks for the current page and the
 * next one, so opening item 3 of 8 does not download the other nine.
 */
@Composable
internal fun MessageMediaViewerScope(
    repository: MediaRepository?,
    chatCanForward: Boolean = true,
    onForward: (List<Message>) -> Unit = {},
    onDelete: (List<Message>) -> Unit = {},
    onShowInChat: (Message) -> Unit = {},
    onEnsureReceipts: (Message) -> Unit = {},
    seenByLabel: ((Message) -> String?)? = null,
    onOpenSeenBy: ((Message) -> Unit)? = null,
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
        val resolvedAlbum = albumMessages.ifEmpty { listOf(message) }
        album = resolvedAlbum
        selected = message
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
        key(message.id) {
            MessageMediaViewer(
                album = album.ifEmpty { listOf(message) },
                startIndex = startIndex,
                repository = repository,
                chatTitle = titleHolder.value,
                chatCanForward = chatCanForward,
                onForward = onForward,
                onDelete = onDelete,
                onShowInChat = onShowInChat,
                onEnsureReceipts = onEnsureReceipts,
                seenByLabel = seenByLabel ?: receiptHolder?.label,
                onOpenSeenBy = onOpenSeenBy,
                onDismiss = { selected = null },
            )
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
    onDelete: (List<Message>) -> Unit,
    onShowInChat: (Message) -> Unit,
    onEnsureReceipts: (Message) -> Unit,
    seenByLabel: ((Message) -> String?)?,
    onOpenSeenBy: ((Message) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resolved = remember { mutableStateMapOf<String, Resolved>() }
    var pendingDelete by remember { mutableStateOf<List<Message>?>(null) }
    val inFlight = remember { mutableStateMapOf<String, Boolean>() }
    val session = remember(context) { MediaPlaybackHolder.session(context) }

    fun resolve(message: Message, force: Boolean = false) {
        val key = messageKey(message)
        val video = message.mediaKind == "video" || message.mediaKind == "gif"
        val cached = message.mediaCacheKey?.let { repository?.cachedFile(it) }
        if (cached != null) {
            resolved[key] = Resolved(cached, loading = false, failed = false)
            return
        }
        if (repository == null) return
        if (!force && inFlight[key] == true) return
        inFlight[key] = true
        resolved[key] = Resolved(null, loading = !video, failed = false)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.ensureLocalMessageMedia(message, MediaPriority.USER)
            }
            inFlight[key] = false
            when (result) {
                is Outcome.Ok -> resolved[key] = Resolved(result.value, loading = false, failed = false)
                is Outcome.Err -> resolved[key] = Resolved(null, loading = false, failed = true)
            }
        }
    }

    LaunchedEffect(album, startIndex) {
        album.getOrNull(startIndex)?.let { resolve(it) }
        album.getOrNull(startIndex + 1)?.let { resolve(it) }
    }

    LaunchedEffect(album, startIndex) {
        album.getOrNull(startIndex)?.let(onEnsureReceipts)
    }

    val items = album.map { message ->
        val key = messageKey(message)
        val video = message.mediaKind == "video" || message.mediaKind == "gif"
        val state = resolved[key]
        val localFile = state?.file
        val preview = remember(key, repository) {
            message.mediaCacheKey?.let { repository?.cachedFile(photoDisplayCacheKey(it)) }
                ?: message.thumbCacheKey?.let { repository?.cachedFile(it) }
        }
        MediaViewerItem(
            id = key,
            kind = if (video) MediaViewerKind.VIDEO else MediaViewerKind.PHOTO,
            source = when {
                localFile != null -> MediaSource.Local(localFile)
                video && repository != null -> MediaSource.Stream(
                    uri = repository.streamUri(message),
                    factory = repository.createMessageDataSourceFactory(message),
                )
                else -> null
            },
            // Photos keep their cached thumbnail as the placeholder and the filmstrip
            // cell, so an album strip is never a row of empty squares.
            preview = preview,
            caption = message.text,
            durationSeconds = message.mediaDuration,
            aspectRatio = mediaAspectRatio(message.mediaWidth, message.mediaHeight),
            senderName = chatTitle ?: message.senderName,
            dateLabel = relativeDateLabel(message.date),
            dateMillis = message.date * 1000L,
            protectedContent = !chatCanForward || message.noforwards,
            loading = state?.loading ?: !video,
            failed = state?.failed == true,
            fileSize = message.fileSize,
            fileName = message.fileName,
            forceLoop = message.mediaKind == "gif",
        )
    }
    val albumState = rememberAlbumState(items, startIndex)
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
        onShare = { item ->
            withLocalMedia(item) { file ->
                val mime = MediaShareActions.mimeFor(mediaName(item, file), item.isVideo)
                if (!MediaShareActions.share(context, file, mime)) {
                    Toast.makeText(context, R.string.media_action_no_app, Toast.LENGTH_SHORT).show()
                }
            }
        },
        onSave = { item ->
            withLocalMedia(item) { file ->
                scope.launch {
                    val mime = MediaShareActions.mimeFor(mediaName(item, file), item.isVideo)
                    val saved = MediaShareActions.saveToGallery(context, file, mime, mediaName(item, file))
                    Toast.makeText(
                        context,
                        if (saved != null) R.string.media_action_saved else R.string.media_action_save_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        },
        onCopyMedia = { item ->
            withLocalMedia(item) { file ->
                val mime = MediaShareActions.mimeFor(mediaName(item, file), item.isVideo)
                val copied = MediaShareActions.copyToClipboard(context, file, mime)
                Toast.makeText(
                    context,
                    when {
                        !copied -> R.string.media_action_save_failed
                        item.isVideo -> R.string.media_action_copied_video
                        else -> R.string.media_action_copied_image
                    },
                    Toast.LENGTH_SHORT,
                ).show()
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
            val messages = (if (wholeAlbum) album else {
                album.filter { messageKey(it) == item.id }
            }).filter { it.canForwardFrom(chatCanForward) }
            if (messages.isNotEmpty()) {
                onForward(messages)
                onDismiss()
            }
        },
        onDelete = { item, wholeAlbum ->
            pendingDelete = if (wholeAlbum) album else album.filter { messageKey(it) == item.id }
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
        canDelete = true,
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
        onRequestItem = { item -> album.firstOrNull { messageKey(it) == item.id }?.let { resolve(it) } },
        onIndexChange = { index -> album.getOrNull(index)?.let(onEnsureReceipts) },
        testTagPrefix = "media-video",
    )

    pendingDelete?.let { targets ->
        val single = targets.size == 1
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.media_delete_title)) },
            text = {
                Text(
                    stringResource(
                        if (single) R.string.media_delete_body_single else R.string.media_delete_body_album,
                        targets.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(targets)
                    onDismiss()
                }) {
                    Text(stringResource(R.string.media_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.media_delete_cancel))
                }
            },
        )
    }
}

private const val SECONDS_PER_DAY = 86_400L

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
