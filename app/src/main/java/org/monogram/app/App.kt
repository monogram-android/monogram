package org.monogram.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.monogram.app.di.*
import org.monogram.core.Logger
import org.monogram.data.di.TdLibClient
import org.monogram.data.di.TdNotificationManager
import org.monogram.data.di.dataModule
import org.monogram.domain.managers.*
import org.monogram.domain.repository.*
import org.monogram.presentation.core.util.*
import org.monogram.presentation.di.AppContainer
import org.monogram.presentation.di.KoinAppContainer
import org.monogram.presentation.di.uiModule
import org.monogram.presentation.features.chats.currentChat.components.ExoPlayerCache
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.settings.storage.CacheController
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class App : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        initCrashHandler()
        initKoin()
        initTdLib()
        initMapLibre()
        checkGmsAvailability()
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

    private fun initTdLib() {
        get<TdLibClient>()
        get<AuthRepository>()
        get<TdNotificationManager>()
    }

    private fun initMapLibre() {
        MapLibre.getInstance(this, null, WellKnownTileServer.MapLibre)
    }

    private fun checkGmsAvailability() {
        val distrManager = get<DistrManager>()
        val isGmsAvailable = distrManager.isGmsAvailable()

        val prefs = get<AppPreferencesProvider>()
        if (!isGmsAvailable && prefs.pushProvider.value == PushProvider.FCM) {
            prefs.setPushProvider(PushProvider.GMS_LESS)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return get<ImageLoader>()
    }
}

@SuppressLint("WrongConstant")
val appModule = module {
    includes(uiModule, dataModule)

    // Utils
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // Preferences
    single<AppPreferencesProvider> { AppPreferences(androidContext(), get()) }
    single { get<AppPreferencesProvider>() as AppPreferences }
    single<CacheProvider> { CachePreferences(androidContext()) }
    single<BotPreferencesProvider> { BotPreferences(androidContext()) }

    // Utils
    single { ExoPlayerCache() }
    single { CacheController(androidContext(), get()) }
    single { VideoPlayerPool(androidContext(), get()) }
    single<ClipManager> { ClipManagerImpl(androidContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager) }
    single<Logger> { LoggerImpl() }

    // Factories
    factory<PhoneManager> { PhoneManagerImpl(androidContext().getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager) }
    factory<DomainManager> { DomainManagerImpl(androidContext(), get<ExternalNavigator>().packageName) }
    factory<AssetsManager> { AssetsManagerImpl(androidContext()) }
    factory<DistrManager> { DistrManagerImpl(androidContext()) }
    factory<MessageDisplayer> { ToastMessageDisplayer(androidContext()) }
    factory<ExternalNavigator> { ExternalNavigatorImpl(androidContext()) }
    factory<IDownloadUtils> { DownloadUtils(androidContext(), get()) }
}