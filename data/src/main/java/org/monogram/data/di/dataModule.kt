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
import org.monogram.data.datasource.cache.*
import org.monogram.data.datasource.remote.*
import org.monogram.data.db.MonogramDatabase
import org.monogram.data.db.MonogramMigrations
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.gateway.TelegramGatewayImpl
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.gateway.UpdateDispatcherImpl
import org.monogram.data.infra.*
import org.monogram.data.mapper.*
import org.monogram.data.mapper.message.MessageContentMapper
import org.monogram.data.mapper.message.MessagePersistenceMapper
import org.monogram.data.mapper.message.MessageSenderResolver
import org.monogram.data.repository.*
import org.monogram.data.repository.user.UserRepositoryImpl
import org.monogram.data.stickers.StickerFileManager
import org.monogram.domain.repository.*

val dataModule = module {
    single { CoroutineScope(SupervisorJob() + get<DispatcherProvider>().default) }

    single(createdAtStart = true) { TdLibClient() }

    single<DispatcherProvider> { DefaultDispatcherProvider() }
    single<StringProvider> { AndroidStringProvider(androidContext()) }
    single { TdLibParametersProvider(androidContext()) }
    single(createdAtStart = true) {
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
    single(createdAtStart = true) {
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
                MonogramMigrations.MIGRATION_28_29
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
            updates = get(),
            fileQueue = get()
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
        ChatMapper(get())
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
            updates = get(),
            wallpaperDao = get(),
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
            fileUpdateHandler = get(),
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
            updates = get(),
            scope = get()
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

    single(createdAtStart = true) { TdNotificationManager(androidContext(), get(), get(), get(), get(), get()) }
}
