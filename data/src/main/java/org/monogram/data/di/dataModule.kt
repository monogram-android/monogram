package org.monogram.data.di

import android.content.Context
import android.net.ConnectivityManager
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.monogram.core.DispatcherProvider
import org.monogram.data.BuildConfig
import org.monogram.data.chats.ChatCache
import org.monogram.data.datasource.FileDataSource
import org.monogram.data.datasource.PlayerDataSourceFactoryImpl
import org.monogram.data.datasource.TdFileDataSource
import org.monogram.data.datasource.cache.ChatLocalDataSource
import org.monogram.data.datasource.cache.ChatsCacheDataSource
import org.monogram.data.datasource.cache.InMemorySettingsCacheDataSource
import org.monogram.data.datasource.cache.RoomChatLocalDataSource
import org.monogram.data.datasource.cache.RoomStickerLocalDataSource
import org.monogram.data.datasource.cache.RoomUserLocalDataSource
import org.monogram.data.datasource.cache.SettingsCacheDataSource
import org.monogram.data.datasource.cache.StickerLocalDataSource
import org.monogram.data.datasource.cache.UserCacheDataSource
import org.monogram.data.datasource.cache.UserLocalDataSource
import org.monogram.data.datasource.remote.AuthRemoteDataSource
import org.monogram.data.datasource.remote.ChatRemoteSource
import org.monogram.data.datasource.remote.ChatsRemoteDataSource
import org.monogram.data.datasource.remote.EmojiRemoteSource
import org.monogram.data.datasource.remote.ExternalProxyDataSource
import org.monogram.data.datasource.remote.GifRemoteSource
import org.monogram.data.datasource.remote.HttpExternalProxyDataSource
import org.monogram.data.datasource.remote.LinkRemoteDataSource
import org.monogram.data.datasource.remote.MessageFileApi
import org.monogram.data.datasource.remote.MessageFileCoordinator
import org.monogram.data.datasource.remote.MessageRemoteDataSource
import org.monogram.data.datasource.remote.NominatimRemoteDataSource
import org.monogram.data.datasource.remote.PrivacyRemoteDataSource
import org.monogram.data.datasource.remote.ProxyRemoteDataSource
import org.monogram.data.datasource.remote.SettingsRemoteDataSource
import org.monogram.data.datasource.remote.StickerRemoteSource
import org.monogram.data.datasource.remote.TdAuthRemoteDataSource
import org.monogram.data.datasource.remote.TdChatRemoteSource
import org.monogram.data.datasource.remote.TdChatsRemoteDataSource
import org.monogram.data.datasource.remote.TdEmojiRemoteSource
import org.monogram.data.datasource.remote.TdGifRemoteSource
import org.monogram.data.datasource.remote.TdLinkRemoteDataSource
import org.monogram.data.datasource.remote.TdMessageRemoteDataSource
import org.monogram.data.datasource.remote.TdPrivacyRemoteDataSource
import org.monogram.data.datasource.remote.TdProxyRemoteDataSource
import org.monogram.data.datasource.remote.TdSettingsRemoteDataSource
import org.monogram.data.datasource.remote.TdStickerRemoteSource
import org.monogram.data.datasource.remote.TdUpdateRemoteDataSource
import org.monogram.data.datasource.remote.TdUserRemoteDataSource
import org.monogram.data.datasource.remote.UpdateRemoteDateSource
import org.monogram.data.datasource.remote.UserRemoteDataSource
import org.monogram.data.db.MonogramDatabase
import org.monogram.data.db.MonogramMigrations
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.gateway.TelegramGatewayImpl
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.gateway.UpdateDispatcherImpl
import org.monogram.data.infra.AndroidStringProvider
import org.monogram.data.infra.ConnectionManager
import org.monogram.data.infra.DataMemoryDiagnostics
import org.monogram.data.infra.DataMemoryPressureHandler
import org.monogram.data.infra.DefaultDispatcherProvider
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.infra.FileMessageRegistry
import org.monogram.data.infra.FileObserverHub
import org.monogram.data.infra.FileUpdateHandler
import org.monogram.data.infra.OfflineWarmup
import org.monogram.data.infra.SponsorSyncManager
import org.monogram.data.infra.TdLibParametersProvider
import org.monogram.data.mapper.ChatMapper
import org.monogram.data.mapper.CustomEmojiLoader
import org.monogram.data.mapper.MessageMapper
import org.monogram.data.mapper.NetworkMapper
import org.monogram.data.mapper.StorageMapper
import org.monogram.data.mapper.TdFileHelper
import org.monogram.data.mapper.WebPageMapper
import org.monogram.data.mapper.message.MessageContentMapper
import org.monogram.data.mapper.message.MessagePersistenceMapper
import org.monogram.data.mapper.message.MessageSenderResolver
import org.monogram.data.repository.AttachMenuBotRepositoryImpl
import org.monogram.data.repository.AuthRepositoryImpl
import org.monogram.data.repository.BotRepositoryImpl
import org.monogram.data.repository.ChatInfoRepositoryImpl
import org.monogram.data.repository.ChatStatisticsRepositoryImpl
import org.monogram.data.repository.ChatsListRepositoryImpl
import org.monogram.data.repository.EmojiRepositoryImpl
import org.monogram.data.repository.ExternalProxyRepositoryImpl
import org.monogram.data.repository.GifRepositoryImpl
import org.monogram.data.repository.LinkHandlerRepositoryImpl
import org.monogram.data.repository.LinkParser
import org.monogram.data.repository.LocationRepositoryImpl
import org.monogram.data.repository.MessageRepositoryImpl
import org.monogram.data.repository.NetworkStatisticsRepositoryImpl
import org.monogram.data.repository.NotificationSettingsRepositoryImpl
import org.monogram.data.repository.PollRepositoryImpl
import org.monogram.data.repository.PremiumRepositoryImpl
import org.monogram.data.repository.PrivacyRepositoryImpl
import org.monogram.data.repository.ProfilePhotoRepositoryImpl
import org.monogram.data.repository.SessionRepositoryImpl
import org.monogram.data.repository.SponsorRepositoryImpl
import org.monogram.data.repository.StickerRepositoryImpl
import org.monogram.data.repository.StorageRepositoryImpl
import org.monogram.data.repository.StreamingRepositoryImpl
import org.monogram.data.repository.UpdateRepositoryImpl
import org.monogram.data.repository.UserProfileEditRepositoryImpl
import org.monogram.data.repository.WallpaperRepositoryImpl
import org.monogram.data.repository.user.UserRepositoryImpl
import org.monogram.data.stickers.StickerFileManager
import org.monogram.domain.repository.AttachMenuBotRepository
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.BotRepository
import org.monogram.domain.repository.ChatCreationRepository
import org.monogram.domain.repository.ChatEventLogRepository
import org.monogram.domain.repository.ChatFolderRepository
import org.monogram.domain.repository.ChatInfoRepository
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.ChatOperationsRepository
import org.monogram.domain.repository.ChatSearchRepository
import org.monogram.domain.repository.ChatSettingsRepository
import org.monogram.domain.repository.ChatStatisticsRepository
import org.monogram.domain.repository.EmojiRepository
import org.monogram.domain.repository.ExternalProxyRepository
import org.monogram.domain.repository.FileRepository
import org.monogram.domain.repository.ForumTopicsRepository
import org.monogram.domain.repository.GifRepository
import org.monogram.domain.repository.InlineBotRepository
import org.monogram.domain.repository.LinkHandlerRepository
import org.monogram.domain.repository.LocationRepository
import org.monogram.domain.repository.MessageAiRepository
import org.monogram.domain.repository.MessageRepository
import org.monogram.domain.repository.NetworkStatisticsRepository
import org.monogram.domain.repository.NotificationSettingsRepository
import org.monogram.domain.repository.PaymentRepository
import org.monogram.domain.repository.PlayerDataSourceFactory
import org.monogram.domain.repository.PollRepository
import org.monogram.domain.repository.PremiumRepository
import org.monogram.domain.repository.PrivacyRepository
import org.monogram.domain.repository.ProfilePhotoRepository
import org.monogram.domain.repository.SessionRepository
import org.monogram.domain.repository.SponsorRepository
import org.monogram.domain.repository.StickerRepository
import org.monogram.domain.repository.StorageRepository
import org.monogram.domain.repository.StreamingRepository
import org.monogram.domain.repository.StringProvider
import org.monogram.domain.repository.UpdateRepository
import org.monogram.domain.repository.UserProfileEditRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.domain.repository.WallpaperRepository
import org.monogram.domain.repository.WebAppRepository

val dataModule = module {
    single { CoroutineScope(SupervisorJob() + get<DispatcherProvider>().default) }

    single(createdAtStart = true) { TdLibClient() }

    single<DispatcherProvider> { DefaultDispatcherProvider() }
    single<StringProvider> { AndroidStringProvider(androidContext()) }
    single { TdLibParametersProvider(androidContext()) }
    single {
        OfflineWarmup(
            scope = get(),
            dispatchers = get(),
            gateway = get(),
            chatDao = get(),
            messageDao = get(),
            userDao = get(),
            userFullInfoDao = get(),
            chatFullInfoDao = get(),
            messageMapper = get(),
            chatCache = get(),
            stickerRepository = get()
        )
    }
    single {
        SponsorSyncManager(
            scope = get(),
            gateway = get(),
            sponsorDao = get(),
            authRepository = get()
        )
    }

    single { ChatCache() }
    single<TelegramGateway>(createdAtStart = true) {
        TelegramGatewayImpl(get())
    }
    single<UpdateDispatcher> {
        UpdateDispatcherImpl(
            gateway = get()
        )
    }
    single<FileDataSource> {
        TdFileDataSource(
            gateway = get(),
            fileDownloadQueue = get()
        )
    }

    factory<AuthRemoteDataSource> {
        TdAuthRemoteDataSource(
            gateway = get()
        )
    }

    single {
        NominatimRemoteDataSource()
    }

    factory<PlayerDataSourceFactory> {
        PlayerDataSourceFactoryImpl(
            fileDataSource = get()
        )
    }

    single<AuthRepository>(createdAtStart = true) {
        AuthRepositoryImpl(
            parametersProvider = get(),
            remote = get(),
            updates = get(),
            scope = get()
        )
    }

    factory<UserRemoteDataSource> {
        TdUserRemoteDataSource(
            gateway = get()
        )
    }

    factory<LinkRemoteDataSource> {
        TdLinkRemoteDataSource(
            gateway = get()
        )
    }

    // Database
    single {
        Room.databaseBuilder(
            androidContext(),
            MonogramDatabase::class.java,
            "monogram_db"
        )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                MonogramMigrations.MIGRATION_26_27,
                MonogramMigrations.MIGRATION_27_28,
                MonogramMigrations.MIGRATION_28_29,
                MonogramMigrations.MIGRATION_29_30,
                MonogramMigrations.MIGRATION_30_31
            )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
    single { get<MonogramDatabase>().chatDao() }
    single { get<MonogramDatabase>().messageDao() }
    single { get<MonogramDatabase>().userDao() }
    single { get<MonogramDatabase>().chatFullInfoDao() }
    single { get<MonogramDatabase>().topicDao() }
    single { get<MonogramDatabase>().userFullInfoDao() }
    single { get<MonogramDatabase>().stickerSetDao() }
    single { get<MonogramDatabase>().recentEmojiDao() }
    single { get<MonogramDatabase>().searchHistoryDao() }
    single { get<MonogramDatabase>().chatFolderDao() }
    single { get<MonogramDatabase>().attachBotDao() }
    single { get<MonogramDatabase>().keyValueDao() }
    single { get<MonogramDatabase>().notificationSettingDao() }
    single { get<MonogramDatabase>().notificationExceptionDao() }
    single { get<MonogramDatabase>().wallpaperDao() }
    single { get<MonogramDatabase>().stickerPathDao() }
    single { get<MonogramDatabase>().sponsorDao() }
    single { get<MonogramDatabase>().textCompositionStyleDao() }

    single<UserLocalDataSource> {
        RoomUserLocalDataSource(
            userDao = get(),
            userFullInfoDao = get()
        )
    }

    single<ChatLocalDataSource> {
        RoomChatLocalDataSource(
            database = get(),
            chatDao = get(),
            messageDao = get(),
            chatFullInfoDao = get(),
            topicDao = get()
        )
    }

    single<StickerLocalDataSource> {
        RoomStickerLocalDataSource(
            stickerSetDao = get(),
            recentEmojiDao = get(),
            stickerPathDao = get()
        )
    }

    single<UserRepository> {
        UserRepositoryImpl(
            remote = get(),
            userLocal = get(),
            chatLocal = get(),
            chatCache = get(),
            updates = get(),
            scope = get(),
            gateway = get(),
            fileQueue = get(),
            fileObserverHub = get(),
            keyValueDao = get(),
            cacheProvider = get()
        )
    }

    single<UserProfileEditRepository> {
        UserProfileEditRepositoryImpl(
            remote = get()
        )
    }

    single<ProfilePhotoRepository> {
        ProfilePhotoRepositoryImpl(
            remote = get(),
            chatLocal = get(),
            gateway = get(),
            fileQueue = get(),
            fileObserverHub = get()
        )
    }

    single<ChatInfoRepository> {
        ChatInfoRepositoryImpl(
            remote = get(),
            chatLocal = get(),
            userRepository = get()
        )
    }

    single<PremiumRepository> {
        PremiumRepositoryImpl(
            remote = get()
        )
    }

    single<BotRepository> {
        BotRepositoryImpl(
            remote = get()
        )
    }

    single<ChatStatisticsRepository> {
        ChatStatisticsRepositoryImpl(
            remote = get()
        )
    }

    single<SponsorRepository> {
        SponsorRepositoryImpl(
            sponsorSyncManager = get()
        )
    }

    factory<ChatsRemoteDataSource> {
        TdChatsRemoteDataSource(
            gateway = get()
        )
    }

    single<ChatsCacheDataSource> {
        get<ChatCache>()
    }

    single<ChatRemoteSource> {
        TdChatRemoteSource(
            gateway = get(),
            connectivityManager = androidContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager,
        )
    }

    factory<ProxyRemoteDataSource> {
        TdProxyRemoteDataSource(
            gateway = get()
        )
    }

    single {
        ChatMapper(get(), get())
    }

    single {
        StorageMapper(get())
    }

    single {
        NetworkMapper(get(), get())
    }

    single<MessageFileApi> {
        MessageFileCoordinator(
            fileDownloadQueue = get()
        )
    }

    single<UserCacheDataSource> {
        get<ChatCache>()
    }

    single {
        TdFileHelper(
            connectivityManager = androidContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager,
            fileApi = get(),
            appPreferences = get(),
            cache = get()
        )
    }

    single {
        CustomEmojiLoader(
            gateway = get(),
            fileApi = get(),
            fileUpdateHandler = get(),
            fileHelper = get()
        )
    }

    single {
        WebPageMapper(
            fileHelper = get(),
            appPreferences = get()
        )
    }

    single {
        MessageContentMapper(
            fileHelper = get(),
            appPreferences = get(),
            customEmojiLoader = get(),
            webPageMapper = get(),
            scope = get()
        )
    }

    single {
        MessageSenderResolver(
            gateway = get(),
            userRepository = get(),
            chatInfoRepository = get(),
            cache = get(),
            fileHelper = get()
        )
    }

    single {
        MessagePersistenceMapper(
            cache = get(),
            fileHelper = get()
        )
    }

    single {
        MessageMapper(
            gateway = get(),
            userRepository = get(),
            cache = get(),
            fileHelper = get(),
            senderResolver = get(),
            contentMapper = get(),
            persistenceMapper = get(),
            customEmojiLoader = get()
        )
    }

    single {
        ConnectionManager(
            chatRemoteSource = get(),
            proxyRemoteSource = get(),
            updates = get(),
            appPreferences = get(),
            dispatchers = get(),
            connectivityManager = androidContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager,
            scope = get()
        )
    }

    single {
        ChatsListRepositoryImpl(
            remoteDataSource = get(),
            chatRemoteSource = get(),
            updates = get(),
            appPreferences = get(),
            cacheProvider = get(),
            dispatchers = get(),
            cache = get(),
            chatMapper = get(),
            messageMapper = get(),
            gateway = get(),
            scope = get(),
            chatLocalDataSource = get(),
            connectionManager = get(),
            databaseFile = androidContext().getDatabasePath("monogram_db"),
            searchHistoryDao = get(),
            chatFolderDao = get(),
            userFullInfoDao = get(),
            fileQueue = get(),
            fileUpdateHandler = get(),
            stringProvider = get()
        )
    }
    single<ChatListRepository> { get<ChatsListRepositoryImpl>() }
    single<ChatFolderRepository> { get<ChatsListRepositoryImpl>() }
    single<ChatOperationsRepository> { get<ChatsListRepositoryImpl>() }
    single<ChatSearchRepository> { get<ChatsListRepositoryImpl>() }
    single<ForumTopicsRepository> { get<ChatsListRepositoryImpl>() }
    single<ChatSettingsRepository> { get<ChatsListRepositoryImpl>() }
    single<ChatCreationRepository> { get<ChatsListRepositoryImpl>() }

    factory<SettingsRemoteDataSource> {
        TdSettingsRemoteDataSource(
            gateway = get(),
            fileQueue = get()
        )
    }

    single<SettingsCacheDataSource> {
        InMemorySettingsCacheDataSource()
    }

    single<NotificationSettingsRepository> {
        NotificationSettingsRepositoryImpl(
            remote = get(),
            cache = get(),
            chatsRemote = get(),
            notificationExceptionDao = get(),
            updates = get(),
            scope = get(),
            dispatchers = get()
        )
    }

    single<SessionRepository> {
        SessionRepositoryImpl(
            remote = get()
        )
    }

    single<WallpaperRepository> {
        WallpaperRepositoryImpl(
            remote = get(),
            wallpaperDao = get(),
            fileObserverHub = get(),
            dispatchers = get(),
            scope = get()
        )
    }

    single<StorageRepository> {
        StorageRepositoryImpl(
            remote = get(),
            cache = get(),
            chatsRemote = get(),
            dispatchers = get(),
            storageMapper = get(),
            stringProvider = get()
        )
    }

    single<NetworkStatisticsRepository> {
        NetworkStatisticsRepositoryImpl(
            remote = get(),
            networkMapper = get()
        )
    }

    single<AttachMenuBotRepository> {
        AttachMenuBotRepositoryImpl(
            remote = get(),
            cache = get(),
            cacheProvider = get(),
            updates = get(),
            fileObserverHub = get(),
            dispatchers = get(),
            attachBotDao = get(),
            scope = get()
        )
    }

    single<PollRepository> {
        PollRepositoryImpl()
    }

    single<MessageRemoteDataSource> {
        TdMessageRemoteDataSource(
            gateway = get(),
            messageMapper = get(),
            userRepository = get(),
            chatListRepository = get(),
            cache = get(),
            pollRepository = get(),
            fileDownloadQueue = get(),
            fileUpdateHandler = get(),
            dispatcherProvider = get(),
            scope = get()
        )
    }

    single<MessageRepository> {
        MessageRepositoryImpl(
            context = androidContext(),
            gateway = get(),
            updates = get(),
            messageMapper = get(),
            messageRemoteDataSource = get(),
            cache = get(),
            fileHelper = get(),
            dispatcherProvider = get(),
            scope = get(),
            fileDataSource = get(),
            chatLocalDataSource = get(),
            userLocalDataSource = get(),
            stickerPathDao = get(),
            keyValueDao = get(),
            textCompositionStyleDao = get()
        )
    }

    single<InlineBotRepository> { get<MessageRepository>() }
    single<ChatEventLogRepository> { get<MessageRepository>() }
    single<MessageAiRepository> { get<MessageRepository>() }
    single<PaymentRepository> { get<MessageRepository>() }
    single<FileRepository> { get<MessageRepository>() }
    single<WebAppRepository> { get<MessageRepository>() }

    factory<StickerRemoteSource> {
        TdStickerRemoteSource(
            gateway = get()
        )
    }

    factory<GifRemoteSource> {
        TdGifRemoteSource(
            gateway = get()
        )
    }

    factory<EmojiRemoteSource> {
        TdEmojiRemoteSource(
            gateway = get()
        )
    }

    single {
        FileMessageRegistry()
    }

    single {
        FileDownloadQueue(
            gateway = get(),
            registry = get(),
            cache = get(),
            scope = get(),
            dispatcherProvider = get()
        )
    }

    single {
        FileUpdateHandler(
            registry = get(),
            queue = get(),
            updates = get(),
            scope = get()
        )
    }

    single {
        FileObserverHub(
            queue = get(),
            fileUpdateHandler = get()
        )
    }

    single {
        DataMemoryPressureHandler(
            chatsListRepository = get(),
            fileUpdateHandler = get()
        )
    }

    if (BuildConfig.DEBUG) {
        single(createdAtStart = true) {
            DataMemoryDiagnostics(
                scope = get(),
                memoryPressureHandler = get()
            )
        }
    }

    single {
        StickerFileManager(
            localDataSource = get(),
            fileQueue = get(),
            fileUpdateHandler = get(),
            dispatchers = get(),
            scope = get()
        )
    }

    single<StickerRepository> {
        StickerRepositoryImpl(
            remote = get(),
            fileManager = get(),
            updates = get(),
            cacheProvider = get(),
            dispatchers = get(),
            localDataSource = get(),
            scope = get()
        )
    }

    single<GifRepository> {
        GifRepositoryImpl(
            remote = get(),
            cacheProvider = get(),
            stickerFileManager = get()
        )
    }

    single<EmojiRepository> {
        EmojiRepositoryImpl(
            remote = get(),
            localDataSource = get(),
            cacheProvider = get(),
            dispatchers = get(),
            context = androidContext(),
            scope = get()
        )
    }

    factory<PrivacyRemoteDataSource> {
        TdPrivacyRemoteDataSource(
            gateway = get()
        )
    }

    single<PrivacyRepository> {
        PrivacyRepositoryImpl(
            remote = get(),
            updates = get()
        )
    }

    single {
        LinkParser()
    }

    single<LinkHandlerRepository> {
        LinkHandlerRepositoryImpl(get(), get(), get(), get(), get())
    }

    single<StreamingRepository> {
        StreamingRepositoryImpl(
            fileDataSource = get(),
            fileObserverHub = get()
        )
    }

    factory<ExternalProxyDataSource> {
        HttpExternalProxyDataSource(
            dispatchers = get()
        )
    }

    single<ExternalProxyRepository> {
        ExternalProxyRepositoryImpl(
            remote = get(),
            externalSource = get(),
            dispatchers = get(),
            appPreferences = get()
        )
    }

    single<LocationRepository> {
        LocationRepositoryImpl(
            remote = get()
        )
    }

    factory<UpdateRemoteDateSource> {
        TdUpdateRemoteDataSource(
            gateway = get()
        )
    }

    single<UpdateRepository> {
        UpdateRepositoryImpl(
            context = androidContext(),
            remote = get(),
            fileQueue = get(),
            fileUpdateHandler = get(),
            authRepository = get(),
            scope = get(),
        )
    }

    single(createdAtStart = true) { TdNotificationManager(androidContext(), get(), get(), get(), get(), get(), get()) }
}
