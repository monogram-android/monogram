package org.monogram.presentation.di

import coil3.ImageLoader
import kotlinx.coroutines.CoroutineScope
import org.koin.core.Koin
import org.monogram.core.DispatcherProvider
import org.monogram.core.Logger
import org.monogram.domain.managers.*
import org.monogram.domain.repository.*
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.components.ExoPlayerCache
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.settings.storage.CacheController

class KoinAppContainer(koin: Koin) : AppContainer {
    override val preferences = KoinPreferencesContainer(koin)
    override val cacheProvider: CacheProvider by lazy { koin.get() }
    override val repositories = KoinRepositoriesContainer(koin)
    override val utils = KoinUtilsContainer(koin)
}

class KoinPreferencesContainer(private val koin: Koin) : PreferencesContainer {
    override val appPreferences: AppPreferences by lazy { koin.get() }
    override val appPreferencesProvider: AppPreferencesProvider by lazy { koin.get() }
    override val botPreferencesProvider: BotPreferencesProvider by lazy { koin.get() }
}

class KoinRepositoriesContainer(private val koin: Koin) : RepositoriesContainer {
    override val authRepository: AuthRepository by lazy { koin.get() }
    override val chatsListRepository: ChatsListRepository by lazy { koin.get() }
    override val messageRepository: MessageRepository by lazy { koin.get() }
    override val userRepository: UserRepository by lazy { koin.get() }
    override val settingsRepository: SettingsRepository by lazy { koin.get() }
    override val locationRepository: LocationRepository by lazy { koin.get() }
    override val privacyRepository: PrivacyRepository by lazy { koin.get() }
    override val linkHandlerRepository: LinkHandlerRepository by lazy { koin.get() }
    override val externalProxyRepository: ExternalProxyRepository by lazy { koin.get() }
    override val stickerRepository: StickerRepository by lazy { koin.get() }
    override val updateRepository: UpdateRepository by lazy { koin.get() }
}

class KoinUtilsContainer(private val koin: Koin) : UtilsContainer {
    override val appCoroutineScope: CoroutineScope by lazy { koin.get() }
    override val videoPlayerPool: VideoPlayerPool by lazy { koin.get() }
    override val exoPlayerCache: ExoPlayerCache by lazy { koin.get() }
    override val cacheController: CacheController by lazy { koin.get() }
    override val imageLoader: ImageLoader by lazy { koin.get() }
    override val clipManager: ClipManager by lazy { koin.get() }
    override val dispatcherProvider: DispatcherProvider by lazy { koin.get() }
    override val logger: Logger by lazy { koin.get() }

    override fun messageDisplayer(): MessageDisplayer = koin.get()
    override fun externalNavigator(): ExternalNavigator = koin.get()
    override fun phoneManager(): PhoneManager = koin.get()
    override fun domainManager(): DomainManager = koin.get()
    override fun assetsManager(): AssetsManager = koin.get()
    override fun distrManager(): DistrManager = koin.get()

    override fun downloadUtils(): IDownloadUtils = koin.get()
    override fun stringProvider(): StringProvider = koin.get()
}