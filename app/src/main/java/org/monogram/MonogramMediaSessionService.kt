package org.monogram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.monogram.core.ui.media.MediaPlaybackHolder

/**
 * Hosts the process-wide [MediaSession] so playback survives leaving the app: the
 * notification, the lockscreen and headset buttons all drive the same ExoPlayer the
 * viewer and the mini player use. No second player instance is ever created.
 */
class MonogramMediaSessionService : MediaSessionService() {
    private var session: MediaSession? = null
    private val idleListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (timeline.isEmpty) stopSelf()
        }
    }

    override fun onCreate() {
        createPlaybackChannel()
        startForeground(NOTIFICATION_ID, playbackNotification())
        super.onCreate()
        val playback = MediaPlaybackHolder.session(this)
        session = playback.mediaSession
        addSession(playback.mediaSession)
        playback.player.addListener(idleListener)
    }

    private fun createPlaybackChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                PLAYBACK_CHANNEL,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            },
        )
    }

    private fun playbackNotification(): Notification =
        NotificationCompat.Builder(this, PLAYBACK_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.app_name))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, playbackNotification())
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val player = MediaPlaybackHolder.peek()?.player ?: return
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        MediaPlaybackHolder.peek()?.player?.removeListener(idleListener)
        session = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 7401
        private const val PLAYBACK_CHANNEL = "media_playback"

        fun notificationIntent(activity: android.app.Activity): PendingIntent? =
            activity.packageManager.getLaunchIntentForPackage(activity.packageName)
                ?.let {
                    PendingIntent.getActivity(
                        activity,
                        0,
                        it,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                }
    }
}
