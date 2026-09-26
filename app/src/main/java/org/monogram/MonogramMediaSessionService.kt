package org.monogram

import android.app.PendingIntent
import android.content.Intent
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

    override fun onCreate() {
        super.onCreate()
        session = MediaPlaybackHolder.session(this).mediaSession
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val player = MediaPlaybackHolder.peek()?.player ?: return
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        session = null
        super.onDestroy()
    }

    companion object {
        fun notificationIntent(activity: android.app.Activity): PendingIntent? =
            activity.packageManager.getLaunchIntentForPackage(activity.packageName)
                ?.let { PendingIntent.getActivity(
                    activity,
                    0,
                    it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ) }
    }
}
