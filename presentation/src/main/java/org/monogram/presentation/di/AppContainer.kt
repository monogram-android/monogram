package org.monogram.presentation.di

import coil3.ImageLoader
import kotlinx.coroutines.CoroutineScope
import org.monogram.core.DispatcherProvider
import org.monogram.core.Logger
import org.monogram.domain.managers.*
import org.monogram.domain.repository.*
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.components.ExoPlayerCache
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.settings.storage.CacheController

interface AppContainer {
    val preferences: PreferencesContainer
    val repositories: RepositoriesContainer
    val utils: UtilsContainer
    val cacheProvider: CacheProvider
}

interface PreferencesContainer {
    val appPreferences: AppPreferences
    val appPreferencesProvider: AppPreferencesProvider
    val botPreferencesProvider: BotPreferencesProvider
}

interface RepositoriesContainer {
    val authRepository: AuthRepository
    val chatsListRepository: ChatsListRepository
    val messageRepository: MessageRepository
    val userRepository: UserRepository
    val settingsRepository: SettingsRepository
    val locationRepository: LocationRepository
    val privacyRepository: PrivacyRepository
    val linkHandlerRepository: LinkHandlerRepository
    val externalProxyRepository: ExternalProxyRepository
    val stickerRepository: StickerRepository
    val updateRepository: UpdateRepository
}

interface UtilsContainer {
    val videoPlayerPool: VideoPlayerPool
    val exoPlayerCache: ExoPlayerCache
    val cacheController: CacheController
    val imageLoader: ImageLoader
    val appCoroutineScope: CoroutineScope
    val clipManager: ClipManager
    val dispatcherProvider: DispatcherProvider
    val logger: Logger
    fun messageDisplayer(): MessageDisplayer
    fun externalNavigator(): ExternalNavigator
    fun phoneManager(): PhoneManager
    fun domainManager(): DomainManager
    fun assetsManager(): AssetsManager
    fun distrManager(): DistrManager
    fun downloadUtils(): IDownloadUtils
    fun stringProvider(): StringProvider
}
