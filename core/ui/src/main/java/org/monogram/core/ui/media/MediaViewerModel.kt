package org.monogram.core.ui.media

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.datasource.DataSource
import java.io.File

/** What kind of media a viewer page shows. Mixed albums contain both. */
enum class MediaViewerKind { PHOTO, VIDEO, AUDIO, VOICE, VIDEO_NOTE, UNKNOWN }

@Immutable
data class CaptionEntity(val offset: Int, val length: Int, val type: Type, val value: String? = null) {
    enum class Type { URL, MENTION, BOLD }
}

@Immutable
sealed interface MediaHdr {
    data object None : MediaHdr
    data object GainMap : MediaHdr
    data class Video(val hdrStaticInfoLabel: String) : MediaHdr
}

@Immutable
data class AlbumSelectionState(val active: Boolean = false, val ids: Set<String> = emptySet()) {
    fun contains(id: String): Boolean = if (active) id in ids else false
}

/** Where the bytes come from. [Stream] is used while a video is still downloading. */
sealed interface MediaSource {
    @Immutable
    data class Local(val file: File) : MediaSource

    data class Stream(val uri: Uri, val factory: DataSource.Factory? = null) : MediaSource
}

/**
 * One page of the media viewer. Everything the shell needs to render a photo or a
 * video, including the album and accessibility metadata, without knowing the chat.
 */
@Immutable
data class MediaViewerItem(
    val id: String,
    val kind: MediaViewerKind,
    val source: MediaSource? = null,
    /** Thumbnail or blurhash stand-in shown before the full media arrives. */
    val preview: File? = null,
    val caption: String? = null,
    val captionEntities: List<CaptionEntity> = emptyList(),
    val durationSeconds: Int? = null,
    val albumIndex: Int = 0,
    val albumCount: Int = 1,
    val groupedId: Long? = null,
    val spoiler: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    val progress: Float? = null,
    val hdr: MediaHdr = MediaHdr.None,
    val qualities: List<String> = emptyList(),
    val aspectRatio: Float? = null,
    val senderName: String? = null,
    /** Pre-formatted relative date ("yesterday", "14:32"). */
    val dateLabel: String? = null,
    val dateMillis: Long? = null,
    val protectedContent: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val fileSize: Long? = null,
    val fileName: String? = null,
    /** GIF-style loops requested by the caller, regardless of duration. */
    val forceLoop: Boolean = false,
    /** Message identity used to reopen the source chat from global playback controls. */
    val sourceChatId: Long? = null,
    val sourceMessageId: Int? = null,
) {
    val isVideo: Boolean get() = kind == MediaViewerKind.VIDEO || kind == MediaViewerKind.VIDEO_NOTE
    val isVideoNote: Boolean get() = kind == MediaViewerKind.VIDEO_NOTE
    val isAudio: Boolean get() = kind == MediaViewerKind.AUDIO || kind == MediaViewerKind.VOICE
    val isPlayable: Boolean get() = isVideo || isAudio
    val isMessageMedia: Boolean
        get() = isAudio || kind == MediaViewerKind.VIDEO_NOTE

    /** Short videos loop while they are the current page; longer ones never do. */
    val loops: Boolean
        get() = kind == MediaViewerKind.VIDEO && !protectedContent &&
            (forceLoop || (durationSeconds ?: 0) in 1..LOOP_MAX_SECONDS)

    companion object {
        const val LOOP_MAX_SECONDS = 30
    }
}

/** Callbacks the shell raises. The feature layer decides what share/save actually do. */
@Immutable
data class MediaViewerActions(
    val onShare: (MediaViewerItem) -> Unit = {},
    val onSave: (MediaViewerItem) -> Unit = {},
    val onForward: (item: MediaViewerItem, wholeAlbum: Boolean) -> Unit = { _, _ -> },
    val onDelete: (item: MediaViewerItem, wholeAlbum: Boolean) -> Unit = { _, _ -> },
    val onEdit: (MediaViewerItem) -> Unit = {},
    val onShowInChat: (MediaViewerItem) -> Unit = {},
    val onReply: ((MediaViewerItem) -> Unit)? = null,
    val onSaveAs: ((MediaViewerItem) -> Unit)? = null,
    val onCopyCaption: (MediaViewerItem) -> Unit = {},
    val onReport: (MediaViewerItem) -> Unit = {},
    val onOpenExternally: (MediaViewerItem) -> Unit = {},
    val onEnterPictureInPicture: () -> Unit = {},
    val onExitPictureInPicture: () -> Unit = {},
    val onListenInBackground: () -> Unit = {},
    val onRetry: (MediaViewerItem) -> Unit = {},
    /** Puts the image or video itself on the clipboard, not a link. */
    val onCopyMedia: (MediaViewerItem) -> Unit = {},
    val seenByLabel: ((MediaViewerItem) -> String?)? = null,
    val onOpenSeenBy: ((MediaViewerItem) -> Unit)? = null,
    val canDelete: Boolean = false,
    val canEdit: Boolean = false,
    val canPictureInPicture: Boolean = false,
    val canRetry: Boolean = false,
    val canForward: Boolean = true,
    val onCaptionUrl: (String) -> Unit = {},
    val onCaptionMention: (String) -> Unit = {},
)

/** Which of the four playback surfaces currently owns the session. */
enum class MediaSurface { VIEWER, PIP, MINI_PLAYER, CHAT, AUDIO_ONLY, STOPPED }

/** Pager state for the album, hoisted so the caller can keep it across process death. */
@Stable
class MediaAlbumState(
    initialItems: List<MediaViewerItem> = emptyList(),
    initialIndex: Int = 0,
) {
    var items by mutableStateOf(initialItems)
    var index by mutableIntStateOf(initialIndex.coerceAtLeast(0))
    var selection by mutableStateOf(AlbumSelectionState())
    private val detectedHdr = mutableMapOf<String, MediaHdr>()
    private val detectedDimensions = mutableMapOf<String, Pair<Int, Int>>()

    init {
        // The index is an invariant of the album, not something callers must police.
        index = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    val current: MediaViewerItem? get() = items.getOrNull(index)
    val count: Int get() = items.size
    val hasAlbum: Boolean get() = items.size > 1

    fun update(items: List<MediaViewerItem>, index: Int? = null) {
        val currentId = current?.id
        val retainedIndex = items.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: this.index
        this.items = items.map { item ->
            val hdr = detectedHdr[item.id] ?: item.hdr
            val dimensions = detectedDimensions[item.id]
            item.copy(hdr = hdr, width = dimensions?.first ?: item.width, height = dimensions?.second ?: item.height)
        }
        this.index = (index ?: retainedIndex).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val retained = selection.ids.intersect(items.mapTo(mutableSetOf()) { it.id })
        selection = AlbumSelectionState(retained.isNotEmpty(), retained)
    }

    fun setHdr(id: String, hdr: MediaHdr) {
        detectedHdr[id] = hdr
        if (items.any { it.id == id && it.hdr != hdr }) {
            items = items.map { if (it.id == id) it.copy(hdr = hdr) else it }
        }
    }

    fun setDimensions(id: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        detectedDimensions[id] = width to height
        if (items.any { it.id == id && (it.width != width || it.height != height) }) {
            items = items.map { if (it.id == id) it.copy(width = width, height = height) else it }
        }
    }

    fun toggleSelection(id: String) {
        if (items.none { it.id == id }) return
        val next = if (id in selection.ids) selection.ids - id else selection.ids + id
        selection = AlbumSelectionState(active = next.isNotEmpty(), ids = next)
    }

    fun clearSelection() { selection = AlbumSelectionState() }

    fun selectedItems(): List<MediaViewerItem> = if (selection.active) {
        items.filter { it.id in selection.ids }
    } else {
        listOfNotNull(current)
    }
}
