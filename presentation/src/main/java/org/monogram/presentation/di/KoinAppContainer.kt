package org.monogram.presentation.di

import coil3.ImageLoader
import kotlinx.coroutines.CoroutineScope
import org.koin.core.Koin
import org.monogram.core.DispatcherProvider
import org.monogram.core.Logger
import org.monogram.domain.managers.AssetsManager
import org.monogram.domain.managers.ClipManager
import org.monogram.domain.managers.DistrManager
import org.monogram.domain.managers.DomainManager
import org.monogram.domain.managers.PhoneManager
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.AttachMenuBotRepository
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.BotPreferencesProvider
import org.monogram.domain.repository.BotRepository
import org.monogram.domain.repository.CacheProvider
import org.monogram.domain.repository.ChatCreationRepository
import org.monogram.domain.repository.ChatEventLogRepository
import org.monogram.domain.repository.ChatFolderRepository
import org.monogram.domain.repository.ChatInfoRepository
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.ChatOperationsRepository
import org.monogram.domain.repository.ChatSearchRepository
import org.monogram.domain.repository.ChatSettingsRepository
import org.monogram.domain.repository.ChatStatisticsRepository
import org.monogram.domain.repository.EditorSnippetProvider
import org.monogram.domain.repository.EmojiRepository
import org.monogram.domain.repository.ExternalNavigator
import org.monogram.domain.repository.ExternalProxyRepository
import org.monogram.domain.repository.FileRepository
import org.monogram.domain.repository.ForumTopicsRepository
import org.monogram.domain.repository.GifRepository
import org.monogram.domain.repository.InlineBotRepository
import org.monogram.domain.repository.LinkHandlerRepository
import org.monogram.domain.repository.LocationRepository
import org.monogram.domain.repository.MessageAiRepository
import org.monogram.domain.repository.MessageDisplayer
import org.monogram.domain.repository.MessageRepository
import org.monogram.domain.repository.NetworkStatisticsRepository
import org.monogram.domain.repository.NotificationSettingsRepository
import org.monogram.domain.repository.PaymentRepository
import org.monogram.domain.repository.PremiumRepository
import org.monogram.domain.repository.PrivacyRepository
import org.monogram.domain.repository.ProfilePhotoRepository
import org.monogram.domain.repository.PushDebugRepository
import org.monogram.domain.repository.SessionRepository
import org.monogram.domain.repository.SponsorRepository
import org.monogram.domain.repository.StickerRepository
import org.monogram.domain.repository.StorageRepository
import org.monogram.domain.repository.StringProvider
import org.monogram.domain.repository.UpdateRepository
import org.monogram.domain.repository.UserProfileEditRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.domain.repository.WallpaperRepository
import org.monogram.domain.repository.WebAppRepository
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
    override val editorSnippetProvider: EditorSnippetProvider by lazy { koin.get() }
}

class KoinRepositoriesContainer(private val koin: Koin) : RepositoriesContainer {
    override val authRepository: AuthRepository by lazy { koin.get() }
    override val chatListRepository: ChatListRepository by lazy { koin.get() }
    override val chatFolderRepository: ChatFolderRepository by lazy { koin.get() }
    override val chatOperationsRepository: ChatOperationsRepository by lazy { koin.get() }
    override val chatSearchRepository: ChatSearchRepository by lazy { koin.get() }
    override val forumTopicsRepository: ForumTopicsRepository by lazy { koin.get() }
    override val chatSettingsRepository: ChatSettingsRepository by lazy { koin.get() }
    override val chatCreationRepository: ChatCreationRepository by lazy { koin.get() }
    override val messageRepository: MessageRepository by lazy { koin.get() }
    override val inlineBotRepository: InlineBotRepository by lazy { koin.get() }
    override val chatEventLogRepository: ChatEventLogRepository by lazy { koin.get() }
    override val messageAiRepository: MessageAiRepository by lazy { koin.get() }
    override val paymentRepository: PaymentRepository by lazy { koin.get() }
    override val fileRepository: FileRepository by lazy { koin.get() }
    override val webAppRepository: WebAppRepository by lazy { koin.get() }
    override val userRepository: UserRepository by lazy { koin.get() }
    override val userProfileEditRepository: UserProfileEditRepository by lazy { koin.get() }
    override val profilePhotoRepository: ProfilePhotoRepository by lazy { koin.get() }
    override val chatInfoRepository: ChatInfoRepository by lazy { koin.get() }
    override val premiumRepository: PremiumRepository by lazy { koin.get() }
    override val botRepository: BotRepository by lazy { koin.get() }
    override val chatStatisticsRepository: ChatStatisticsRepository by lazy { koin.get() }
    override val sponsorRepository: SponsorRepository by lazy { koin.get() }
    override val notificationSettingsRepository: NotificationSettingsRepository by lazy { koin.get() }
    override val sessionRepository: SessionRepository by lazy { koin.get() }
    override val wallpaperRepository: WallpaperRepository by lazy { koin.get() }
    override val storageRepository: StorageRepository by lazy { koin.get() }
    override val networkStatisticsRepository: NetworkStatisticsRepository by lazy { koin.get() }
    override val attachMenuBotRepository: AttachMenuBotRepository by lazy { koin.get() }
    override val locationRepository: LocationRepository by lazy { koin.get() }
    override val privacyRepository: PrivacyRepository by lazy { koin.get() }
    override val linkHandlerRepository: LinkHandlerRepository by lazy { koin.get() }
    override val externalProxyRepository: ExternalProxyRepository by lazy { koin.get() }
    override val stickerRepository: StickerRepository by lazy { koin.get() }
    override val gifRepository: GifRepository by lazy { koin.get() }
    override val emojiRepository: EmojiRepository by lazy { koin.get() }
    override val updateRepository: UpdateRepository by lazy { koin.get() }
    override val pushDebugRepository: PushDebugRepository by lazy { koin.get() }
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