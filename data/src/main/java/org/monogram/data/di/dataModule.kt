package org.monogram.data.di

import android.content.Context
import android.net.ConnectivityManager
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import org.monogram.data.chats.ChatCache
import org.monogram.data.datasource.FileDataSource
import org.monogram.data.datasource.PlayerDataSourceFactoryImpl
import org.monogram.data.datasource.TdFileDataSource
import org.monogram.data.datasource.cache.*
import org.monogram.data.datasource.remote.*
import org.monogram.data.db.MonogramDatabase
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.gateway.TelegramGatewayImpl
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.gateway.UpdateDispatcherImpl
import org.monogram.data.infra.*
import org.monogram.data.mapper.ChatMapper
import org.monogram.data.mapper.MessageMapper
import org.monogram.data.mapper.NetworkMapper
import org.monogram.data.mapper.StorageMapper
import org.monogram.data.repository.*
import org.monogram.domain.repository.*

val dataModule = module {
    single { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    single { TdLibClient(androidContext()) }

    single<DispatcherProvider> { DefaultDispatcherProvider() }
    single<ScopeProvider> { DefaultScopeProvider(get()) }
    single<StringProvider> { AndroidStringProvider(androidContext()) }

    single { ChatCache() }
    single<TelegramGateway> {
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

    factory<PlayerDataSourceFactory> {
        PlayerDataSourceFactoryImpl(
            fileDataSource = get()
        )
    }

    single<AuthRepository> {
        AuthRepositoryImpl(
            remote = get(),
            updates = get(),
            tdLibClient = get(),
            scopeProvider = get()
        )
    }

    factory<UserRemoteDataSource> {
        TdUserRemoteDataSource(
            gateway = get()
        )
    }

    // Database
    single {
        Room.databaseBuilder(
            androidContext(),
            MonogramDatabase::class.java,
            "monogram_db"
        ).addMigrations(
            MonogramDatabase.MIGRATION_14_15,
            MonogramDatabase.MIGRATION_15_16,
            MonogramDatabase.MIGRATION_16_17
        ).build()
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
    single { get<MonogramDatabase>().wallpaperDao() }
    single { get<MonogramDatabase>().stickerPathDao() }

    single<UserLocalDataSource> {
        RoomUserLocalDataSource(
            userDao = get(),
            userFullInfoDao = get()
        )
    }

    single<ChatLocalDataSource> {
        RoomChatLocalDataSource(
            chatDao = get(),
            messageDao = get(),
            chatFullInfoDao = get(),
            topicDao = get()
        )
    }

    single<UserRepository> {
        UserRepositoryImpl(
            remote = get(),
            userLocal = get(),
            chatLocal = get(),
            updates = get(),
            scopeProvider = get(),
            gateway = get(),
            fileQueue = get()
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
        MessageMapper(
            connectivityManager = androidContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager,
            gateway = get(),
            userRepository = get(),
            customEmojiPaths = get<FileUpdateHandler>().customEmojiPaths,
            fileIdToCustomEmojiId = get<FileUpdateHandler>().fileIdToCustomEmojiId,
            fileApi = get(),
            settingsRepository = get(),
            cache = get(),
            scopeProvider = get()
        )
    }

    single {
        ConnectionManager(
            chatRemoteSource = get(),
            proxyRemoteSource = get(),
            updates = get(),
            appPreferences = get(),
            dispatchers = get(),
            scopeProvider = get()
        )
    }

    single<ChatsListRepository> {
        ChatsListRepositoryImpl(
            remoteDataSource = get(),
            cacheDataSource = get(),
            chatRemoteSource = get(),
            proxyRemoteSource = get(),
            updates = get(),
            appPreferences = get(),
            cacheProvider = get(),
            dispatchers = get(),
            cache = get(),
            chatMapper = get(),
            messageMapper = get(),
            gateway = get(),
            scopeProvider = get(),
            chatLocalDataSource = get(),
            connectionManager = get(),
            databaseFile = androidContext().getDatabasePath("monogram_db"),
            searchHistoryDao = get(),
            chatFolderDao = get(),
            fileQueue = get(),
            stringProvider = get()
        )
    }

    factory<SettingsRemoteDataSource> {
        TdSettingsRemoteDataSource(
            gateway = get(),
            fileQueue = get()
        )
    }

    single<SettingsCacheDataSource> {
        InMemorySettingsCacheDataSource()
    }

    single<SettingsRepository> {
        SettingsRepositoryImpl(
            remote = get(),
            cache = get(),
            chatsRemote = get(),
            updates = get(),
            appPreferences = get(),
            cacheProvider = get(),
            scopeProvider = get(),
            dispatchers = get(),
            attachBotDao = get(),
            keyValueDao = get(),
            wallpaperDao = get(),
            storageMapper = get(),
            stringProvider = get(),
            networkMapper = get()
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
            chatsListRepository = get(),
            cache = get(),
            pollRepository = get(),
            fileDownloadQueue = get(),
            dispatcherProvider = get(),
            scopeProvider = get()
        )
    }

    single<MessageRepository> {
        MessageRepositoryImpl(
            context = androidContext(),
            gateway = get(),
            messageMapper = get(),
            messageRemoteDataSource = get(),
            cache = get(),
            dispatcherProvider = get(),
            scopeProvider = get(),
            fileDataSource = get(),
            chatLocalDataSource = get()
        )
    }

    factory<StickerRemoteSource> {
        TdStickerRemoteSource(
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

    single<StickerRepository> {
        StickerRepositoryImpl(
            remote = get(),
            fileQueue = get(),
            fileUpdateHandler = get(),
            updates = get(),
            cacheProvider = get(),
            dispatchers = get(),
            context = androidContext(),
            scopeProvider = get(),
            stickerSetDao = get(),
            recentEmojiDao = get(),
            stickerPathDao = get()
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
            updates = get(),
            scopeProvider = get()
        )
    }

    single<LinkHandlerRepository> {
        LinkHandlerRepositoryImpl(get(), get(), get(), get())
    }

    single<StreamingRepository> {
        StreamingRepositoryImpl(
            fileDataSource = get(),
            updates = get(),
            scopeProvider = get()
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
        LocationRepositoryImpl()
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
            scopeProvider = get(),
        )
    }

    single { TdNotificationManager(androidContext(), get(), get(), get(), get(), get()) }
}
