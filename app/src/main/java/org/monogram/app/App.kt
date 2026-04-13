package org.monogram.app

import android.app.Application
import android.content.Intent
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.monogram.app.di.appModule
import org.monogram.data.infra.DataMemoryPressureHandler
import org.monogram.domain.managers.DistrManager
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.PushProvider
import org.monogram.presentation.di.AppContainer
import org.monogram.presentation.di.KoinAppContainer
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class App : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        initCrashHandler()
        initKoin()
        initMapLibre()
        checkPushAvailability()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            trimInMemoryCaches("onTrimMemory:$level")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        trimInMemoryCaches("onLowMemory")
    }

    private fun initCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                val stackTrace = sw.toString()

                Log.d("CrashHandler", stackTrace)

                val intent = Intent(this, CrashActivity::class.java).apply {
                    putExtra("EXTRA_CRASH_LOG", stackTrace)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
                exitProcess(1)
            } catch (e: Exception) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun initKoin() {
        val koin = startKoin {
            androidContext(this@App)
            modules(appModule)
        }.koin
        container = KoinAppContainer(koin)
    }

    private fun initMapLibre() {
        MapLibre.getInstance(this, null, WellKnownTileServer.MapLibre)
    }

    private fun checkPushAvailability() {
        val distrManager = get<DistrManager>()
        val isGmsAvailable = distrManager.isGmsAvailable()
        val isFcmAvailable = distrManager.isFcmAvailable()
        val isUnifiedPushAvailable = distrManager.isUnifiedPushDistributorAvailable()

        val prefs = get<AppPreferencesProvider>()
        val currentProvider = prefs.pushProvider.value
        if (currentProvider == PushProvider.FCM && !(isGmsAvailable && isFcmAvailable)) {
            val fallback =
                if (isUnifiedPushAvailable) PushProvider.UNIFIED_PUSH else PushProvider.GMS_LESS
            prefs.setPushProvider(fallback)
        } else if (currentProvider == PushProvider.UNIFIED_PUSH && !isUnifiedPushAvailable) {
            val fallback =
                if (isGmsAvailable && isFcmAvailable) PushProvider.FCM else PushProvider.GMS_LESS
            prefs.setPushProvider(fallback)
        }
    }

    private fun trimInMemoryCaches(reason: String) {
        if (!::container.isInitialized) return
        runCatching {
            get<DataMemoryPressureHandler>().clearDataCaches(reason)
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear data caches for $reason", error)
        }

        runCatching {
            get<ImageLoader>().memoryCache?.clear()
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear Coil memory cache for $reason", error)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return get<ImageLoader>()
    }

    companion object {
        private const val TAG = "App"
    }
}
