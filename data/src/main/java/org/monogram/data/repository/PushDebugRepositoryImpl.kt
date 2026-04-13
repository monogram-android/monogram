package org.monogram.data.repository

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.monogram.data.push.PushSyncTrigger
import org.monogram.data.push.UnifiedPushManager
import org.monogram.data.service.TdNotificationService
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.PushDebugRepository
import org.monogram.domain.repository.PushDiagnostics
import org.monogram.domain.repository.UnifiedPushDebugStatus

class PushDebugRepositoryImpl(
    private val context: Context,
    private val appPreferences: AppPreferencesProvider,
    private val unifiedPushManager: UnifiedPushManager,
    private val pushSyncTrigger: PushSyncTrigger,
    private val scope: CoroutineScope
) : PushDebugRepository {

    private val _diagnostics = MutableStateFlow(PushDiagnostics())
    override val diagnostics: StateFlow<PushDiagnostics> = _diagnostics

    init {
        val prefsFlow = combine(
            appPreferences.pushProvider,
            appPreferences.backgroundServiceEnabled,
            appPreferences.hideForegroundNotification,
            appPreferences.isPowerSavingMode,
            appPreferences.isWakeLockEnabled
        ) { provider, bgEnabled, hideForeground, powerSaving, wakeLock ->
            PrefSnapshot(provider, bgEnabled, hideForeground, powerSaving, wakeLock)
        }

        scope.launch {
            combine(
                prefsFlow,
                appPreferences.batteryOptimizationEnabled,
                TdNotificationService.isRunningFlow,
                unifiedPushManager.status,
                unifiedPushManager.endpoint
            ) { prefs, batteryOpt, serviceRunning, unifiedStatus, endpoint ->
                PushDiagnostics(
                    pushProvider = prefs.pushProvider,
                    backgroundServiceEnabled = prefs.backgroundServiceEnabled,
                    hideForegroundNotification = prefs.hideForegroundNotification,
                    isPowerSavingMode = prefs.isPowerSavingMode,
                    isWakeLockEnabled = prefs.isWakeLockEnabled,
                    batteryOptimizationEnabled = batteryOpt,
                    isTdNotificationServiceRunning = serviceRunning,
                    unifiedPushStatus = when (unifiedStatus) {
                        UnifiedPushManager.Status.IDLE -> UnifiedPushDebugStatus.IDLE
                        UnifiedPushManager.Status.REGISTERING -> UnifiedPushDebugStatus.REGISTERING
                        UnifiedPushManager.Status.REGISTERED -> UnifiedPushDebugStatus.REGISTERED
                        UnifiedPushManager.Status.FAILED -> UnifiedPushDebugStatus.FAILED
                        UnifiedPushManager.Status.UNREGISTERED -> UnifiedPushDebugStatus.UNREGISTERED
                    },
                    unifiedPushEndpoint = endpoint,
                    unifiedPushSavedDistributor = unifiedPushManager.getSavedDistributor(),
                    unifiedPushAckDistributor = unifiedPushManager.getAckDistributor(),
                    unifiedPushDistributorsCount = unifiedPushManager.getDistributors().size
                )
            }.collect {
                _diagnostics.value = it
            }
        }
    }

    override fun triggerTestPush() {
        ensureDebugChannel()

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(
            context,
            9110,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, DEBUG_CHANNEL_ID)
            .setSmallIcon(org.monogram.data.R.drawable.message_outline)
            .setContentTitle("MonoGram Debug Push")
            .setContentText("Synthetic push signal delivered")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            NotificationManagerCompat.from(context).notify(DEBUG_NOTIFICATION_ID, builder.build())
        }

        pushSyncTrigger.requestSync("debug_test_push")
    }

    private fun ensureDebugChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            DEBUG_CHANNEL_ID,
            "Debug Push",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Debug notifications for push diagnostics"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private data class PrefSnapshot(
        val pushProvider: org.monogram.domain.repository.PushProvider,
        val backgroundServiceEnabled: Boolean,
        val hideForegroundNotification: Boolean,
        val isPowerSavingMode: Boolean,
        val isWakeLockEnabled: Boolean
    )

    private companion object {
        const val DEBUG_CHANNEL_ID = "debug_push_channel"
        const val DEBUG_NOTIFICATION_ID = 9110
    }
}
