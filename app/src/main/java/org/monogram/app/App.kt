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
import org.monogram.domain.managers.DistrManager
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.PushProvider
import org.monogram.presentation.di.AppContainer
import org.monogram.presentation.di.KoinAppContainer
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.jvm.java
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

        val prefs = get<AppPreferencesProvider>()
        if (!(isGmsAvailable && isFcmAvailable) && prefs.pushProvider.value == PushProvider.FCM) {
            prefs.setPushProvider(PushProvider.GMS_LESS)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return get<ImageLoader>()
    }
}
