package org.monogram.data.di

import android.content.Context
import android.net.ConnectivityManager
import androidx.room.Room
import androidx.room.RoomDatabase
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.monogram.core.DispatcherProvider
import org.monogram.data.BuildConfig
import org.monogram.data.backend.KeyValueTelegramBackendSelectionStore
import org.monogram.data.backend.LegacyActiveAccountBinding
import org.monogram.data.backend.LegacyBackendAccessGuard
import org.monogram.data.backend.LegacyMessageHistorySnapshotRepository
import org.monogram.data.backend.LegacyUserProfileSnapshotRepository
import org.monogram.data.backend.LegacyDialogSnapshotRepository
import org.monogram.data.backend.TelegramBackendAuthRouter
import org.monogram.data.backend.TelegramBackendChatReadRouter
import org.monogram.data.backend.TelegramBackendChatSearchRouter
import org.monogram.data.backend.TelegramBackendChatInfoRouter
import org.monogram.data.backend.TelegramBackendContactEditRouter
import org.monogram.data.backend.TelegramBackendMessageRouter
import org.monogram.data.backend.TelegramBackendNotificationSettingsRouter
import org.monogram.data.backend.TelegramBackendModeRepositoryImpl
import org.monogram.data.backend.TelegramBackendLimitsRouter
import org.monogram.data.backend.TelegramBackendSessionRouter
import org.monogram.data.backend.LegacyChatReadContracts
import org.monogram.data.backend.TelegramBackendReadRouter
import org.monogram.data.backend.TelegramBackendSelectionStore
import org.monogram.data.backend.TelegramBackendSwitchRepositoryImpl
import org.monogram.data.backend.TelegramBackendSwitchService
import org.monogram.data.chats.ChatCache
import org.monogram.data.datasource.FileDataSource
import org.monogram.data.datasource.PlayerDataSourceFactoryImpl
import org.monogram.data.datasource.TdFileDataSource
import org.monogram.data.datasource.cache.ChatLocalDataSource
import org.monogram.data.datasource.cache.ChatsCacheDataSource
import org.monogram.data.datasource.cache.InMemorySettingsCacheDataSource
import org.monogram.data.datasource.cache.MessageCacheWriter
import org.monogram.data.datasource.cache.invalidateFailedMessageCacheCoverage
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
import org.monogram.data.datasource.remote.FxEmbedRemoteDataSource
import org.monogram.data.datasource.remote.GifRemoteSource
import org.monogram.data.datasource.remote.GitHubRemoteDataSource
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
import org.monogram.data.datasource.remote.createMonogramHttpClient
import org.monogram.data.db.MonogramDatabase
import org.monogram.data.db.MonogramMigrations
import org.monogram.data.mtproto.MtProtoRoomUpdateStateStore
import org.monogram.data.mtproto.MtProtoRoomUpdateRecovery
import org.monogram.data.mtproto.MtProtoRoomLiveUpdateApplier
import org.monogram.data.mtproto.MtProtoLiveSessionResetter
import org.monogram.data.mtproto.MtProtoLiveUpdateCoordinator
import org.monogram.data.mtproto.MtProtoSessionTransportFactory
import org.monogram.data.mtproto.MtProtoPendingEnvelopeStore
import org.monogram.data.mtproto.MtProtoRoomPendingEnvelopeStore
import org.monogram.data.mtproto.MtProtoRoomCloudObjectStager
import org.monogram.data.mtproto.MtProtoCloudObjectStager
import org.monogram.data.mtproto.MtProtoRoomUserProjectionStore
import org.monogram.data.mtproto.MtProtoUserProjectionStore
import org.monogram.data.mtproto.MtProtoRoomChatProjectionStore
import org.monogram.data.mtproto.MtProtoChatProjectionStore
import org.monogram.data.mtproto.MtProtoRoomMessageProjectionStore
import org.monogram.data.mtproto.MtProtoMessageProjectionStore
import org.monogram.data.mtproto.MtProtoRoomDialogStore
import org.monogram.data.mtproto.MtProtoDialogStore
import org.monogram.data.mtproto.MtProtoDraftStore
import org.monogram.data.mtproto.MtProtoRoomDraftStore
import org.monogram.data.mtproto.MtProtoDialogResultStager
import org.monogram.data.mtproto.MtProtoHistoryResultStager
import org.monogram.data.mtproto.MtProtoDialogSnapshotRepository
import org.monogram.data.mtproto.MtProtoTextMessageRepositoryImpl
import org.monogram.data.mtproto.MtProtoDraftRepository
import org.monogram.data.mtproto.MtProtoDeleteMessageRepository
import org.monogram.data.mtproto.MtProtoDeleteMessageRepositoryImpl
import org.monogram.data.mtproto.MtProtoDeletePrivateDialogRepository
import org.monogram.data.mtproto.MtProtoDeletePrivateDialogRepositoryImpl
import org.monogram.data.mtproto.MtProtoDraftRepositoryImpl
import org.monogram.data.mtproto.MtProtoPinnedMessageRepository
import org.monogram.data.mtproto.MtProtoPinnedMessageRepositoryImpl
import org.monogram.data.mtproto.MtProtoReadHistoryRepositoryImpl
import org.monogram.data.mtproto.MtProtoReportPeerRepository
import org.monogram.data.mtproto.MtProtoReportPeerRepositoryImpl
import org.monogram.data.mtproto.MtProtoMessageDeletionRepositoryImpl
import org.monogram.data.mtproto.MtProtoDialogChatListRepository
import org.monogram.data.mtproto.MtProtoArchiveRepository
import org.monogram.data.mtproto.MtProtoArchiveRepositoryImpl
import org.monogram.data.mtproto.MtProtoDialogPinRepository
import org.monogram.data.mtproto.MtProtoDialogPinRepositoryImpl
import org.monogram.data.mtproto.MtProtoDialogUnreadRepository
import org.monogram.data.mtproto.MtProtoDialogUnreadRepositoryImpl
import org.monogram.data.mtproto.MtProtoMuteRepository
import org.monogram.data.mtproto.MtProtoMuteRepositoryImpl
import org.monogram.data.mtproto.MtProtoLeaveChatRepository
import org.monogram.data.mtproto.MtProtoLeaveChatRepositoryImpl
import org.monogram.data.mtproto.MtProtoClearHistoryRepository
import org.monogram.data.mtproto.MtProtoClearHistoryRepositoryImpl
import org.monogram.data.mtproto.MtProtoChatSearchRepository
import org.monogram.data.mtproto.MtProtoChatInfoRepository
import org.monogram.data.mtproto.MtProtoMessageHistorySnapshotRepository
import org.monogram.data.mtproto.MtProtoNotificationSettingsRepository
import org.monogram.data.mtproto.MtProtoSessionRepository
import org.monogram.data.mtproto.MtProtoUserProfileSnapshotRepository
import org.monogram.data.mtproto.MtProtoUserProfileReader
import org.monogram.data.mtproto.MtProtoRecoveryStateStore
import org.monogram.data.mtproto.MtProtoTransactionalUpdateStateStore
import org.monogram.data.mtproto.MtProtoUpdateCursorStore
import org.monogram.data.mtproto.MtProtoAccountStateCleaner
import org.monogram.data.mtproto.MtProtoAccountStateResetter
import org.monogram.data.mtproto.MtProtoAuthRepository
import org.monogram.data.mtproto.MtProtoAuthSessionResetter
import org.monogram.data.mtproto.MtProtoAuthSessionHandleFactory
import org.monogram.data.mtproto.MtProtoPhoneAuthSessionFactory
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.gateway.TelegramGatewayImpl
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.gateway.UpdateDispatcherImpl
import org.monogram.data.infra.AndroidStringProvider
import org.monogram.data.infra.ConnectionManager
import org.monogram.data.infra.ConnectivityNetworkSnapshotProvider
import org.monogram.data.infra.DataMemoryDiagnostics
import org.monogram.data.infra.DataMemoryPressureHandler
import org.monogram.data.infra.DefaultDispatcherProvider
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.infra.FileMessageRegistry
import org.monogram.data.infra.FileObserverHub
import org.monogram.data.infra.FileUpdateHandler
import org.monogram.data.infra.FileUpdateQueue
import org.monogram.data.infra.NetworkSnapshotProvider
import org.monogram.data.infra.OfflineWarmup
import org.monogram.data.infra.SponsorSyncManager
import org.monogram.data.infra.TelegramClientMetadataProvider
import org.monogram.data.infra.TdLibParametersProvider
import org.monogram.data.mapper.ChatMapper
import org.monogram.data.mapper.CustomEmojiLoader
import org.monogram.data.mapper.MappedMediaDemandCoordinator
import org.monogram.data.mapper.MessageMapper
import org.monogram.data.mapper.NetworkMapper
import org.monogram.data.mapper.SponsoredMessageMapper
import org.monogram.data.mapper.StorageMapper
import org.monogram.data.mapper.TdFileHelper
import org.monogram.data.mapper.WebPageMapper
import org.monogram.data.mapper.message.MessageContentMapper
import org.monogram.data.mapper.message.MessagePersistenceMapper
import org.monogram.data.mapper.message.MessageSenderResolver
import org.monogram.data.mtproto.AndroidMtProtoAuthKeyStore
import org.monogram.data.mtproto.MtProtoAuthKeyPersistence
import org.monogram.data.mtproto.MtProtoAuthKeyEstablisher
import org.monogram.data.mtproto.MtProtoAuthKeySessionBootstrap
import org.monogram.data.mtproto.MtProtoAuthKeyStore
import org.monogram.data.mtproto.MtProtoAccountDcStore
import org.monogram.data.mtproto.KeyValueMtProtoAccountDcStore
import org.monogram.data.mtproto.MtProtoAccountAuthorizationStore
import org.monogram.data.mtproto.KeyValueMtProtoAccountAuthorizationStore
import org.monogram.data.mtproto.MtProtoAuthorizedSessionRestorer
import org.monogram.data.mtproto.TelegramMtProtoAuthorizedSessionRestorer
import org.monogram.data.mtproto.MtProtoAuthKeyLoader
import org.monogram.data.mtproto.TelegramMtProtoBootstrapConfigProvider
import org.monogram.data.mtproto.TelegramMtProtoBootstrapConfigSource
import org.monogram.data.mtproto.TelegramMtProtoSessionFactory
import org.monogram.data.notifications.NotificationMuteResolver
import org.monogram.data.push.PushProcessingCoordinator
import org.monogram.data.push.PushSyncRequester
import org.monogram.data.push.PushSyncTrigger
import org.monogram.data.push.UnifiedPushManager
import org.monogram.data.repository.AttachMenuBotRepositoryImpl
import org.monogram.data.repository.AuthRepositoryImpl
import org.monogram.data.repository.BotRepositoryImpl
import org.monogram.data.repository.ChatInfoRepositoryImpl
import org.monogram.data.repository.ChatStatisticsRepositoryImpl
import org.monogram.data.repository.ChatsListRepositoryImpl
import org.monogram.data.repository.ClientOptionsRepositoryImpl
import org.monogram.data.repository.ContactEditRepositoryImpl
import org.monogram.data.repository.DraftLinkPreviewResolver
import org.monogram.data.repository.EmojiRepositoryImpl
import org.monogram.data.repository.GifRepositoryImpl
import org.monogram.data.repository.GitHubCommitRepositoryImpl
import org.monogram.data.repository.LinkHandlerRepositoryImpl
import org.monogram.data.repository.LinkParser
import org.monogram.data.repository.LocationRepositoryImpl
import org.monogram.data.repository.MessageRepositoryImpl
import org.monogram.data.repository.NetworkStatisticsRepositoryImpl
import org.monogram.data.repository.NotificationSettingsRepositoryImpl
import org.monogram.data.repository.PinnedMessageVisibilityRepositoryImpl
import org.monogram.data.repository.PollRepositoryImpl
import org.monogram.data.repository.PremiumRepositoryImpl
import org.monogram.data.repository.PrivacyRepositoryImpl
import org.monogram.data.backend.TelegramBackendPrivacyRouter
import org.monogram.data.mtproto.MtProtoPrivacyRepository
import org.monogram.data.mtproto.MtProtoProfileEditRepository
import org.monogram.data.backend.TelegramBackendProfileEditRouter
import org.monogram.data.backend.TelegramBackendProfilePhotoRouter
import org.monogram.data.backend.TelegramBackendUserRouter
import org.monogram.data.repository.ProfilePhotoRepositoryImpl
import org.monogram.data.repository.ProxyDiagnosticsRepositoryImpl
import org.monogram.data.repository.ProxyRepositoryImpl
import org.monogram.data.repository.PushDebugRepositoryImpl
import org.monogram.data.repository.SessionRepositoryImpl
import org.monogram.data.repository.SponsorRepositoryImpl
import org.monogram.data.repository.StickerRepositoryImpl
import org.monogram.data.repository.StorageRepositoryImpl
import org.monogram.data.repository.StoryRepositoryImpl
import org.monogram.data.repository.StreamingRepositoryImpl
import org.monogram.data.repository.TdLibLimitsRepositoryImpl
import org.monogram.data.repository.TelegramLinkRepositoryImpl
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
import org.monogram.domain.repository.ClientOptionsRepository
import org.monogram.domain.repository.ContactEditRepository
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.domain.repository.EmojiRepository
import org.monogram.domain.repository.FileRepository
import org.monogram.domain.repository.ForumTopicsRepository
import org.monogram.domain.repository.GifRepository
import org.monogram.domain.repository.GitHubCommitRepository
import org.monogram.domain.repository.InlineBotRepository
import org.monogram.domain.repository.LinkHandlerRepository
import org.monogram.domain.repository.LocationRepository
import org.monogram.domain.repository.MessageAiRepository
import org.monogram.domain.repository.MessageHistorySnapshotRepository
import org.monogram.domain.repository.MessageRepository
import org.monogram.domain.repository.MtProtoTextMessageRepository
import org.monogram.domain.repository.MtProtoReadHistoryRepository
import org.monogram.domain.repository.MtProtoMessageDeletionRepository
import org.monogram.domain.repository.NetworkStatisticsRepository
import org.monogram.domain.repository.NotificationSettingsRepository
import org.monogram.domain.repository.PaymentRepository
import org.monogram.domain.repository.PinnedMessageVisibilityRepository
import org.monogram.domain.repository.PlayerDataSourceFactory
import org.monogram.domain.repository.PollRepository
import org.monogram.domain.repository.PremiumRepository
import org.monogram.domain.repository.PrivacyRepository
import org.monogram.domain.repository.ProfilePhotoRepository
import org.monogram.domain.repository.ProxyDiagnosticsRepository
import org.monogram.domain.repository.ProxyRepository
import org.monogram.domain.repository.PushDebugRepository
import org.monogram.domain.repository.RichTextParsingRepository
import org.monogram.domain.repository.SessionRepository
import org.monogram.domain.repository.SponsorRepository
import org.monogram.domain.repository.StickerRepository
import org.monogram.domain.repository.StorageRepository
import org.monogram.domain.repository.StoryRepository
import org.monogram.domain.repository.StreamingRepository
import org.monogram.domain.repository.StringProvider
import org.monogram.domain.repository.UserProfileSnapshotRepository
import org.monogram.domain.repository.TdLibLimitsRepository
import org.monogram.domain.repository.TelegramBackendModeRepository
import org.monogram.domain.repository.TelegramBackendSwitchRepository
import org.monogram.domain.repository.TelegramLinkRepository
import org.monogram.domain.repository.UpdateRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.CodeSettings_3f851bba91
import org.monogram.domain.repository.UserProfileEditRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.domain.repository.WallpaperRepository
import org.monogram.domain.repository.WebAppRepository
import org.monogram.mtproto.handshake.MtProtoAuthHandshake

val dataModule = module {
    includes(fcmRuntimeOverrideModule)

    single { CoroutineScope(SupervisorJob() + get<DispatcherProvider>().default) }

    single { TdLibClient() }

    single<DispatcherProvider> { DefaultDispatcherProvider() }
    single { TelegramClientMetadataProvider(androidContext()) }
    single<StringProvider> { AndroidStringProvider(androidContext()) }
    single<MtProtoAuthKeyStore> { AndroidMtProtoAuthKeyStore(androidContext()) }
    single { MtProtoAuthKeyPersistence(get()) }
    single<MtProtoAccountDcStore> { KeyValueMtProtoAccountDcStore(get()) }
    single<MtProtoAuthKeyEstablisher> {
        MtProtoAuthKeyEstablisher { transport, config -> MtProtoAuthHandshake().execute(transport, config) }
    }
    single { MtProtoAuthKeySessionBootstrap(get(), get()) }
    single<MtProtoAuthKeyLoader> {
        MtProtoAuthKeyLoader { scope, transport, config ->
            get<MtProtoAuthKeySessionBootstrap>().loadOrEstablish(scope, transport, config)
        }
    }
    single<TelegramMtProtoBootstrapConfigSource> {
        TelegramMtProtoBootstrapConfigProvider(
            metadataProvider = get(),
            accountDcStore = get(),
        )
    }
    single {
        TelegramMtProtoSessionFactory(
            configSource = get(),
            keyLoader = get(),
            authKeyPersistence = get(),
            userProjectionStore = get(),
            chatProjectionStore = get(),
            messageProjectionStore = get(),
        )
    }
    single {
        MtProtoPhoneAuthSessionFactory(
            openTransport = { accountSlot ->
                get<TelegramMtProtoSessionFactory>().open(accountSlot)
            },
            openTransportForDc = { accountSlot, dcId ->
                get<TelegramMtProtoSessionFactory>().open(accountSlot, dcId)
            },
            apiId = BuildConfig.API_ID,
            apiHash = BuildConfig.API_HASH,
            codeSettings = CodeSettings_3f851bba91(
                allowFlashcall = false,
                currentNumber = false,
                allowAppHash = true,
                allowMissedCall = false,
                allowFirebase = false,
                unknownNumber = false,
                logoutTokens = null,
                token = null,
                appSandbox = null,
            ),
        )
    }
    single<MtProtoAuthSessionHandleFactory> { get<MtProtoPhoneAuthSessionFactory>() }
    single<MtProtoAccountAuthorizationStore> { KeyValueMtProtoAccountAuthorizationStore(get()) }
    single<MtProtoAuthorizedSessionRestorer> {
        TelegramMtProtoAuthorizedSessionRestorer(get(), get(), get(), get())
    }
    single<MtProtoSessionTransportFactory> {
        MtProtoSessionTransportFactory { accountSlot ->
            get<TelegramMtProtoSessionFactory>().open(accountSlot)
        }
    }
    single { MtProtoAuthRepository(get(), get(), get(), get(), get()) }
    single<MtProtoAuthSessionResetter> { get<MtProtoAuthRepository>() }
    single { TdLibParametersProvider(androidContext(), get()) }
    single {
        OfflineWarmup(
            scope = get(),
            dispatchers = get(),
            gateway = get(),
            chatDao = get(),
            messageDao = get(),
            keyValueDao = get(),
            userDao = get(),
            userFullInfoDao = get(),
            chatFullInfoDao = get(),
            messageMapper = get(),
            chatCache = get(),
            stickerRepository = get(),
            storyRepository = get()
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
    single<TelegramGateway> {
        TelegramGatewayImpl { get<TdLibClient>() }
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

    single<HttpClient> {
        createMonogramHttpClient()
    }

    single {
        NominatimRemoteDataSource(get())
    }

    single {
        FxEmbedRemoteDataSource(get())
    }

    single {
        GitHubRemoteDataSource(get())
    }

    factory<PlayerDataSourceFactory> {
        PlayerDataSourceFactoryImpl(
            fileDataSource = get()
        )
    }

    single {
        AuthRepositoryImpl(
            parametersProvider = get(),
            remote = get(),
            updates = get(),
            scope = get()
        )
    }
    single<AuthRepository>(createdAtStart = true) {
        TelegramBackendAuthRouter(
            selectionStore = get(),
            legacyFactory = { get<AuthRepositoryImpl>() },
            mtProtoFactory = { get<MtProtoAuthRepository>() },
            scope = get(),
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
                MonogramMigrations.MIGRATION_30_31,
                MonogramMigrations.MIGRATION_31_32,
                MonogramMigrations.MIGRATION_32_33,
                MonogramMigrations.MIGRATION_33_34,
                MonogramMigrations.MIGRATION_34_35,
                MonogramMigrations.MIGRATION_35_36,
                MonogramMigrations.MIGRATION_36_37,
                MonogramMigrations.MIGRATION_37_38,
                MonogramMigrations.MIGRATION_38_39,
                MonogramMigrations.MIGRATION_39_40,
                MonogramMigrations.MIGRATION_40_41,
                MonogramMigrations.MIGRATION_41_42,
                MonogramMigrations.MIGRATION_42_43,
                MonogramMigrations.MIGRATION_43_44,
                MonogramMigrations.MIGRATION_44_45,
                MonogramMigrations.MIGRATION_45_46,
                MonogramMigrations.MIGRATION_46_47,
                MonogramMigrations.MIGRATION_47_48,
            )
            .build()
    }
    single { get<MonogramDatabase>().chatDao() }
    single { get<MonogramDatabase>().messageDao() }
    single { get<MonogramDatabase>().messageWindowDao() }
    single { get<MonogramDatabase>().mtProtoUpdateStateDao() }
    single { get<MonogramDatabase>().mtProtoPendingEnvelopeDao() }
    single { get<MonogramDatabase>().mtProtoCloudObjectDao() }
    single { get<MonogramDatabase>().mtProtoUserProjectionDao() }
    single { get<MonogramDatabase>().mtProtoChatProjectionDao() }
    single { get<MonogramDatabase>().mtProtoDialogProjectionDao() }
    single { get<MonogramDatabase>().mtProtoMessageProjectionDao() }
    single { get<MonogramDatabase>().mtProtoDraftProjectionDao() }
    single { MtProtoRoomUserProjectionStore(get(), cloudObjectDao = get()) }
    single<MtProtoUserProjectionStore> { get<MtProtoRoomUserProjectionStore>() }
    single { MtProtoRoomChatProjectionStore(get(), cloudObjectDao = get()) }
    single<MtProtoChatProjectionStore> { get<MtProtoRoomChatProjectionStore>() }
    single { MtProtoRoomMessageProjectionStore(get(), cloudObjectDao = get(), dialogStore = get(), database = get()) }
    single<MtProtoMessageProjectionStore> { get<MtProtoRoomMessageProjectionStore>() }
    single { MtProtoRoomDialogStore(get(), get(), get(), get()) }
    single<MtProtoDialogStore> { get<MtProtoRoomDialogStore>() }
    single { MtProtoRoomDraftStore(get()) }
    single<MtProtoDraftStore> { get<MtProtoRoomDraftStore>() }
    single { MtProtoDialogResultStager(get(), get(), get(), get(), get()) }
    single { MtProtoHistoryResultStager(get(), get(), get(), get()) }
    single { MtProtoDialogSnapshotRepository(get(), get(), get(), get()) }
    single<MtProtoMessageDeletionRepository> {
        MtProtoMessageDeletionRepositoryImpl(get(), get(), get(), get())
    }
    single<MtProtoReadHistoryRepository> {
        MtProtoReadHistoryRepositoryImpl(
            configSource = get(),
            transportFactory = get(),
            users = get(),
            chats = get(),
        )
    }
    single<MtProtoDraftRepository> {
        MtProtoDraftRepositoryImpl(
            configSource = get(),
            transportFactory = get(),
            users = get(),
            chats = get(),
            drafts = get(),
        )
    }
    single<MtProtoDialogUnreadRepository> {
        MtProtoDialogUnreadRepositoryImpl(get(), get(), get(), get(), get())
    }
    single<MtProtoReportPeerRepository> {
        MtProtoReportPeerRepositoryImpl(
            configSource = get(),
            transportFactory = get(),
            users = get(),
            chats = get(),
        )
    }
    single<MtProtoDeletePrivateDialogRepository> {
        MtProtoDeletePrivateDialogRepositoryImpl(
            dialogs = get<MtProtoDialogSnapshotRepository>(),
            messages = get<MtProtoTextMessageRepository>(),
            dialogStore = get(),
            configSource = get(),
        )
    }
    single<MtProtoDeleteMessageRepository> {
        MtProtoDeleteMessageRepositoryImpl(
            dialogs = get(),
            deletion = get(),
        )
    }
    single<MtProtoPinnedMessageRepository> {
        MtProtoPinnedMessageRepositoryImpl(
            dialogs = get(),
            messages = get(),
        )
    }
    single<MtProtoTextMessageRepository> {
        MtProtoTextMessageRepositoryImpl(
            configSource = get(),
            transportFactory = get(),
            users = get(),
            chats = get(),
            messages = get(),
        )
    }
    single {
        MtProtoMessageHistorySnapshotRepository(
            configSource = get(),
            messageStore = get(),
            sessionFactory = get(),
            userStore = get(),
            chatStore = get(),
            resultStager = get(),
        )
    }
    single { MtProtoUserProfileSnapshotRepository(get(), get(), get()) }
    single<MtProtoUserProfileReader> { get<MtProtoUserProfileSnapshotRepository>() }
    single {
        MtProtoRoomCloudObjectStager(
            get(),
            userProjectionStore = get(),
            chatProjectionStore = get(),
            messageProjectionStore = get(),
            draftStore = get(),
        )
    }
    single<MtProtoCloudObjectStager> { get<MtProtoRoomCloudObjectStager>() }
    single { MtProtoRoomPendingEnvelopeStore(get()) }
    single<MtProtoPendingEnvelopeStore> { get<MtProtoRoomPendingEnvelopeStore>() }
    single { MtProtoRoomUpdateStateStore(get(), get()) }
    single<MtProtoTransactionalUpdateStateStore> { get<MtProtoRoomUpdateStateStore>() }
    single { MtProtoRoomLiveUpdateApplier(get(), get(), get()) }
    single<MtProtoRecoveryStateStore> { get<MtProtoRoomUpdateStateStore>() }
    single { MtProtoRoomUpdateRecovery(get(), get(), get()) }
    single<MtProtoUpdateCursorStore> { get<MtProtoRoomUpdateStateStore>() }
    single {
        MtProtoAccountStateCleaner(
            authKeyPersistence = get(),
            updateCursorStore = get(),
            pendingEnvelopeStore = get(),
            cloudObjectStager = get(),
            userProjectionStore = get(),
            chatProjectionStore = get(),
            messageProjectionStore = get(),
            accountDcStore = get(),
            dialogStore = get(),
            draftStore = get(),
            authorizationStore = get(),
        )
    }
    single<MtProtoAccountStateResetter> { get<MtProtoAccountStateCleaner>() }
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
    single { KeyValueTelegramBackendSelectionStore(get()) }
    single<TelegramBackendSelectionStore> { get<KeyValueTelegramBackendSelectionStore>() }
    single<TelegramBackendModeRepository> { TelegramBackendModeRepositoryImpl(get(), get()) }
    single(createdAtStart = true) {
        MtProtoLiveUpdateCoordinator(
            selectionStore = get(),
            authRepository = get(),
            transportFactory = get(),
            configSource = get(),
            recovery = get(),
            liveUpdateApplier = get(),
            dialogs = get<MtProtoDialogSnapshotRepository>(),
            scope = get(),
        )
    }
    single<MtProtoLiveSessionResetter> { get<MtProtoLiveUpdateCoordinator>() }
    single { LegacyActiveAccountBinding() }
    single { LegacyBackendAccessGuard(get(), get()) }
    single {
        TelegramBackendSwitchService(
            selectionStore = get(),
            legacyActiveAccountBinding = get(),
            mtProtoAccountStateResetter = get<MtProtoAccountStateResetter>(),
            mtProtoAuthSessionResetter = get(),
            mtProtoLiveSessionResetter = get(),
        )
    }
    single<TelegramBackendSwitchRepository> { TelegramBackendSwitchRepositoryImpl(get()) }
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
            topicDao = get(),
            messageWindowDao = get()
        )
    }

    single<StickerLocalDataSource> {
        RoomStickerLocalDataSource(
            stickerSetDao = get(),
            recentEmojiDao = get(),
            stickerPathDao = get()
        )
    }

    single {
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
            cacheProvider = get(),
            legacyActiveAccountBinding = get()
        )
    }
    single<UserRepository> {
        TelegramBackendUserRouter(
            selectionStore = get(),
            legacyFactory = { get<UserRepositoryImpl>() },
            mtProtoProfiles = get<MtProtoUserProfileReader>(),
            scope = get(),
            mtProtoAccountStateResetter = get(),
            mtProtoAuthSessionResetter = get(),
            mtProtoLiveSessionResetter = get(),
        )
    }
    single { LegacyUserProfileSnapshotRepository(get(), get<UserRepository>()) }
    single { LegacyDialogSnapshotRepository(get(), get<ChatListRepository>(), get()) }
    single { LegacyMessageHistorySnapshotRepository(get(), get<MessageRepository>(), get()) }
    single {
        TelegramBackendReadRouter(
            selectionStore = get(),
            legacyDialogs = get<LegacyDialogSnapshotRepository>(),
            mtProtoDialogs = get<MtProtoDialogSnapshotRepository>(),
            legacyMessageHistory = get<LegacyMessageHistorySnapshotRepository>(),
            mtProtoMessageHistory = get<MtProtoMessageHistorySnapshotRepository>(),
            legacyUserProfiles = get<LegacyUserProfileSnapshotRepository>(),
            mtProtoUserProfiles = get<MtProtoUserProfileSnapshotRepository>(),
        )
    }
    single<DialogSnapshotRepository> { get<TelegramBackendReadRouter>() }
    single<MessageHistorySnapshotRepository> { get<TelegramBackendReadRouter>() }
    single<UserProfileSnapshotRepository> { get<TelegramBackendReadRouter>() }

    single { UserProfileEditRepositoryImpl(remote = get()) }
    single { MtProtoProfileEditRepository(configSource = get(), transportFactory = get(), users = get()) }
    single<UserProfileEditRepository> {
        TelegramBackendProfileEditRouter(
            selectionStore = get(),
            legacyFactory = { get<UserProfileEditRepositoryImpl>() },
            mtProtoFactory = { get<MtProtoProfileEditRepository>() },
            scope = get(),
        )
    }

    single<ContactEditRepository> {
        TelegramBackendContactEditRouter(
            selectionStore = get(),
            legacyFactory = {
                ContactEditRepositoryImpl(
                    userRepository = get(),
                    userRemoteDataSource = get(),
                )
            },
            users = get(),
            mtProtoProfiles = get(),
            scope = get(),
        )
    }

    single<ProfilePhotoRepository> {
        TelegramBackendProfilePhotoRouter(
            selectionStore = get(),
            legacyFactory = {
                ProfilePhotoRepositoryImpl(
                    remote = get(),
                    chatLocal = get(),
                    gateway = get(),
                    fileQueue = get(),
                    fileObserverHub = get(),
                )
            },
            scope = get(),
        )
    }

    single<MtProtoChatInfoRepository> {
        MtProtoChatInfoRepository(
            configSource = get(),
            transportFactory = get(),
            chats = get(),
            users = get(),
            search = get<MtProtoChatSearchRepository>(),
        )
    }
    single<ChatInfoRepository> {
        TelegramBackendChatInfoRouter(
            selectionStore = get(),
            legacyFactory = {
                ChatInfoRepositoryImpl(
                    remote = get(),
                    chatLocal = get(),
                    userRepository = get(),
                    scope = get(),
                )
            },
            mtProto = get(),
            scope = get(),
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
            telegramLinkRepository = get()
        )
    }
    single<NetworkSnapshotProvider> {
        ConnectivityNetworkSnapshotProvider(
            connectivityManager = androidContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        )
    }

    factory<ProxyRemoteDataSource> {
        TdProxyRemoteDataSource(
            gateway = get(),
            dispatchers = get()
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
            fileHelper = get()
        )
    }

    single {
        MessageContentMapper(
            fileHelper = get(),
            customEmojiLoader = get(),
            webPageMapper = get(),
            cache = get(),
            stringProvider = get()
        )
    }

    single { MappedMediaDemandCoordinator(fileApi = get()) }

    single {
        MessageSenderResolver(
            gateway = get(),
            userRepository = get(),
            chatInfoRepository = get(),
            cache = get(),
            fileHelper = get(),
            stringProvider = get()
        )
    }

    single {
        MessagePersistenceMapper(
            cache = get(),
            fileHelper = get(),
            stringProvider = get()
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
            mediaDemandCoordinator = get(),
            persistenceMapper = get(),
            customEmojiLoader = get(),
            stringProvider = get()
        )
    }

    single {
        ConnectionManager(
            chatRemoteSource = get(),
            proxyRemoteSource = get(),
            updates = get(),
            appPreferences = get(),
            dispatchers = get(),
            networkSnapshotProvider = get(),
            appForegroundTracker = get(),
            scope = get()
        )
    }

    single { PushSyncTrigger(connectionManager = get(), gateway = get()) }
    single<PushSyncRequester> { get<PushSyncTrigger>() }
    single { PushProcessingCoordinator(androidContext(), get(), get()) }
    single { UnifiedPushManager(androidContext()) }
    single { NotificationMuteResolver() }

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
    single<MtProtoClearHistoryRepository> {
        MtProtoClearHistoryRepositoryImpl(
            messages = get<MtProtoTextMessageRepository>(),
            configSource = get(),
            chats = get(),
        )
    }
    single<MtProtoLeaveChatRepository> {
        MtProtoLeaveChatRepositoryImpl(
            configSource = get(),
            transportFactory = get(),
            chats = get(),
            cloudObjectStager = get(),
            dialogs = get<MtProtoDialogSnapshotRepository>(),
        )
    }
    single<MtProtoMuteRepository> {
        MtProtoMuteRepositoryImpl(
            configSource = get(),
            transportFactory = get(),
            users = get(),
            chats = get(),
            dialogs = get<MtProtoDialogSnapshotRepository>(),
        )
    }
    single<MtProtoDialogPinRepository> {
        MtProtoDialogPinRepositoryImpl(
            configSource = get(),
            transportFactory = get(),
            users = get(),
            chats = get(),
            dialogs = get<MtProtoDialogSnapshotRepository>(),
        )
    }
    single<MtProtoArchiveRepository> {
        MtProtoArchiveRepositoryImpl(
            configSource = get(),
            transportFactory = get(),
            users = get(),
            chats = get(),
            dialogs = get<MtProtoDialogSnapshotRepository>(),
        )
    }
    single {
        MtProtoDialogChatListRepository(
            dialogRepository = get<MtProtoDialogSnapshotRepository>(),
            readHistoryRepository = get<MtProtoReadHistoryRepository>(),
            scope = get(),
            dialogUpdates = get<MtProtoDialogSnapshotRepository>().dialogUpdates,
            archiveRepository = get(),
            dialogPinRepository = get(),
            muteRepository = get(),
            leaveChatRepository = get(),
            clearHistoryRepository = get(),
            deletePrivateDialogRepository = get(),
            reportPeerRepository = get(),
            dialogUnreadRepository = get(),
        )
    }
    single {
        TelegramBackendChatReadRouter(
            selectionStore = get(),
            legacyFactory = {
                LegacyChatReadContracts(
                    chatListRepository = get<ChatsListRepositoryImpl>(),
                    chatFolderRepository = get<ChatsListRepositoryImpl>(),
                    chatOperationsRepository = get<ChatsListRepositoryImpl>(),
                )
            },
            mtProtoFactory = { get<MtProtoDialogChatListRepository>() },
            scope = get(),
        )
    }
    single<ChatListRepository> { get<TelegramBackendChatReadRouter>() }
    single<ChatFolderRepository> { get<TelegramBackendChatReadRouter>() }
    single<ChatOperationsRepository> { get<TelegramBackendChatReadRouter>() }
    single {
        MtProtoChatSearchRepository(
            dialogRepository = get<MtProtoDialogSnapshotRepository>(),
            messageStore = get(),
            configSource = get(),
            transportFactory = get(),
            userStore = get(),
            chatStore = get(),
            resultStager = get(),
            searchHistoryDao = get(),
            scope = get(),
        )
    }
    single<ChatSearchRepository> {
        TelegramBackendChatSearchRouter(
            selectionStore = get(),
            legacyFactory = { get<ChatsListRepositoryImpl>() },
            mtProtoFactory = { get<MtProtoChatSearchRepository>() },
            scope = get(),
        )
    }
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

    single<ClientOptionsRepository> {
        ClientOptionsRepositoryImpl(
            remote = get()
        )
    }

    single<TdLibLimitsRepository> {
        TelegramBackendLimitsRouter(
            selectionStore = get(),
            legacyFactory = {
                TdLibLimitsRepositoryImpl(
                    remote = get(),
                    updates = get(),
                    authRepository = get(),
                    scope = get(),
                )
            },
            scope = get(),
        )
    }

    single<MtProtoNotificationSettingsRepository> {
        MtProtoNotificationSettingsRepository(
            configSource = get(),
            transportFactory = get(),
            users = get(),
            chats = get(),
            dialogs = get(),
            chatList = get(),
        )
    }
    single<NotificationSettingsRepository> {
        TelegramBackendNotificationSettingsRouter(
            selectionStore = get(),
            legacyFactory = {
                NotificationSettingsRepositoryImpl(
                    remote = get(),
                    cache = get(),
                    chatsRemote = get(),
                    notificationExceptionDao = get(),
                    updates = get(),
                    scope = get(),
                    dispatchers = get(),
                )
            },
            mtProto = get(),
            scope = get(),
        )
    }

    single<MtProtoSessionRepository> { MtProtoSessionRepository(transportFactory = get()) }
    single<SessionRepository> {
        TelegramBackendSessionRouter(
            selectionStore = get(),
            legacyFactory = { SessionRepositoryImpl(remote = get()) },
            mtProto = get(),
            scope = get(),
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
            stringProvider = get(),
            chatLocalDataSource = get(),
            userLocalDataSource = get(),
            stickerLocalDataSource = get()
        )
    }

    single {
        val localDataSource = get<ChatLocalDataSource>()
        MessageCacheWriter(
            scope = get(),
            applyBatch = localDataSource::applyMessageCacheMutations,
            onTerminalFailure = { mutations, _ ->
                invalidateFailedMessageCacheCoverage(localDataSource, mutations)
            }
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
            webPageMapper = get(),
            draftLinkPreviewResolver = get(),
            dispatcherProvider = get(),
            scope = get(),
            tdLibLimitsRepository = get()
        )
    }

    single {
        MessageRepositoryImpl(
            context = androidContext(),
            gateway = get(),
            updates = get(),
            pollRepository = get(),
            messageMapper = get(),
            messageRemoteDataSource = get(),
            cache = get(),
            fileHelper = get(),
            sponsoredMessageMapper = get(),
            fileDataSource = get(),
            fxEmbedRemoteDataSource = get(),
            draftLinkPreviewResolver = get(),
            dispatcherProvider = get(),
            scope = get(),
            chatLocalDataSource = get(),
            messageCacheWriter = get(),
            userLocalDataSource = get(),
            stickerPathDao = get(),
            keyValueDao = get(),
            textCompositionStyleDao = get(),
            tdLibLimitsRepository = get()
        )
    }
    single<MessageRepository> {
        TelegramBackendMessageRouter(
            selectionStore = get(),
            legacyFactory = { get<MessageRepositoryImpl>() },
            draftFactory = { get<MtProtoDraftRepository>() },
            deleteFactory = { get<MtProtoDeleteMessageRepository>() },
            pinnedFactory = { get<MtProtoPinnedMessageRepository>() },
            historyRepository = get<MtProtoMessageHistorySnapshotRepository>(),
            scope = get(),
        ).repository
    }

    single {
        SponsoredMessageMapper(
            fileHelper = get(),
            contentMapper = get()
        )
    }

    single<PinnedMessageVisibilityRepository> {
        PinnedMessageVisibilityRepositoryImpl(
            keyValueDao = get()
        )
    }

    single<InlineBotRepository> { get<MessageRepository>() }
    single<ChatEventLogRepository> { get<MessageRepository>() }
    single<MessageAiRepository> { get<MessageRepository>() }
    single<RichTextParsingRepository> { get<MessageRepository>() as RichTextParsingRepository }
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

    single<FileUpdateQueue> { get<FileDownloadQueue>() }

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
        single {
            DataMemoryDiagnostics(
                scope = get(),
                memoryPressureHandler = get()
            )
        }
    }

    single {
        StickerFileManager(
            localDataSource = get(),
            fileDataSource = get(),
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

    single { PrivacyRepositoryImpl(remote = get(), updates = get()) }
    single { MtProtoPrivacyRepository(configSource = get(), transportFactory = get(), users = get()) }
    single<PrivacyRepository> {
        TelegramBackendPrivacyRouter(
            selectionStore = get(),
            legacyFactory = { get<PrivacyRepositoryImpl>() },
            mtProtoFactory = { get<MtProtoPrivacyRepository>() },
            scope = get(),
        )
    }

    single<TelegramLinkRepository> {
        TelegramLinkRepositoryImpl(
            gateway = get(),
            updates = get(),
            keyValueDao = get(),
            scope = get(),
            dispatchers = get()
        )
    }

    single<GitHubCommitRepository> {
        GitHubCommitRepositoryImpl(
            remoteDataSource = get()
        )
    }

    single {
        LinkParser()
    }

    single {
        DraftLinkPreviewResolver()
    }

    single<LinkHandlerRepository> {
        LinkHandlerRepositoryImpl(get(), get(), get(), get(), get())
    }

    single<StoryRepository> {
        StoryRepositoryImpl(
            gateway = get(),
            updates = get(),
            scope = get(),
            fileDataSource = get(),
            tdLibLimitsRepository = get()
        )
    }

    single<StreamingRepository> {
        StreamingRepositoryImpl(
            fileDataSource = get(),
            fileObserverHub = get()
        )
    }

    single<ProxyRepository> {
        ProxyRepositoryImpl(
            remote = get(),
            appPreferences = get()
        )
    }

    single<ProxyDiagnosticsRepository> {
        ProxyDiagnosticsRepositoryImpl(
            remote = get()
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
            stringProvider = get(),
        )
    }

    single<PushDebugRepository> {
        PushDebugRepositoryImpl(
            context = androidContext(),
            appPreferences = get(),
            unifiedPushManager = get(),
            pushSyncTrigger = get(),
            scope = get()
        )
    }

    single {
        TdNotificationManager(
            androidContext(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get()
        )
    }
}
