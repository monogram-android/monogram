package org.monogram.app.di

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.monogram.core.Logger
import org.monogram.data.di.dataModule
import org.monogram.domain.managers.*
import org.monogram.domain.repository.*
import org.monogram.presentation.core.util.*
import org.monogram.presentation.di.uiModule
import org.monogram.presentation.features.chats.currentChat.components.ExoPlayerCache
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.settings.storage.CacheController

@SuppressLint("WrongConstant")
val appModule = module {
    includes(uiModule, dataModule)

    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    single<AppPreferencesProvider> { AppPreferences(androidContext(), get()) }
    single { get<AppPreferencesProvider>() as AppPreferences }
    single<EditorSnippetProvider> { EditorSnippetPreferences(androidContext()) }
    single<CacheProvider> { CachePreferences(androidContext()) }
    single<BotPreferencesProvider> { BotPreferences(androidContext()) }

    single { ExoPlayerCache() }
    single { CacheController(androidContext(), get()) }
    single { VideoPlayerPool(androidContext(), get(), get()) }
    single<ClipManager> {
        ClipManagerImpl(
            androidContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager,
        )
    }
    single<Logger> { LoggerImpl() }

    factory<PhoneManager> {
        PhoneManagerImpl(
            androidContext().getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager,
        )
    }
    factory<DomainManager> { DomainManagerImpl(androidContext(), get<ExternalNavigator>().packageName) }
    factory<AssetsManager> { AssetsManagerImpl(androidContext()) }
    factory<DistrManager> { DistrManagerImpl(androidContext()) }
    factory<MessageDisplayer> { ToastMessageDisplayer(androidContext()) }
    factory<ExternalNavigator> { ExternalNavigatorImpl(androidContext()) }
    factory<IDownloadUtils> { DownloadUtils(androidContext(), get()) }
}
