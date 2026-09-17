package org.monogram

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.arkivanov.decompose.defaultComponentContext
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.ThemePreference
import org.monogram.core.ui.media.LocalPictureInPictureActive
import org.monogram.core.ui.media.LocalPictureInPictureController
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.MediaSurface
import org.monogram.core.ui.media.MediaViewerPipStage
import org.monogram.core.ui.media.PictureInPictureController
import org.monogram.core.ui.perf.perfSpan
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.core.common.AppLog
import org.monogram.push.NotificationPresenter
import org.monogram.root.RootComponent
import org.monogram.root.RootContent
import android.graphics.Color as AndroidColor

class MainActivity : ComponentActivity() {
    private lateinit var root: RootComponent
    private var idleJob: Job? = null
    private var inPictureInPicture by mutableStateOf(false)

    /** Only this activity owns the PiP window; the media viewer stays in its dialog. */
    private val pictureInPicture = object : PictureInPictureController {
        override val supported: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

        override fun enter() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            if (!supported) return
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            runCatching { enterPictureInPictureMode(params) }
        }

        override fun updateActions(playing: Boolean, canSkipNext: Boolean) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            if (!supported) return
            runCatching {
                setPictureInPictureParams(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build(),
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        MediaPlaybackHolder.peek()?.attachSurface(
            if (isInPictureInPictureMode) MediaSurface.PIP
            else MediaSurface.MINI_PLAYER,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val crashLog = AppLog.readCrashLog()
        if (crashLog != null) {
            AppLog.clearCrashLog()
            startActivity(CrashActivity.intent(this, crashLog))
            finish()
            return
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = AndroidColor.TRANSPARENT,
                darkScrim = AndroidColor.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = AndroidColor.TRANSPARENT,
                darkScrim = AndroidColor.TRANSPARENT,
            ),
        )
        val app = application as MonogramApp
        val startOnHome = perfSpan("main:isAuthorized") { app.sessionStore.isAuthorizedBlocking() }
        val componentContext = defaultComponentContext()
        root = RootComponent(
            componentContext = componentContext,
            storeFactory = DefaultStoreFactory(),
            client = app.client,
            warmup = app.warmup,
            sessionStore = app.sessionStore,
            mediaRepository = app.mediaRepository,
            startOnHome = startOnHome,
            pushRegistration = app.push,
            notificationLocal = app.notifications,
        )
        handleIncomingIntent(intent)
        setContent {
            val appearance by AppearanceSettings.state.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (appearance.theme) {
                ThemePreference.Light -> false
                ThemePreference.Dark -> true
                ThemePreference.System -> systemDark
            }
            MonogramTheme(
                darkTheme = darkTheme,
                dynamicColor = appearance.dynamicColor,
                accentPreset = appearance.accentPreset,
            ) {
                val pip = remember { pictureInPicture }
                CompositionLocalProvider(
                    LocalPictureInPictureController provides pip,
                    LocalPictureInPictureActive provides inPictureInPicture,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        if (inPictureInPicture) {
                            val session = MediaPlaybackHolder.peek()
                            if (session?.current != null) {
                                MediaViewerPipStage(session = session, modifier = Modifier.fillMaxSize())
                            } else {
                                RootContent(component = root, modifier = Modifier.fillMaxSize())
                            }
                        } else {
                            RootContent(
                                component = root,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        idleJob?.cancel()
        val app = application as MonogramApp
        app.push.setForeground(true)
        app.push.requestPermission(this)
        lifecycleScope.launch { runCatching { app.client.connect() } }
    }

    override fun onStop() {
        val app = application as MonogramApp
        app.push.setForeground(false)
        idleJob?.cancel()
        idleJob = lifecycleScope.launch {
            delay(120_000)
            if (!app.push.appForeground && app.notifications.token().isNotBlank()) {
                app.client.hibernate()
            }
        }
        super.onStop()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == NotificationPresenter.ACTION_OPEN_CHAT) {
            val chatId = intent.getLongExtra(NotificationPresenter.EXTRA_CHAT_ID, 0L)
            val messageId = intent.getIntExtra(NotificationPresenter.EXTRA_MESSAGE_ID, 0)
            if (chatId != 0L) root.openFromNotification(chatId, messageId)
            return
        }
        val uri = intent.dataString ?: return
        root.openTelegramUri(uri)
    }
}
