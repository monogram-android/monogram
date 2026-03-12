package org.monogram.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.fragment.app.FragmentActivity
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.retainedComponent
import org.koin.android.ext.android.inject
import org.monogram.data.service.TdNotificationService
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.PushProvider
import org.monogram.presentation.features.chats.currentChat.components.chats.LocalLinkHandler
import org.monogram.presentation.root.DefaultAppComponentContext
import org.monogram.presentation.root.DefaultRootComponent
import org.monogram.presentation.root.RootComponent

class MainActivity : FragmentActivity() {
    private lateinit var root: RootComponent
    private val appPreferences: AppPreferencesProvider by inject()

    @OptIn(ExperimentalDecomposeApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = retainedComponent { componentContext ->
            DefaultRootComponent(
                DefaultAppComponentContext(
                    componentContext = componentContext,
                    container = (application as App).container,
                )
            )
        }
        enableEdgeToEdge()

        handleIntent(intent)
        startNotificationService()

        setContent {
            AppThemeContainer(root.appPreferences) {
                CompositionLocalProvider(LocalLinkHandler provides root::handleLink) {
                    MainContent(root)
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
        if (appPreferences.pushProvider.value == PushProvider.FCM) return
        val intent = Intent(this, TdNotificationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}