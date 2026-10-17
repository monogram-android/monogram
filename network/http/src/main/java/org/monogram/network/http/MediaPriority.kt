package org.monogram.network.http

/**
 * Queue rank for `upload.getFile`. Higher runs first.
 * https://core.telegram.org/api/files
 */
object MediaPriority {
    const val IDLE = 0
    const val DEFAULT = 10
    const val VISIBLE = 20
    const val DISPLAY = 25
    const val THUMB = 26
    const val USER = 30

    fun isBackground(priority: Int): Boolean = priority <= IDLE

    fun downloadProfile(priority: Int, kind: MediaFetchKind): DownloadProfile = when {
        kind == MediaFetchKind.Thumb -> DownloadProfile.Thumb
        priority >= USER -> DownloadProfile.User
        priority >= VISIBLE -> DownloadProfile.Visible
        priority <= IDLE -> DownloadProfile.Background
        else -> DownloadProfile.Ordinary
    }
}

/** Same windows as the native download profiles. https://core.telegram.org/api/files */
enum class DownloadProfile {
    Thumb,
    Ordinary,
    Background,
    Visible,
    Playing,
    User,
}

fun isLargeDownload(totalBytes: Long?): Boolean = totalBytes != null && totalBytes >= 20_000_000L

fun DownloadProfile.requestsInFlight(): Int = when (this) {
    DownloadProfile.Thumb -> 1
    DownloadProfile.Ordinary, DownloadProfile.Background -> 4
    DownloadProfile.Visible, DownloadProfile.Playing, DownloadProfile.User -> 8
}
