package org.monogram.presentation.features.viewers.components

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.util.Consumer
import org.monogram.presentation.R

private const val ACTION_MEDIA_CONTROL = "org.monogram.pip.MEDIA_CONTROL"
private const val EXTRA_CONTROL_TYPE = "control_type"
private const val EXTRA_ID = "id"
private const val CONTROL_TYPE_PLAY = 1
private const val CONTROL_TYPE_PAUSE = 2
private const val CONTROL_TYPE_REWIND = 3
private const val CONTROL_TYPE_FORWARD = 4

@Composable
fun PipController(
    isPlaying: Boolean,
    videoAspectRatio: Float,
    pipId: Int,
    isActive: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onPipModeChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current

    val currentOnPlay by rememberUpdatedState(onPlay)
    val currentOnPause by rememberUpdatedState(onPause)
    val currentOnRewind by rememberUpdatedState(onRewind)
    val currentOnForward by rememberUpdatedState(onForward)
    val currentOnPipModeChanged by rememberUpdatedState(onPipModeChanged)

    fun createRemoteAction(controlType: Int, iconResId: Int, title: String): RemoteAction? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(ACTION_MEDIA_CONTROL).apply {
                putExtra(EXTRA_CONTROL_TYPE, controlType)
                putExtra(EXTRA_ID, pipId)
                data = "monogram://pip/$pipId/$controlType".toUri()
                setPackage(context.packageName)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                controlType,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return RemoteAction(Icon.createWithResource(context, iconResId), title, title, pendingIntent)
        }
        return null
    }

    fun getPipParams(autoEnter: Boolean): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val constrainedRatio = videoAspectRatio.coerceIn(0.4184f, 2.39f)
            val ratio = if (constrainedRatio.isNaN() || constrainedRatio <= 0f) Rational(16, 9)
            else Rational((constrainedRatio * 1000).toInt(), 1000)

            val params = PictureInPictureParams.Builder()
                .setAspectRatio(ratio)

            val actions = mutableListOf<RemoteAction>()

            // Rewind
            createRemoteAction(CONTROL_TYPE_REWIND, R.drawable.ic_replay_10, context.getString(R.string.pip_rewind))?.let {
                actions.add(it)
            }

            // Play/Pause
            val playPauseAction = if (isPlaying) {
                createRemoteAction(CONTROL_TYPE_PAUSE, R.drawable.ic_pause, context.getString(R.string.pip_pause))
            } else {
                createRemoteAction(CONTROL_TYPE_PLAY, R.drawable.ic_play, context.getString(R.string.pip_play))
            }
            playPauseAction?.let { actions.add(it) }

            // Forward
            createRemoteAction(CONTROL_TYPE_FORWARD, R.drawable.ic_forward_10, context.getString(R.string.pip_forward))?.let {
                actions.add(it)
            }

            params.setActions(actions)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setAutoEnterEnabled(autoEnter)
            }
            return params.build()
        }
        return null
    }

    fun updatePipParams(autoEnter: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val activity = context.findActivity() ?: return
            getPipParams(autoEnter)?.let {
                try {
                    activity.setPictureInPictureParams(it)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    val receiver = remember(pipId) {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_MEDIA_CONTROL) {
                    val id = intent.getIntExtra(EXTRA_ID, 0)
                    if (id == pipId) {
                        when (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                            CONTROL_TYPE_PLAY -> currentOnPlay()
                            CONTROL_TYPE_PAUSE -> currentOnPause()
                            CONTROL_TYPE_REWIND -> currentOnRewind()
                            CONTROL_TYPE_FORWARD -> currentOnForward()
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(context, pipId, isActive) {
        if (!isActive) return@DisposableEffect onDispose {}

        val filter = IntentFilter(ACTION_MEDIA_CONTROL).apply {
            addDataScheme("monogram")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    DisposableEffect(context) {
        val activity = context.findActivity()
        val pipConsumer = Consumer<PictureInPictureModeChangedInfo> { info ->
            currentOnPipModeChanged(info.isInPictureInPictureMode)
        }
        activity?.addOnPictureInPictureModeChangedListener(pipConsumer)
        onDispose {
            activity?.removeOnPictureInPictureModeChangedListener(pipConsumer)
            // Disable auto-PIP when leaving
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    activity?.setPictureInPictureParams(
                        PictureInPictureParams.Builder()
                            .setAutoEnterEnabled(false)
                            .build()
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    LaunchedEffect(isPlaying, videoAspectRatio, isActive) {
        updatePipParams(isActive && isPlaying)
    }
}

fun enterPipMode(context: Context, isPlaying: Boolean, videoAspectRatio: Float, pipId: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val activity = context.findActivity() ?: return
        val constrainedRatio = videoAspectRatio.coerceIn(0.4184f, 2.39f)
        val ratio = if (constrainedRatio.isNaN() || constrainedRatio <= 0f) Rational(16, 9)
        else Rational((constrainedRatio * 1000).toInt(), 1000)

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)

        val actions = mutableListOf<RemoteAction>()

        fun createAction(controlType: Int, iconResId: Int, title: String): RemoteAction {
            val intent = Intent(ACTION_MEDIA_CONTROL).apply {
                putExtra(EXTRA_CONTROL_TYPE, controlType)
                putExtra(EXTRA_ID, pipId)
                data = "monogram://pip/$pipId/$controlType".toUri()
                setPackage(context.packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                controlType,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return RemoteAction(Icon.createWithResource(context, iconResId), title, title, pendingIntent)
        }

        // Rewind
        actions.add(createAction(CONTROL_TYPE_REWIND, R.drawable.ic_replay_10, context.getString(R.string.pip_rewind)))

        // Play/Pause
        val playPauseAction = if (isPlaying) {
            createAction(CONTROL_TYPE_PAUSE, R.drawable.ic_pause, context.getString(R.string.pip_pause))
        } else {
            createAction(CONTROL_TYPE_PLAY, R.drawable.ic_play, context.getString(R.string.pip_play))
        }
        actions.add(playPauseAction)

        // Forward
        actions.add(createAction(CONTROL_TYPE_FORWARD, R.drawable.ic_forward_10, context.getString(R.string.pip_forward)))

        params.setActions(actions)

        activity.enterPictureInPictureMode(params.build())
    }
}
