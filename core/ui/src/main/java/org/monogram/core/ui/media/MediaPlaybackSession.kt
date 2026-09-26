package org.monogram.core.ui.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The single playback session shared by every surface: the viewer stage, the mini
 * player, picture-in-picture, the media notification and the audio-only
 * "listen in background" mode.
 *
 * Exactly one [ExoPlayer] exists per process. A surface only decides whether the video
 * output is attached - never whether a decoder exists. Every mutation is marshalled to
 * the main looper because ExoPlayer is main-thread affine and callers include media
 * keys, headset buttons, the media session service and tests.
 */
@Stable
class MediaPlaybackSession(private val context: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context))
        .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    val mediaSession: MediaSession = MediaSession.Builder(context, player).build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null

    /** Current album, photo and video items in display order. */
    var queue by mutableStateOf<List<MediaViewerItem>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set

    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var bufferedMs by mutableLongStateOf(0L)
        private set
    var aspectRatio by mutableFloatStateOf(0f)
        private set
    var failed by mutableStateOf(false)
        private set

    var muted by mutableStateOf(true)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var audioOnly by mutableStateOf(false)
        private set

    /** Which surface currently draws the video output. */
    var surface by mutableStateOf(MediaSurface.STOPPED)
        private set

    /** Wall-clock position per media id so closing and reopening resumes instead of restarting. */
    private val resumePositions = CopyOnWriteArrayList<Pair<String, Long>>()

    private val audioOnlyListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    val current: MediaViewerItem? get() = queue.getOrNull(index)
    val albumSize: Int get() = queue.size
    val hasAlbum: Boolean get() = queue.size > 1
    val isAlbumVideoOnly: Boolean get() = queue.isNotEmpty() && queue.all { it.isVideo }

    /** MediaSession next/prev may only walk playable items, never land on a photo. */
    private val videoIndices: List<Int> get() = queue.indices.filter { queue[it].isVideo }
    val hasNextVideo: Boolean get() = videoIndices.any { it > index }
    /** True while a mixed album is held at the end of a video instead of advancing. */
    val pausesAtEndOfMixedVideo: Boolean get() = player.pauseAtEndOfMediaItems
    val hasPreviousVideo: Boolean get() = videoIndices.any { it < index }

    /** Raised when media keys walk the video queue, so the pager can follow. */
    var onVideoQueueStep: ((Int) -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing = isPlaying
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            buffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
        }

        override fun onPlayerError(error: PlaybackException) {
            failed = true
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                aspectRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            speed = playbackParameters.speed
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            positionMs = resumePositions.lastOrNull { it.first == mediaItem?.mediaId }?.second ?: 0L
            // The notification, lockscreen and headset keys drive the player's own queue.
            // Only settle those transitions here: playlist changes are our own resync.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
                return
            }
            val targetId = mediaItem?.mediaId ?: return
            if (queue.getOrNull(index)?.id == targetId) return
            val albumIndex = queue.indexOfFirst { it.id == targetId }
            if (albumIndex < 0) return
            index = albumIndex
            onVideoQueueStep?.invoke(albumIndex)
        }
    }

    init {
        player.addListener(listener)
        player.repeatMode = Player.REPEAT_MODE_OFF
        ticker = scope.launch {
            while (isActive) {
                sample()
                delay(200)
            }
        }
    }

    private fun sample() {
        positionMs = player.currentPosition.coerceAtLeast(0L)
        val reported = player.duration
        if (reported != C.TIME_UNSET && reported > 0L) durationMs = reported
        bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
        val id = current?.id
        if (id != null) {
            val existing = resumePositions.indexOfLast { it.first == id }
            if (existing >= 0) resumePositions[existing] = id to positionMs
            else resumePositions.add(id to positionMs)
        }
    }

    /**
     * Installs a new album and starts at [startIndex]. Re-entering the very same media
     * (rotation, activity recreation) never overrides a pause the user asked for, but a
     * fresh item always follows [autoplay].
     */
    fun setQueue(
        items: List<MediaViewerItem>,
        startIndex: Int,
        autoplay: Boolean,
        startMuted: Boolean,
        preserveUserMute: Boolean = false,
    ) = onMain {
        val target = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val sameMedia = current?.id != null && current?.id == items.getOrNull(target)?.id
        queue = items
        index = target
        if (!preserveUserMute) muted = startMuted
        prepareCurrent(if (sameMedia) player.playWhenReady else autoplay)
    }

    fun togglePlayPause() = onMain {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
        player.playWhenReady = !player.playWhenReady
        if (player.playWhenReady) failed = false
    }

    fun play() = onMain {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
        player.playWhenReady = true
    }

    fun pause() = onMain { player.playWhenReady = false }

    fun seekTo(milliseconds: Long) = onMain {
        val target = milliseconds.coerceIn(0L, durationMs.coerceAtLeast(0L))
        player.seekTo(target)
        positionMs = target
        current?.let { rememberPosition(it.id, target) }
    }

    fun seekBy(deltaMs: Long) = seekTo(positionMs + deltaMs)

    fun mute(value: Boolean) = onMain {
        muted = value
        player.volume = if (muted) 0f else 1f
    }

    fun toggleMuted() = mute(!muted)

    fun changeSpeed(value: Float) = onMain {
        speed = value
        player.setPlaybackSpeed(value)
    }

    fun setLoopingCurrent(value: Boolean) = onMain {
        player.repeatMode = if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun selectNextVideo(): Boolean = onMainChecked {
        val target = videoIndices.firstOrNull { it > index } ?: return@onMainChecked false
        holdPosition()
        onVideoQueueStep?.invoke(target)
        index = target
        prepareCurrent(true)
        true
    }

    fun selectPreviousVideo(): Boolean = onMainChecked {
        val target = videoIndices.lastOrNull { it < index } ?: return@onMainChecked false
        holdPosition()
        onVideoQueueStep?.invoke(target)
        index = target
        prepareCurrent(true)
        true
    }

    /** Kills the video surface but keeps decoding audio; used by "Listen in background". */
    fun listenInBackground() = onMain {
        audioOnly = true
        surface = MediaSurface.AUDIO_ONLY
        audioOnlyListeners.forEach { it(true) }
    }

    fun attachSurface(target: MediaSurface) = onMain { surface = target }

    /** Called when the notification's play/pause is used while no UI is on screen. */
    fun notificationToggle() = togglePlayPause()

    fun rememberPosition(id: String, position: Long) = onMain {
        resumePositions.removeAll { it.first == id }
        resumePositions.add(id to position)
    }

    fun positionFor(id: String): Long = resumePositions.lastOrNull { it.first == id }?.second ?: 0L

    fun holdPosition() = onMain {
        val id = current?.id ?: return@onMain
        resumePositions.removeAll { it.first == id }
        resumePositions.add(id to positionMs)
    }

    /** Stops the session completely: the mini player disappears and no media stays loaded. */
    fun stop() = onMain {
        holdPosition()
        player.stop()
        player.clearMediaItems()
        queue = emptyList()
        index = 0
        playing = false
        buffering = false
        durationMs = 0L
        positionMs = 0L
        bufferedMs = 0L
        audioOnly = false
        surface = MediaSurface.STOPPED
        audioOnlyListeners.forEach { it(false) }
    }

    fun release() = onMain {
        ticker?.cancel()
        player.removeListener(listener)
        mediaSession.release()
        player.release()
    }

    fun onAudioOnlyChanged(listener: (Boolean) -> Unit) {
        audioOnlyListeners.add(listener)
    }

    private fun prepareCurrent(autoplay: Boolean) {
        val item = current ?: return
        failed = false
        if (!item.isVideo) {
            player.pause()
            player.clearMediaItems()
            durationMs = 0L
            positionMs = 0L
            bufferedMs = 0L
            return
        }
        val videos = queue.filter { it.isVideo }
        val videoIndex = videos.indexOfFirst { it.id == item.id }
        if (videoIndex < 0) return
        val sameSet = player.mediaItemCount == videos.size &&
            videos.indices.all { player.getMediaItemAt(it).mediaId == videos[it].id }
        if (sameSet && player.currentMediaItem?.mediaId == item.id) {
            player.volume = if (muted) 0f else 1f
            player.playWhenReady = autoplay
            return
        }
        val sources = videos.mapNotNull(::mediaSourceFor)
        if (sources.isEmpty()) return
        val startIndex = videoIndex.coerceIn(0, sources.lastIndex)
        player.setMediaSources(sources, startIndex, positionFor(item.id))
        player.repeatMode = if (item.loops) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.pauseAtEndOfMediaItems = !isAlbumVideoOnly
        player.prepare()
        player.volume = if (muted) 0f else 1f
        player.playWhenReady = autoplay
    }

    /** One media item, wrapped in the item's own data source factory when it streams. */
    private fun mediaSourceFor(item: MediaViewerItem): MediaSource? {
        val mediaItem = mediaItemFor(item) ?: return null
        val factory = (item.source as? MediaSource.Stream)?.factory
        return if (factory != null) {
            DefaultMediaSourceFactory(context).setDataSourceFactory(factory).createMediaSource(mediaItem)
        } else {
            DefaultMediaSourceFactory(context).createMediaSource(mediaItem)
        }
    }

    private fun mediaItemFor(item: MediaViewerItem): MediaItem? {
        val source = item.source ?: return null
        val uri = when (source) {
            is MediaSource.Local -> android.net.Uri.fromFile(source.file)
            is MediaSource.Stream -> source.uri
        }
        return MediaItem.Builder().setUri(uri).setMediaId(item.id).build()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** Same marshalling for the few calls that report a result back to the caller. */
    private fun <T> onMainChecked(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        val latch = CountDownLatch(1)
        mainHandler.post {
            result = block()
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}

/**
 * Process-wide holder so the viewer, the mini player and the media session service all
 * talk to the same player instance.
 */
object MediaPlaybackHolder {
    @Volatile
    private var instance: MediaPlaybackSession? = null

    fun session(context: Context): MediaPlaybackSession {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: MediaPlaybackSession(context.applicationContext).also { instance = it }
        }
    }

    fun peek(): MediaPlaybackSession? = instance
}
