package org.monogram.app.di

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.monogram.core.Logger
import org.monogram.data.di.dataModule
import org.monogram.domain.managers.AssetsManager
import org.monogram.domain.managers.ClipManager
import org.monogram.domain.managers.DistrManager
import org.monogram.domain.managers.DomainManager
import org.monogram.domain.managers.PhoneManager
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.BotPreferencesProvider
import org.monogram.domain.repository.CacheProvider
import org.monogram.domain.repository.EditorSnippetProvider
import org.monogram.domain.repository.ExternalNavigator
import org.monogram.domain.repository.MessageDisplayer
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.BotPreferences
import org.monogram.presentation.core.util.CachePreferences
import org.monogram.presentation.core.util.DateFormatManager
import org.monogram.presentation.core.util.DateFormatManagerImpl
import org.monogram.presentation.core.util.DownloadUtils
import org.monogram.presentation.core.util.EditorSnippetPreferences
import org.monogram.presentation.core.util.ExternalNavigatorImpl
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.core.util.ToastMessageDisplayer
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

    single<DateFormatManager> { DateFormatManagerImpl(DateFormat.is24HourFormat(androidContext())) }

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
