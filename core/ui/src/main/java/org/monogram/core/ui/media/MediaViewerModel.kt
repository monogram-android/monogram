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
enum class MediaViewerKind { PHOTO, VIDEO }

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
    val durationSeconds: Int? = null,
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
) {
    val isVideo: Boolean get() = kind == MediaViewerKind.VIDEO

    /** Short videos loop while they are the current page; longer ones never do. */
    val loops: Boolean
        get() = isVideo && !protectedContent &&
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
)

/** Which of the four playback surfaces currently owns the session. */
enum class MediaSurface { VIEWER, PIP, MINI_PLAYER, AUDIO_ONLY, STOPPED }

/** Pager state for the album, hoisted so the caller can keep it across process death. */
@Stable
class MediaAlbumState(
    initialItems: List<MediaViewerItem> = emptyList(),
    initialIndex: Int = 0,
) {
    var items by mutableStateOf(initialItems)
    var index by mutableIntStateOf(initialIndex.coerceAtLeast(0))

    init {
        // The index is an invariant of the album, not something callers must police.
        index = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    val current: MediaViewerItem? get() = items.getOrNull(index)
    val count: Int get() = items.size
    val hasAlbum: Boolean get() = items.size > 1

    fun update(items: List<MediaViewerItem>, index: Int = this.index) {
        this.items = items
        this.index = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }
}
