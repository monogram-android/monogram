package org.monogram.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.WindowInfoTracker
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.retainedComponent
import org.koin.android.ext.android.inject
import org.monogram.app.ui.theme.AppThemeContainer
import org.monogram.data.service.TdNotificationService
import org.monogram.domain.repository.PushProvider
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.LocalVideoPlayerPool
import org.monogram.presentation.core.util.NightMode
import org.monogram.presentation.features.chats.currentChat.components.chats.LocalLinkHandler
import org.monogram.presentation.root.DefaultAppComponentContext
import org.monogram.presentation.root.DefaultRootComponent
import org.monogram.presentation.root.RootComponent
import java.util.Calendar

class MainActivity : FragmentActivity() {
    private lateinit var root: RootComponent
    private val appPreferences: AppPreferences by inject()

    @Volatile
    private var keepSplashOnScreen: Boolean = true

    @OptIn(ExperimentalDecomposeApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(resolveStartupTheme())
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { keepSplashOnScreen }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { provider ->
                provider.view.animate()
                    .alpha(0f)
                    .setDuration(220L)
                    .withEndAction { provider.remove() }
                    .start()
            }
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        root = retainedComponent { componentContext ->
            DefaultRootComponent(
                DefaultAppComponentContext(
                    componentContext = componentContext,
                    container = (application as App).container,
                )
            )
        }

        handleIntent(intent)

        val windowInfoTracker = WindowInfoTracker.getOrCreate(this)

        setContent {
            LaunchedEffect(Unit) {
                keepSplashOnScreen = false
                startNotificationService()
            }

            val windowLayoutInfo by windowInfoTracker.windowLayoutInfo(this)
                .collectAsStateWithLifecycle(initialValue = null)

            AppThemeContainer(root.appPreferences) {
                CompositionLocalProvider(
                    LocalLinkHandler provides root::handleLink,
                    LocalVideoPlayerPool provides root.videoPlayerPool
                ) {
                    MainContent(
                        root = root,
                        windowLayoutInfo = windowLayoutInfo
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val data = intent.dataString
        if (data != null) {
            root.handleLink(data)
            return
        }

        val chatId = intent.getLongExtra("chat_id", 0L)
        if (chatId != 0L) {
            root.navigateToChat(chatId)
        }
    }

    private fun startNotificationService() {
        if (appPreferences.pushProvider.value != PushProvider.GMS_LESS) return
        val intent = Intent(this, TdNotificationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun resolveStartupTheme(): Int {
        return when (appPreferences.nightMode.value) {
            NightMode.SYSTEM -> R.style.Theme_MonoGram_Startup
            NightMode.LIGHT -> R.style.Theme_MonoGram_Startup_Light
            NightMode.DARK -> R.style.Theme_MonoGram_Startup_Dark
            NightMode.SCHEDULED -> {
                val calendar = Calendar.getInstance()
                val now = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

                val start = appPreferences.nightModeStartTime.value
                    .split(":")
                    .takeIf { it.size == 2 }
                    ?.let { it[0].toIntOrNull()?.times(60)?.plus(it[1].toIntOrNull() ?: 0) }
                    ?: 22 * 60

                val end = appPreferences.nightModeEndTime.value
                    .split(":")
                    .takeIf { it.size == 2 }
                    ?.let { it[0].toIntOrNull()?.times(60)?.plus(it[1].toIntOrNull() ?: 0) }
                    ?: 7 * 60

                if (start < end) {
                    if (now in start until end) {
                        R.style.Theme_MonoGram_Startup_Dark
                    } else {
                        R.style.Theme_MonoGram_Startup_Light
                    }
                } else {
                    if (now >= start || now < end) {
                        R.style.Theme_MonoGram_Startup_Dark
                    } else {
                        R.style.Theme_MonoGram_Startup_Light
                    }
                }
            }

            NightMode.BRIGHTNESS -> {
                val brightness = runCatching {
                    Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255)
                }.getOrDefault(255)
                if (brightness / 255f <= appPreferences.nightModeBrightnessThreshold.value) {
                    R.style.Theme_MonoGram_Startup_Dark
                } else {
                    R.style.Theme_MonoGram_Startup_Light
                }
            }
        }
    }
}
