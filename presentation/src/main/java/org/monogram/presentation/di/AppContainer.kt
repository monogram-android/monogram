package org.monogram.presentation.di

import coil3.ImageLoader
import kotlinx.coroutines.CoroutineScope
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
    val editorSnippetProvider: EditorSnippetProvider
}

interface RepositoriesContainer {
    val authRepository: AuthRepository
    val chatListRepository: ChatListRepository
    val chatFolderRepository: ChatFolderRepository
    val chatOperationsRepository: ChatOperationsRepository
    val chatSearchRepository: ChatSearchRepository
    val forumTopicsRepository: ForumTopicsRepository
    val chatSettingsRepository: ChatSettingsRepository
    val chatCreationRepository: ChatCreationRepository
    val messageRepository: MessageRepository
    val inlineBotRepository: InlineBotRepository
    val chatEventLogRepository: ChatEventLogRepository
    val messageAiRepository: MessageAiRepository
    val paymentRepository: PaymentRepository
    val fileRepository: FileRepository
    val webAppRepository: WebAppRepository
    val userRepository: UserRepository
    val userProfileEditRepository: UserProfileEditRepository
    val profilePhotoRepository: ProfilePhotoRepository
    val chatInfoRepository: ChatInfoRepository
    val premiumRepository: PremiumRepository
    val botRepository: BotRepository
    val chatStatisticsRepository: ChatStatisticsRepository
    val sponsorRepository: SponsorRepository
    val notificationSettingsRepository: NotificationSettingsRepository
    val sessionRepository: SessionRepository
    val wallpaperRepository: WallpaperRepository
    val storageRepository: StorageRepository
    val networkStatisticsRepository: NetworkStatisticsRepository
    val attachMenuBotRepository: AttachMenuBotRepository
    val locationRepository: LocationRepository
    val privacyRepository: PrivacyRepository
    val linkHandlerRepository: LinkHandlerRepository
    val externalProxyRepository: ExternalProxyRepository
    val stickerRepository: StickerRepository
    val gifRepository: GifRepository
    val emojiRepository: EmojiRepository
    val updateRepository: UpdateRepository
    val pushDebugRepository: PushDebugRepository
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
