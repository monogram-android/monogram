package org.monogram.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import org.koin.android.ext.android.inject
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.PushProvider
import org.monogram.domain.repository.StringProvider

class TdNotificationService : Service() {
    private val appPreferences: AppPreferencesProvider by inject()
    private val stringProvider: StringProvider by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isServiceRunning = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var checkJob: Job? = null

    companion object {
        const val FOREGROUND_CHANNEL_ID = "tdlib_background_service"
        const val FOREGROUND_ID = 999
        const val ACTION_STOP = "org.monogram.data.service.ACTION_STOP"
        private const val CHECK_INTERVAL = 60_000L // 1 minute
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundService()
            return START_NOT_STICKY
        }

        if (appPreferences.pushProvider.value == PushProvider.FCM) {
            stopForegroundService()
            return START_NOT_STICKY
        }

        if (!isServiceRunning) {
            isServiceRunning = true
            acquireWakeLock()
            startForegroundNotification()
            startListeningUpdates()
            startPeriodicCheck()
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (appPreferences.pushProvider.value == PushProvider.FCM) return
        if (appPreferences.isPowerSavingMode.value) return
        if (!appPreferences.isWakeLockEnabled.value) return

        if (wakeLock == null) {
            val powerManager = getSystemService(PowerManager::class.java)
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "monogram:TdNotificationServiceLock")
            wakeLock?.setReferenceCounted(false)
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun startForegroundNotification(status: String? = null) {
        createForegroundChannel()

        val notificationIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, TdNotificationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationBuilder = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle("MonoGram")
            .setContentText(status ?: stringProvider.getString("notification_service_waiting"))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(org.monogram.data.R.drawable.message_outline)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                stringProvider.getString("notification_service_stop"),
                stopPendingIntent
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notificationBuilder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        val notification = notificationBuilder.build()

        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(
                this,
                FOREGROUND_ID,
                notification,
                foregroundServiceType
            )

            if (appPreferences.hideForegroundNotification.value) {
                serviceScope.launch {
                    delay(2000)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_DETACH)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(false)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun stopForegroundService() {
        isServiceRunning = false
        appPreferences.setBackgroundServiceEnabled(false)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopSelf()
    }

    private fun startListeningUpdates() {
        serviceScope.launch {
            combine(
                appPreferences.isPowerSavingMode,
                appPreferences.isWakeLockEnabled,
                appPreferences.batteryOptimizationEnabled,
                appPreferences.pushProvider
            ) { powerSaving, wakeLockEnabled, batteryOptimization, pushProvider ->
                Quadruple(powerSaving, wakeLockEnabled, batteryOptimization, pushProvider)
            }.collect { (isPowerSaving, isWakeLockEnabled, isBatteryOptimization, pushProvider) ->
                if (pushProvider == PushProvider.FCM) {
                    stopForegroundService()
                    return@collect
                }
                
                if (isPowerSaving || !isWakeLockEnabled || isBatteryOptimization) {
                    delay(5000)
                    releaseWakeLock()
                } else {
                    if (isServiceRunning) {
                        acquireWakeLock()
                    }
                }
            }
        }
    }

    private fun startPeriodicCheck() {
        checkJob?.cancel()
        checkJob = serviceScope.launch {
            while (isActive) {
                if (appPreferences.pushProvider.value == PushProvider.FCM || (!appPreferences.backgroundServiceEnabled.value && appPreferences.pushProvider.value == PushProvider.GMS_LESS)) {
                    stopForegroundService()
                    break
                }

                val shouldReleaseWakeLock = appPreferences.isPowerSavingMode.value ||
                        !appPreferences.isWakeLockEnabled.value ||
                        appPreferences.batteryOptimizationEnabled.value

                if (shouldReleaseWakeLock) {
                    releaseWakeLock()
                } else {
                    if (wakeLock?.isHeld == false) {
                        acquireWakeLock()
                    }
                }

                delay(CHECK_INTERVAL)
            }
        }
    }

    private fun createForegroundChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                stringProvider.getString("notification_service_channel_name"),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = stringProvider.getString("notification_service_channel_description")
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        serviceScope.cancel()
        releaseWakeLock()
    }
}

data class Quadruple<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)