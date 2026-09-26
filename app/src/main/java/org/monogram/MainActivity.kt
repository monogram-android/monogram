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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.monogram.core.common.Outcome
import org.monogram.core.common.PerfLog
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.ThemePreference
import org.monogram.core.ui.media.LocalPictureInPictureActive
import org.monogram.core.ui.media.LocalPictureInPictureController
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.MediaSurface
import org.monogram.core.ui.media.MediaViewerPipStage
import org.monogram.core.ui.media.PictureInPictureController
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.core.common.AppLog
import org.monogram.push.NotificationPresenter
import org.monogram.root.RootComponent
import org.monogram.root.RootContent
import org.monogram.root.IncomingShare
import org.monogram.root.IncomingShareStager
import android.graphics.Color as AndroidColor

class MainActivity : ComponentActivity() {
    private lateinit var root: RootComponent
    private val startupReady = CompletableDeferred<Unit>()
    private var idleJob: Job? = null
    private var startJob: Job? = null
    private var inPictureInPicture by mutableStateOf(false)
    private var pendingIncomingShare: IncomingShare? = null
    private var shareIntentHandled = false

    companion object {
        private const val STATE_INCOMING_SHARE = "main.incoming_share"
        private const val STATE_SHARE_INTENT_HANDLED = "main.share_intent_handled"
    }

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
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !startupReady.isCompleted }
        super.onCreate(savedInstanceState)
        pendingIncomingShare = IncomingShare.fromBundle(
            savedInstanceState?.getBundle(STATE_INCOMING_SHARE),
        )
        shareIntentHandled = savedInstanceState?.getBoolean(STATE_SHARE_INTENT_HANDLED) == true
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
        val componentContext = defaultComponentContext()
        lifecycleScope.launch {
            app.awaitReady()
            val startOnHome = withContext(Dispatchers.IO) {
                resolveStartOnHome(
                    sessionAuthorized = { app.sessionStore.isAuthorized() },
                    locallyAuthorized = { app.client.isLocallyAuthorized() },
                )
            }
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
                onIncomingShareConsumed = { pendingIncomingShare = null },
                appUpdate = app.appUpdate,
            )
            if (pendingIncomingShare != null) {
                root.openIncomingShare(pendingIncomingShare!!)
            } else if (!shareIntentHandled) {
                handleIncomingIntent(intent)
            }
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
            startupReady.complete(Unit)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            shareIntentHandled = false
        }
        if (startupReady.isCompleted) handleIncomingIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingIncomingShare?.toBundle()?.let { outState.putBundle(STATE_INCOMING_SHARE, it) }
        outState.putBoolean(
            STATE_SHARE_INTENT_HANDLED,
            shareIntentHandled || runCatching { root.hasIncomingShare() }.getOrDefault(false),
        )
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        idleJob?.cancel()
        startJob?.cancel()
        val app = application as MonogramApp
        startJob = lifecycleScope.launch {
            app.awaitReady()
            app.push.setForeground(true)
            app.push.requestPermission(this@MainActivity)
            startupReady.await()
            val started = PerfLog.nowMs()
            when (val result = runCatching { app.client.connect() }.getOrNull()) {
                is Outcome.Ok -> PerfLog.mark("activity:connect", PerfLog.nowMs() - started, "result=ok")
                is Outcome.Err -> PerfLog.mark("activity:connect", PerfLog.nowMs() - started, "result=err")
                null -> PerfLog.mark("activity:connect", PerfLog.nowMs() - started, "result=throw")
            }
        }
    }

    override fun onStop() {
        val app = application as MonogramApp
        startJob?.cancel()
        idleJob?.cancel()
        idleJob = lifecycleScope.launch {
            app.awaitReady()
            app.push.setForeground(false)
            delay(120_000)
            if (!app.push.appForeground && app.notifications.token().isNotBlank()) {
                app.client.hibernate()
            }
        }
        super.onStop()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            lifecycleScope.launch {
                val share = withContext(Dispatchers.IO) {
                    IncomingShareStager.stage(this@MainActivity, intent)
                } ?: return@launch
                pendingIncomingShare = share
                shareIntentHandled = true
                root.openIncomingShare(share)
            }
            return
        }
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
