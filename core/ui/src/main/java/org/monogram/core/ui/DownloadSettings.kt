package org.monogram.core.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DownloadConcurrency(val lanes: Int, val parts: Int)

enum class AutoDownloadNetwork { Wifi, Mobile, Roaming }

data class AutoDownloadPreset(
    val enabled: Boolean = true,
    val photos: Boolean,
    val videos: Boolean,
    val gifs: Boolean,
    val files: Boolean,
    val maxVideoBytes: Long,
    val maxGifBytes: Long,
    val maxFileBytes: Long,
    val preloadVideo: Boolean,
) {
    fun allowsDisplay(kind: String?, @Suppress("UNUSED_PARAMETER") sizeBytes: Long? = null): Boolean {
        if (!enabled) return false
        return when (kind) {
            "photo", "webpage" -> photos
            "video", "gif" -> true
            else -> false
        }
    }

    fun allowsFull(kind: String?, sizeBytes: Long?, userRequested: Boolean = false): Boolean {
        if (userRequested) return true
        if (!enabled) return false
        return when (kind) {
            "sticker", "sticker_animated", "sticker_video" -> true
            "gif" -> gifs && sizeWithin(sizeBytes, maxGifBytes)
            "video" -> videos && sizeWithin(sizeBytes, maxVideoBytes)
            "document" -> files && sizeWithin(sizeBytes, maxFileBytes)
            else -> false
        }
    }

    fun allowsStream(kind: String?, sizeBytes: Long?): Boolean {
        if (!enabled || kind != "video" || !videos) return false
        if (sizeWithin(sizeBytes, maxVideoBytes)) return true
        val size = sizeBytes ?: return false
        return preloadVideo && size > maxVideoBytes && maxVideoBytes > 2L * MB
    }

    fun encode(): String = listOf(
        flag(enabled),
        flag(photos),
        flag(videos),
        flag(gifs),
        flag(files),
        maxVideoBytes.toString(),
        maxGifBytes.toString(),
        maxFileBytes.toString(),
        flag(preloadVideo),
    ).joinToString("_")

    companion object {
        const val KB = 1024L
        const val MB = 1024L * 1024L

        val SIZE_STEPS: List<Long> = listOf(
            512_000L,
            1L * MB,
            2L * MB,
            3L * MB,
            5L * MB,
            10L * MB,
            15L * MB,
            25L * MB,
            50L * MB,
            100L * MB,
            512L * MB,
            2L * MB * 1024L,
        )

        val WIFI = AutoDownloadPreset(
            photos = true,
            videos = true,
            gifs = true,
            files = true,
            maxVideoBytes = 15L * MB,
            maxGifBytes = 15L * MB,
            maxFileBytes = 3L * MB,
            preloadVideo = true,
        )

        val MOBILE = AutoDownloadPreset(
            photos = true,
            videos = true,
            gifs = true,
            files = true,
            maxVideoBytes = 10L * MB,
            maxGifBytes = 10L * MB,
            maxFileBytes = 1L * MB,
            preloadVideo = true,
        )

        val ROAMING = AutoDownloadPreset(
            photos = true,
            videos = false,
            gifs = false,
            files = false,
            maxVideoBytes = 512_000L,
            maxGifBytes = 512_000L,
            maxFileBytes = 512_000L,
            preloadVideo = false,
        )

        fun decode(raw: String?, fallback: AutoDownloadPreset): AutoDownloadPreset {
            if (raw.isNullOrBlank()) return fallback
            val parts = raw.split('_')
            if (parts.size < 9) return fallback
            return AutoDownloadPreset(
                enabled = parts[0] == "1",
                photos = parts[1] == "1",
                videos = parts[2] == "1",
                gifs = parts[3] == "1",
                files = parts[4] == "1",
                maxVideoBytes = parts[5].toLongOrNull() ?: fallback.maxVideoBytes,
                maxGifBytes = parts[6].toLongOrNull() ?: fallback.maxGifBytes,
                maxFileBytes = parts[7].toLongOrNull() ?: fallback.maxFileBytes,
                preloadVideo = parts[8] == "1",
            )
        }

        private fun flag(value: Boolean): String = if (value) "1" else "0"

        private fun sizeWithin(sizeBytes: Long?, maxBytes: Long): Boolean {
            val size = sizeBytes ?: return false
            return size > 0L && size <= maxBytes
        }
    }
}

data class DownloadState(
    val lanes: Int = 8,
    val speedUpUploads: Boolean = false,
    val speedUpDownloads: Boolean = true,
    val wifi: AutoDownloadPreset = AutoDownloadPreset.WIFI,
    val mobile: AutoDownloadPreset = AutoDownloadPreset.MOBILE,
    val roaming: AutoDownloadPreset = AutoDownloadPreset.ROAMING,
    val autoplayGifs: Boolean = true,
    val autoplayVideos: Boolean = true,
    val activeNetwork: AutoDownloadNetwork = AutoDownloadNetwork.Wifi,
) {
    val parts: Int get() = if (speedUpDownloads) 8 else 6
    val concurrency: DownloadConcurrency get() = DownloadConcurrency(lanes = lanes, parts = parts)
    val filePartKib: Int get() = if (speedUpUploads) 512 else 32
    val downloadChunkKib: Int get() = if (speedUpDownloads) 256 else 128

    fun presetFor(network: AutoDownloadNetwork): AutoDownloadPreset = when (network) {
        AutoDownloadNetwork.Wifi -> wifi
        AutoDownloadNetwork.Mobile -> mobile
        AutoDownloadNetwork.Roaming -> roaming
    }
}

object DownloadSettings {
    private const val PREFS = "monogram_download"
    private const val KEY_SPEED_UP_UPLOADS = "speed_up_uploads"
    private const val KEY_SPEED_UP_DOWNLOADS = "speed_up_downloads"
    private const val KEY_WIFI = "autodownload_wifi"
    private const val KEY_MOBILE = "autodownload_mobile"
    private const val KEY_ROAMING = "autodownload_roaming"
    private const val KEY_AUTOPLAY_GIFS = "autoplay_gifs"
    private const val KEY_AUTOPLAY_VIDEOS = "autoplay_videos"

    private val mutable = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = mutable.asStateFlow()

    private val network = MutableStateFlow(AutoDownloadNetwork.Wifi)

    val concurrency: DownloadConcurrency get() = mutable.value.concurrency

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var apply: ((DownloadState) -> Unit)? = null

    @Volatile
    private var networkOverride: AutoDownloadNetwork? = null

    fun install(context: Context, apply: (DownloadState) -> Unit) {
        val app = context.applicationContext
        appContext = app
        this.apply = apply
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mutable.value = DownloadState(
            speedUpUploads = prefs.getBoolean(KEY_SPEED_UP_UPLOADS, false),
            speedUpDownloads = prefs.getBoolean(KEY_SPEED_UP_DOWNLOADS, true),
            wifi = AutoDownloadPreset.decode(prefs.getString(KEY_WIFI, null), AutoDownloadPreset.WIFI),
            mobile = AutoDownloadPreset.decode(prefs.getString(KEY_MOBILE, null), AutoDownloadPreset.MOBILE),
            roaming = AutoDownloadPreset.decode(prefs.getString(KEY_ROAMING, null), AutoDownloadPreset.ROAMING),
            autoplayGifs = prefs.getBoolean(KEY_AUTOPLAY_GIFS, true),
            autoplayVideos = prefs.getBoolean(KEY_AUTOPLAY_VIDEOS, true),
        )
        refreshNetwork(app)
        listenForNetwork(app)
        apply(mutable.value)
    }

    fun activeNetwork(): AutoDownloadNetwork = network.value

    fun activePreset(): AutoDownloadPreset = mutable.value.presetFor(network.value)

    fun setSpeedUpUploads(enabled: Boolean) {
        mutable.update { it.copy(speedUpUploads = enabled) }
        persist()
        apply?.invoke(mutable.value)
    }

    fun setSpeedUpDownloads(enabled: Boolean) {
        mutable.update { it.copy(speedUpDownloads = enabled) }
        persist()
        apply?.invoke(mutable.value)
    }

    fun setPreset(networkKind: AutoDownloadNetwork, preset: AutoDownloadPreset) {
        mutable.update {
            when (networkKind) {
                AutoDownloadNetwork.Wifi -> it.copy(wifi = preset)
                AutoDownloadNetwork.Mobile -> it.copy(mobile = preset)
                AutoDownloadNetwork.Roaming -> it.copy(roaming = preset)
            }
        }
        persist()
        apply?.invoke(mutable.value)
    }

    fun setAutoplayGifs(enabled: Boolean) {
        mutable.update { it.copy(autoplayGifs = enabled) }
        persist()
        apply?.invoke(mutable.value)
    }

    fun setAutoplayVideos(enabled: Boolean) {
        mutable.update { it.copy(autoplayVideos = enabled) }
        persist()
        apply?.invoke(mutable.value)
    }

    fun resetAutoDownload() {
        mutable.update {
            it.copy(
                wifi = AutoDownloadPreset.WIFI,
                mobile = AutoDownloadPreset.MOBILE,
                roaming = AutoDownloadPreset.ROAMING,
                autoplayGifs = true,
                autoplayVideos = true,
            )
        }
        persist()
        apply?.invoke(mutable.value)
    }

    fun cycleDebugNetwork() {
        val next = when (network.value) {
            AutoDownloadNetwork.Wifi -> AutoDownloadNetwork.Mobile
            AutoDownloadNetwork.Mobile -> AutoDownloadNetwork.Roaming
            AutoDownloadNetwork.Roaming -> AutoDownloadNetwork.Wifi
        }
        setNetworkOverride(next)
    }

    fun setNetworkOverride(networkKind: AutoDownloadNetwork?) {
        networkOverride = networkKind
        val context = appContext
        if (context != null) {
            refreshNetwork(context)
        } else {
            val detected = networkKind ?: AutoDownloadNetwork.Wifi
            network.value = detected
            mutable.update { it.copy(activeNetwork = detected) }
        }
    }

    fun resetForTests() {
        networkOverride = null
        network.value = AutoDownloadNetwork.Wifi
        mutable.value = DownloadState()
        apply = null
        appContext = null
    }

    private fun persist() {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        val value = mutable.value
        prefs.edit {
            putBoolean(KEY_SPEED_UP_UPLOADS, value.speedUpUploads)
            putBoolean(KEY_SPEED_UP_DOWNLOADS, value.speedUpDownloads)
            putString(KEY_WIFI, value.wifi.encode())
            putString(KEY_MOBILE, value.mobile.encode())
            putString(KEY_ROAMING, value.roaming.encode())
            putBoolean(KEY_AUTOPLAY_GIFS, value.autoplayGifs)
            putBoolean(KEY_AUTOPLAY_VIDEOS, value.autoplayVideos)
        }
    }

    private fun listenForNetwork(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        if (Build.VERSION.SDK_INT < 24) return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refreshNetwork(context)
                override fun onLost(network: Network) = refreshNetwork(context)
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) = refreshNetwork(context)
            })
        }
    }

    private fun refreshNetwork(context: Context) {
        val detected = networkOverride ?: detectNetwork(context)
        network.value = detected
        mutable.update { it.copy(activeNetwork = detected) }
    }

    internal fun detectNetwork(context: Context): AutoDownloadNetwork {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return AutoDownloadNetwork.Mobile
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                return AutoDownloadNetwork.Wifi
            }
        }
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo
        @Suppress("DEPRECATION")
        if (info?.isRoaming == true) return AutoDownloadNetwork.Roaming
        return AutoDownloadNetwork.Mobile
    }
}
