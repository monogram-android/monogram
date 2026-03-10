package org.monogram.presentation.features.chats.currentChat.components

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import coil3.video.videoFrameMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.monogram.presentation.core.util.getMimeType
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

sealed interface VideoType {
    val useAlphaChannel: Boolean
    val removeBlackBackground: Boolean

    data object Sticker : VideoType {
        override val useAlphaChannel: Boolean = true
        override val removeBlackBackground: Boolean = true
    }

    data object Gif : VideoType {
        override val useAlphaChannel: Boolean = false
        override val removeBlackBackground: Boolean = false
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoStickerPlayer(
    path: String,
    videoPlayerPool: VideoPlayerPool,
    type: VideoType,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    shouldLoop: Boolean = true,
    volume: Float = 0f,
    contentScale: ContentScale = ContentScale.Fit,
    onProgressUpdate: (Long) -> Unit = {},
    fileId: Int = 0,
    thumbnailPath: String? = null
) {
    if (LocalInspectionMode.current) {
        Box(modifier = modifier)
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentPath by rememberUpdatedState(path)

    var shouldLoadPlayer by remember { mutableStateOf(false) }
    var isVideoFrameReady by remember { mutableStateOf(false) }

    LaunchedEffect(animate, currentPath) {
        if (animate) {
            delay(150)
            if (isActive) shouldLoadPlayer = true
        } else {
            shouldLoadPlayer = false
            isVideoFrameReady = false
        }
    }

    Box(modifier = modifier) {
        if (shouldLoadPlayer) {
            val exoPlayer = remember(currentPath) { videoPlayerPool.acquire(fileId) }
            val isDisposed = remember { AtomicBoolean(false) }
            val textureViewRef = remember { mutableStateOf<VideoGLTextureView?>(null) }

            LaunchedEffect(volume) {
                if (exoPlayer.volume != volume) exoPlayer.volume = volume
            }

            LaunchedEffect(exoPlayer) {
                while (isActive && !isDisposed.get()) {
                    if (exoPlayer.isPlaying) {
                        onProgressUpdate(exoPlayer.currentPosition)
                    }
                    delay(500)
                }
            }

            DisposableEffect(currentPath, exoPlayer) {
                isDisposed.set(false)

                val isNetworkPath = currentPath.startsWith("http") || currentPath.startsWith("content")
                if (!isNetworkPath && !File(currentPath).exists()) {
                    isDisposed.set(true)
                    return@DisposableEffect onDispose {}
                }

                val uri = if (isNetworkPath) {
                    currentPath.toUri()
                } else {
                    Uri.fromFile(File(currentPath))
                }

                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .setMimeType(getMimeType(currentPath) ?: MimeTypes.VIDEO_MP4)
                    .build()

                val mediaSource = videoPlayerPool.getMediaSourceFactory(fileId)
                    .createMediaSource(mediaItem)

                exoPlayer.apply {
                    setMediaSource(mediaSource)
                    prepare()
                    playWhenReady = true
                    repeatMode = if (shouldLoop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    this.volume = volume
                }

                val playerListener = object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        textureViewRef.value?.setVideoSize(videoSize.width, videoSize.height)
                    }

                    override fun onRenderedFirstFrame() {
                        if (!isDisposed.get()) isVideoFrameReady = true
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (error.cause is FileNotFoundException) {
                            isVideoFrameReady = false
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying && !isVideoFrameReady && !isDisposed.get()) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                isVideoFrameReady = true
                            }, 50)
                        }
                    }
                }
                exoPlayer.addListener(playerListener)

                val lifecycleObserver = LifecycleEventObserver { _, event ->
                    if (isDisposed.get()) return@LifecycleEventObserver
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> exoPlayer.play()
                        Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

                onDispose {
                    isDisposed.set(true)
                    isVideoFrameReady = false
                    lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
                    exoPlayer.removeListener(playerListener)
                    exoPlayer.setVideoSurface(null)
                    videoPlayerPool.release(exoPlayer)
                }
            }

            AndroidView(
                factory = { ctx ->
                    VideoGLTextureView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                        )
                        isOpaque = false
                        this.contentScale = contentScale
                        this.configure(type.useAlphaChannel, type.removeBlackBackground)

                        this.onSurfaceReady = { surface ->
                            if (!isDisposed.get()) {
                                exoPlayer.setVideoSurface(surface)
                            } else {
                                surface.release()
                            }
                        }
                        textureViewRef.value = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        AnimatedVisibility(
            visible = !isVideoFrameReady,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.fillMaxSize()
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(thumbnailPath ?: currentPath)
                    .apply {
                        if (thumbnailPath == null) {
                            decoderFactory(VideoFrameDecoder.Factory())
                            videoFrameMillis(0)
                        }
                    }
                    .memoryCacheKey(thumbnailPath ?: currentPath)
                    .diskCacheKey(thumbnailPath ?: currentPath)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@OptIn(UnstableApi::class)
class VideoPlayerPool(val context: Context, val exoPlayerCache: ExoPlayerCache) {
    private val players = ArrayBlockingQueue<ExoPlayer>(8)
    private var isCallbackRegistered = false
    private var mediaSourceFactory: MediaSource.Factory? = null

    fun getMediaSourceFactory(fileId: Int = 0): MediaSource.Factory {
        if (fileId != 0) {
            val streamingRepository =
                org.koin.core.context.GlobalContext.get().get<org.monogram.domain.repository.PlayerDataSourceFactory>()
            val dataSourceFactory = streamingRepository.createPayload(fileId) as DataSource.Factory
            val extractorsFactory = DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
            return DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
        }

        return mediaSourceFactory ?: synchronized(this) {
            val extractorsFactory = DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)

            mediaSourceFactory ?: DefaultMediaSourceFactory(
                exoPlayerCache.getDataSourceFactory(context),
                extractorsFactory
            )
                .also { mediaSourceFactory = it }
        }
    }

    fun acquire(fileId: Int = 0): ExoPlayer {
        registerMemoryCallbacks(context.applicationContext)
        val player = players.poll()
        return player?.apply {
            seekTo(0)
            playWhenReady = false
        } ?: createPlayer(fileId)
    }

    fun release(player: ExoPlayer) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            resetAndStore(player)
        } else {
            Handler(Looper.getMainLooper()).post { resetAndStore(player) }
        }
    }

    private fun resetAndStore(player: ExoPlayer) {
        player.stop()
        player.clearMediaItems()
        if (!players.offer(player)) {
            player.release()
        }
    }

    private fun createPlayer(fileId: Int = 0): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)

        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(
                500,  // Min buffer
                3000, // Max buffer
                100,  // Buffer to start playback (very low for instant start)
                500   // Buffer for rebuffer
            ).build()

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)

        val mediaSourceFactory = if (fileId != 0) {
            val streamingRepository =
                org.koin.core.context.GlobalContext.get().get<org.monogram.domain.repository.PlayerDataSourceFactory>()
            val dataSourceFactory = streamingRepository.createPayload(fileId) as DataSource.Factory
            DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
        } else {
            DefaultMediaSourceFactory(context, extractorsFactory)
        }

        return ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
            }
    }

    private fun registerMemoryCallbacks(context: Context) {
        if (isCallbackRegistered) return
        synchronized(this) {
            if (isCallbackRegistered) return
            isCallbackRegistered = true
            (context as? Application)?.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) clearPool()
                }

                override fun onConfigurationChanged(newConfig: Configuration) {}
                override fun onLowMemory() {
                    clearPool()
                }
            })
        }
    }

    private fun clearPool() {
        val iterator = players.iterator()
        while (iterator.hasNext()) {
            iterator.next().release()
            iterator.remove()
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
class ExoPlayerCache {
    @Volatile
    private var cacheDataSourceFactory: CacheDataSource.Factory? = null
    private var simpleCache: SimpleCache? = null
    private var databaseProvider: DatabaseProvider? = null
    private val lock = Any()

    fun getDataSourceFactory(context: Context): CacheDataSource.Factory {
        return cacheDataSourceFactory ?: synchronized(lock) {
            if (cacheDataSourceFactory == null) {
                val cacheDir = File(context.applicationContext.cacheDir, "sticker_video_cache")
                if (!cacheDir.exists()) cacheDir.mkdirs()

                if (databaseProvider == null) {
                    databaseProvider = StandaloneDatabaseProvider(context.applicationContext)
                }

                val evictor = LeastRecentlyUsedCacheEvictor(250 * 1024 * 1024)
                simpleCache = SimpleCache(cacheDir, evictor, databaseProvider!!)

                val upstreamFactory = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(15_000)
                    .setUserAgent("StickerPlayer/2.0")

                val defaultDataSourceFactory = DefaultDataSource.Factory(context.applicationContext, upstreamFactory)

                cacheDataSourceFactory = CacheDataSource.Factory()
                    .setCache(simpleCache!!)
                    .setUpstreamDataSourceFactory(defaultDataSourceFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            }
            cacheDataSourceFactory!!
        }
    }

    fun clearCache(context: Context) {
        synchronized(lock) {
            try {
                simpleCache?.release()
                simpleCache = null
                cacheDataSourceFactory = null
                val cacheDir = File(context.applicationContext.cacheDir, "sticker_video_cache")
                if (cacheDir.exists()) {
                    cacheDir.deleteRecursively()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

@Keep
class NativeVideoRenderer {
    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    private var nativeHandle: Long = 0
    var onSurfaceReady: ((Surface) -> Unit)? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    private var overlayTextureId: Int = 0

    external fun create(surface: Surface, useAlpha: Boolean, removeBlackBg: Boolean): Long
    external fun destroy(handle: Long)
    external fun updateSize(handle: Long, width: Int, height: Int)
    external fun notifyFrameAvailable(handle: Long)
    external fun setFilter(handle: Long, matrix: FloatArray?)
    external fun setOverlayTexture(handle: Long, textureId: Int)

    @Keep
    fun onGlContextReady(textureId: Int) {
        Handler(Looper.getMainLooper()).post {
            if (nativeHandle == 0L) return@post
            val st = SurfaceTexture(textureId)
            surfaceTexture = st
            st.setOnFrameAvailableListener {
                if (nativeHandle != 0L) {
                    notifyFrameAvailable(nativeHandle)
                }
            }

            val s = Surface(st)
            surface = s
            onSurfaceReady?.invoke(s)
        }
    }

    @Keep
    fun updateTexture(matrix: FloatArray): Long {
        surfaceTexture?.updateTexImage()
        surfaceTexture?.getTransformMatrix(matrix)
        return surfaceTexture?.timestamp ?: 0L
    }

    fun init(surface: Surface, useAlpha: Boolean, removeBlackBg: Boolean) {
        nativeHandle = create(surface, useAlpha, removeBlackBg)
    }

    fun release() {
        if (nativeHandle != 0L) {
            destroy(nativeHandle)
            nativeHandle = 0
        }
        surface?.release()
        surfaceTexture?.release()
        surface = null
        surfaceTexture = null
    }

    fun setSize(width: Int, height: Int) {
        if (nativeHandle != 0L) {
            updateSize(nativeHandle, width, height)
        }
    }

    fun setFilter(matrix: FloatArray?) {
        if (nativeHandle != 0L) {
            setFilter(nativeHandle, matrix)
        }
    }

    fun setOverlay(bitmap: Bitmap?) {
        if (nativeHandle != 0L) {
            if (bitmap != null) {

            } else {
                setOverlayTexture(nativeHandle, 0)
            }
        }
    }
}

class VideoGLTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    var onSurfaceReady: ((Surface) -> Unit)? = null
    var contentScale: ContentScale = ContentScale.Fit

    private var useAlpha = false
    private var removeBlackBg = false
    private var nativeRenderer: NativeVideoRenderer? = null

    private var videoWidth = 0
    private var videoHeight = 0

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    fun configure(useAlpha: Boolean, removeBlackBg: Boolean) {
        this.useAlpha = useAlpha
        this.removeBlackBg = removeBlackBg
    }

    fun setVideoSize(width: Int, height: Int) {
        this.videoWidth = width
        this.videoHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (videoWidth == 0 || videoHeight == 0) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
            return
        }

        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        val viewRatio = width.toFloat() / height.toFloat()
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()

        var finalWidth = width
        var finalHeight = height

        if (contentScale == ContentScale.Fit) {
            if (videoRatio > viewRatio) {
                finalHeight = (width / videoRatio).toInt()
            } else {
                finalWidth = (height * videoRatio).toInt()
            }
        } else if (contentScale == ContentScale.Crop) {
            if (videoRatio > viewRatio) {
                finalWidth = (height * videoRatio).toInt()
            } else {
                finalHeight = (width / videoRatio).toInt()
            }
        }

        setMeasuredDimension(finalWidth, finalHeight)
    }

    fun setFilter(matrix: FloatArray?) {
        nativeRenderer?.setFilter(matrix)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        nativeRenderer = NativeVideoRenderer()
        nativeRenderer?.onSurfaceReady = { surface ->
            onSurfaceReady?.invoke(surface)
        }
        nativeRenderer?.init(Surface(st), useAlpha, removeBlackBg)
        nativeRenderer?.setSize(width, height)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        nativeRenderer?.setSize(width, height)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        nativeRenderer?.release()
        nativeRenderer = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}