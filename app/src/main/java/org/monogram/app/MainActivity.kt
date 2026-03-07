package org.monogram.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
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

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permissions results if needed
    }

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
        checkAndRequestPermissions()
        requestIgnoreBatteryOptimizations()

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

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent()
            val packageName = packageName
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }
}