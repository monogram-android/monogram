package org.monogram.data.di

import android.content.Context
import android.net.ConnectivityManager
import android.telephony.TelephonyManager
import androidx.room.Room
import androidx.room.RoomDatabase
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.monogram.core.DispatcherProvider
import org.monogram.data.BuildConfig
import org.monogram.data.datasource.cache.ChatLocalDataSource
import org.monogram.data.datasource.cache.MessageCacheWriter
import org.monogram.data.datasource.cache.invalidateFailedMessageCacheCoverage
import org.monogram.data.datasource.cache.RoomChatLocalDataSource
import org.monogram.data.datasource.cache.RoomStickerLocalDataSource
import org.monogram.data.datasource.cache.StickerLocalDataSource
import org.monogram.data.datasource.remote.FxEmbedRemoteDataSource
import org.monogram.data.datasource.remote.GitHubRemoteDataSource
import org.monogram.data.datasource.remote.NominatimRemoteDataSource
import org.monogram.data.datasource.remote.createMonogramHttpClient
import org.monogram.data.db.MonogramDatabase
import org.monogram.data.db.MonogramMigrations
import org.monogram.data.mtproto.MtProtoNetworkStatisticsRepository
import org.monogram.data.mtproto.MtProtoNetworkStatisticsRepositoryImpl
import org.monogram.data.mtproto.NetworkType
import org.monogram.data.mtproto.currentNetworkType
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
import org.monogram.data.mtproto.MtProtoBotCommandRepository
import org.monogram.data.mtproto.MtProtoBotCommandRepositoryImpl
import org.monogram.data.mtproto.MtProtoLinkHandler
import org.monogram.data.mtproto.MtProtoLinkHandlerImpl
import org.monogram.data.mtproto.MtProtoChatSettingsRepository
import org.monogram.data.mtproto.MtProtoChatSettingsRepositoryImpl
import org.monogram.data.mtproto.MtProtoClientOptionsRepository
import org.monogram.data.mtproto.MtProtoClientOptionsRepositoryImpl
import org.monogram.data.mtproto.MtProtoChatCreationRepository
import org.monogram.data.mtproto.MtProtoChatCreationRepositoryImpl
import org.monogram.data.mtproto.FileMtProtoDatabaseSizeReader
import org.monogram.data.mtproto.MtProtoChatStatisticsRepository
import org.monogram.data.mtproto.MtProtoChatStatisticsRepositoryImpl
import org.monogram.data.mtproto.MtProtoStreamingRepository
import org.monogram.data.mtproto.MtProtoWallpaperRepositoryImpl
import org.monogram.data.mtproto.MtProtoRoomMessageProjectionStore
import org.monogram.data.mtproto.MtProtoMessageProjectionStore
import org.monogram.data.mtproto.MtProtoRoomStoryProjectionStore
import org.monogram.data.mtproto.MtProtoStoryProjectionStore
import org.monogram.data.mtproto.MtProtoStoryResultStager
import org.monogram.data.mtproto.MtProtoStoryRefreshRepository
import org.monogram.data.mtproto.MtProtoStoryRefreshRepositoryImpl
import org.monogram.data.mtproto.MtProtoStorageCleanupRepository
import org.monogram.data.mtproto.MtProtoStorageCleanupRepositoryImpl
import org.monogram.data.mtproto.MtProtoStoryStealthModeStore
import org.monogram.data.mtproto.KeyValueMtProtoStoryStealthModeStore
import org.monogram.data.mtproto.MtProtoStoryActiveListReaderImpl
import org.monogram.data.mtproto.MtProtoStoryListRepositoryImpl
import org.monogram.data.mtproto.MtProtoStoryComposerRepositoryImpl
import org.monogram.data.mtproto.MtProtoStoryReadRepositoryImpl
import org.monogram.data.mtproto.MtProtoStoryStealthModeReader
import org.monogram.data.mtproto.MtProtoStoryStealthModeReaderImpl
import org.monogram.data.mtproto.MtProtoPremiumRepository
import org.monogram.data.mtproto.MtProtoPremiumRepositoryImpl
import org.monogram.data.mtproto.MtProtoPhotoLocationStore
import org.monogram.data.mtproto.MtProtoProfilePhotoRepository
import org.monogram.data.mtproto.MtProtoRoomPhotoLocationStore
import org.monogram.data.mtproto.MtProtoRoomDocumentLocationStore
import org.monogram.data.mtproto.MtProtoDocumentLocationStore
import org.monogram.data.mtproto.MtProtoFileHandleStore
import org.monogram.data.mtproto.MtProtoFileRepository
import org.monogram.data.mtproto.MtProtoGifRepository
import org.monogram.data.mtproto.MtProtoDocumentFileRepository
import org.monogram.data.mtproto.MtProtoFileTransferCoordinator
import org.monogram.data.mtproto.MtProtoRoomFileHandleStore
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
import org.monogram.data.mtproto.MtProtoScheduledMessageOperations
import org.monogram.data.mtproto.MtProtoScheduledMessageRepository
import org.monogram.data.mtproto.MtProtoPinnedMessageReader
import org.monogram.data.mtproto.MtProtoMessageViewerReader
import org.monogram.data.mtproto.MtProtoMessageViewerReaderImpl
import org.monogram.data.mtproto.MtProtoPinnedMessageReadRepository
import org.monogram.data.mtproto.MtProtoReadHistoryRepositoryImpl
import org.monogram.data.mtproto.MtProtoReportPeerRepository
import org.monogram.data.mtproto.MtProtoReportPeerRepositoryImpl
import org.monogram.data.mtproto.MtProtoMessageDeletionRepositoryImpl
import org.monogram.data.mtproto.MtProtoDialogChatListRepository
import org.monogram.data.mtproto.MtProtoFolderRepository
import org.monogram.data.mtproto.MtProtoEmojiRepository
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
import org.monogram.data.mtproto.MtProtoUpdateRepository
import org.monogram.data.mtproto.MtProtoAccountStateCleaner
import org.monogram.data.mtproto.MtProtoBotAdapter
import org.monogram.data.mtproto.MtProtoChatCreationAdapter
import org.monogram.data.mtproto.MtProtoChatListAdapter
import org.monogram.data.mtproto.MtProtoChatSettingsAdapter
import org.monogram.data.mtproto.MtProtoChatStatisticsAdapter
import org.monogram.data.mtproto.MtProtoClientOptionsAdapter
import org.monogram.data.mtproto.MtProtoContactEditRepository
import org.monogram.data.mtproto.MtProtoForumTopicsRepository
import org.monogram.data.mtproto.MtProtoLimitsRepository
import org.monogram.data.mtproto.MtProtoLinkHandlerAdapter
import org.monogram.data.mtproto.MtProtoMessageRepositoryAdapter
import org.monogram.data.mtproto.MtProtoPremiumAdapter
import org.monogram.data.mtproto.MtProtoProxyDiagnosticsRepository
import org.monogram.data.mtproto.MtProtoProxyRepository
import org.monogram.data.mtproto.MtProtoPlayerDataSourceFactory
import org.monogram.data.mtproto.MtProtoStorageAdapter
import org.monogram.data.mtproto.MtProtoStoryAdapter
import org.monogram.data.mtproto.MtProtoStreamingAdapter
import org.monogram.data.mtproto.MtProtoTelegramLinkRepository
import org.monogram.data.mtproto.KtorMtProtoUpdateApkDownloader
import org.monogram.data.mtproto.MtProtoCdnTransportFactory
import org.monogram.data.mtproto.MtProtoRoomUploadProgressStore
import org.monogram.data.mtproto.MtProtoRoomSecretChatStateStore
import org.monogram.data.mtproto.MtProtoSecretChatStateStore
import org.monogram.data.mtproto.MtProtoPollPayloadStore
import org.monogram.data.mtproto.MtProtoRoomPollStore
import org.monogram.data.push.MtProtoPushSync
import org.monogram.data.mtproto.MtProtoServerFileReferenceRefresher
import org.monogram.data.mtproto.MtProtoServerLogOut
import org.monogram.data.mtproto.MtProtoUpdateApkDownloader
import org.monogram.data.mtproto.MtProtoUserRepository
import org.monogram.data.mtproto.MtProtoWallpaperAdapter
import org.monogram.data.mtproto.NoOpNotificationActionManager
import org.monogram.data.mtproto.MtProtoAccountStateResetter
import org.monogram.data.mtproto.MtProtoAuthRepository
import org.monogram.data.mtproto.MtProtoAttachMenuBotRepository
import org.monogram.data.mtproto.MtProtoStickerRepository
import org.monogram.data.mtproto.MtProtoAuthSessionResetter
import org.monogram.data.mtproto.MtProtoAuthSessionHandleFactory
import org.monogram.data.mtproto.MtProtoPhoneAuthSessionFactory
import org.monogram.data.infra.AndroidStringProvider
import org.monogram.data.infra.ConnectivityNetworkSnapshotProvider
import org.monogram.data.infra.DefaultDispatcherProvider
import org.monogram.data.infra.FileMessageRegistry
import org.monogram.data.infra.NetworkSnapshotProvider
import org.monogram.data.infra.TelegramClientMetadataProvider
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
import org.monogram.data.push.UnifiedPushManager
import org.monogram.data.repository.DraftLinkPreviewResolver
import org.monogram.data.repository.GitHubCommitRepositoryImpl
import org.monogram.data.repository.LinkParser
import org.monogram.data.repository.LocationRepositoryImpl
import org.monogram.data.repository.PinnedMessageVisibilityRepositoryImpl
import org.monogram.data.repository.PollRepositoryImpl
import org.monogram.data.mtproto.MtProtoPrivacyRepository
import org.monogram.data.mtproto.MtProtoProfileEditRepository
import org.monogram.data.mtproto.MtProtoFileUploader
import org.monogram.data.mtproto.MtProtoMediaMessageRepository
import org.monogram.data.mtproto.TelegramMtProtoMediaMessageRepository
import org.monogram.data.mtproto.TelegramMtProtoFileUploader
import org.monogram.data.repository.PushDebugRepositoryImpl
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
import org.monogram.domain.repository.PollRepository
import org.monogram.domain.repository.PremiumRepository
import org.monogram.domain.repository.PrivacyRepository
import org.monogram.domain.repository.ProfilePhotoRepository
import org.monogram.domain.repository.ProxyDiagnosticsRepository
import org.monogram.domain.repository.ProxyRepository
import org.monogram.domain.repository.PushDebugRepository
import org.monogram.domain.repository.RichTextParsingRepository
import org.monogram.domain.repository.SessionRepository
import org.monogram.domain.repository.StickerRepository
import org.monogram.domain.repository.StorageRepository
import org.monogram.domain.repository.StoryRepository
import org.monogram.domain.repository.StreamingRepository
import org.monogram.domain.repository.StringProvider
import org.monogram.domain.repository.UserProfileSnapshotRepository
import org.monogram.domain.repository.TelegramLimitsRepository
import org.monogram.domain.repository.TelegramLinkRepository
import org.monogram.domain.repository.UpdateRepository
import org.monogram.domain.repository.PlayerDataSourceFactory
import org.monogram.mtproto.tl.generated.cloud.layer223.CodeSettings_3f851bba91
import org.monogram.domain.repository.UserProfileEditRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.domain.repository.WallpaperRepository
import org.monogram.domain.repository.WebAppRepository
import org.monogram.mtproto.handshake.MtProtoAuthHandshake

/** Account slot shared by the single-account MTProto wiring. */
internal const val MT_PROTO_DEFAULT_ACCOUNT_SLOT = "default"

val dataModule = module {
    single<ConnectivityManager> {
        requireNotNull(androidContext().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
    }
    single<TelephonyManager> {
        requireNotNull(androidContext().getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
    }
    includes(fcmRuntimeOverrideModule)

    single { CoroutineScope(SupervisorJob() + get<DispatcherProvider>().default) }


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
    single<MtProtoNetworkStatisticsRepository> {
        MtProtoNetworkStatisticsRepositoryImpl(
            keyValueDao = get(),
            networkType = {
                val manager = get<ConnectivityManager>()
                val roaming = get<TelephonyManager>().isNetworkRoaming
                when (manager.currentNetworkType()) {
                    NetworkType.MOBILE -> if (roaming) NetworkType.ROAMING else NetworkType.MOBILE
                    else -> manager.currentNetworkType()
                }
            },
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
            trafficListener = get<MtProtoNetworkStatisticsRepository>().trafficListener,
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
        TelegramMtProtoAuthorizedSessionRestorer(get(), get(), get(), get(), get())
    }
    single<MtProtoSessionTransportFactory> {
        object : MtProtoSessionTransportFactory {
            override suspend fun open(accountSlot: String) =
                get<TelegramMtProtoSessionFactory>().open(accountSlot)

            override suspend fun open(accountSlot: String, dcId: Int) =
                get<TelegramMtProtoSessionFactory>().open(accountSlot, dcId)
        }
    }
    single { MtProtoAuthRepository(get(), get(), get(), get(), get()) }
    single<MtProtoAuthSessionResetter> { get<MtProtoAuthRepository>() }

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

    single<AuthRepository>(createdAtStart = true) {
        get<MtProtoAuthRepository>()
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
                MonogramMigrations.MIGRATION_48_49,
                MonogramMigrations.MIGRATION_49_50,
                MonogramMigrations.MIGRATION_50_51,
                MonogramMigrations.MIGRATION_51_52,
                MonogramMigrations.MIGRATION_52_53,
                MonogramMigrations.MIGRATION_53_54,
                MonogramMigrations.MIGRATION_54_55,
                MonogramMigrations.MIGRATION_55_56,
                MonogramMigrations.MIGRATION_56_57,
                MonogramMigrations.MIGRATION_57_58,
                MonogramMigrations.MIGRATION_58_59,
                MonogramMigrations.MIGRATION_59_60,
                MonogramMigrations.MIGRATION_60_61,
                MonogramMigrations.MIGRATION_61_62,
                MonogramMigrations.MIGRATION_62_63,
                MonogramMigrations.MIGRATION_63_64,
                MonogramMigrations.MIGRATION_64_65,
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
    single { get<MonogramDatabase>().mtProtoPhotoLocationDao() }
    single { get<MonogramDatabase>().mtProtoStoryProjectionDao() }
    single { get<MonogramDatabase>().mtProtoDraftProjectionDao() }
    single { get<MonogramDatabase>().mtProtoDocumentLocationDao() }
    single { get<MonogramDatabase>().mtProtoFileHandleDao() }
    single { get<MonogramDatabase>().mtProtoFileTransferDao() }
    single { MtProtoRoomUserProjectionStore(get(), cloudObjectDao = get()) }
    single<MtProtoUserProjectionStore> { get<MtProtoRoomUserProjectionStore>() }
    single { MtProtoRoomChatProjectionStore(get(), cloudObjectDao = get()) }
    single<MtProtoChatProjectionStore> { get<MtProtoRoomChatProjectionStore>() }
    single<MtProtoChatSettingsRepository> { MtProtoChatSettingsRepositoryImpl(get(), get(), get(), get(), get(), get()) }
    single<MtProtoClientOptionsRepository> { MtProtoClientOptionsRepositoryImpl(get(), get()) }
    single<MtProtoChatCreationRepository> { MtProtoChatCreationRepositoryImpl(get(), get(), get(), get()) }
    single<MtProtoChatStatisticsRepository> { MtProtoChatStatisticsRepositoryImpl(get(), get(), get(), get()) }
    single { MtProtoRoomDocumentLocationStore(get()) }
    single<MtProtoDocumentLocationStore> { get<MtProtoRoomDocumentLocationStore>() }
    single<MtProtoPhotoLocationStore> { MtProtoRoomPhotoLocationStore(get()) }
    single { MtProtoRoomStoryProjectionStore(get(), database = get()) }
    single<MtProtoStoryProjectionStore> { get<MtProtoRoomStoryProjectionStore>() }
    single<MtProtoStoryStealthModeStore> { KeyValueMtProtoStoryStealthModeStore(get()) }
    single<MtProtoStoryStealthModeReader> { MtProtoStoryStealthModeReaderImpl(get(), get()) }
    single { MtProtoStoryResultStager(get(), get(), get(), get(), get(), get()) }
    single<MtProtoStoryRefreshRepository> {
        MtProtoStoryRefreshRepositoryImpl(
            configSource = get(),
            transportFactory = get(),
            stories = get(),
            resultStager = get(),
            users = get(),
            chats = get(),
        )
    }
    single<MtProtoFileHandleStore> { MtProtoRoomFileHandleStore(get()) }
    single { MtProtoFileTransferCoordinator(
        transportFactory = get(),
        cdnTransportFactory = MtProtoCdnTransportFactory { dcId ->
            get<TelegramMtProtoSessionFactory>().openCdn(MT_PROTO_DEFAULT_ACCOUNT_SLOT, dcId)
        },
    ) }
    single<MtProtoFileRepository> {
        MtProtoDocumentFileRepository(
            context = androidContext(),
            configSource = get(),
            handles = get(),
            locations = get(),
            photos = get(),
            transfers = get(),
            coordinator = get(),
            scope = get(),
            referenceRefresher = MtProtoServerFileReferenceRefresher(
                configSource = get(),
                transportFactory = get(),
                chats = get(),
                documentLocations = get(),
                photoLocations = get(),
            ),
        )
    }
    single {
        MtProtoRoomMessageProjectionStore(
            get(),
            cloudObjectDao = get(),
            dialogStore = get(),
            documentLocations = get(),
            photoLocations = get(),
            database = get(),
        )
    }
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
    single<MtProtoMessageViewerReader> {
        MtProtoMessageViewerReaderImpl(
            configSource = get(),
            transportFactory = get(),
            users = get(),
            chats = get(),
        )
    }

    single<MtProtoPinnedMessageReader> {
        MtProtoPinnedMessageReadRepository(
            configSource = get(),
            transportFactory = get(),
            userStore = get(),
            chatStore = get(),
            messageStore = get(),
            resultStager = get(),
        )
    }
    single<MtProtoScheduledMessageOperations> {
        MtProtoScheduledMessageRepository(
            configSource = get(),
            transportFactory = get(),
            userStore = get(),
            chatStore = get(),
            messageStore = get(),
            resultStager = get(),
        )
    }
    single<MtProtoPinnedMessageRepository> {
        MtProtoPinnedMessageRepositoryImpl(
            dialogs = get(),
            messages = get(),
        )
    }
    single<MtProtoMediaMessageRepository> {
        TelegramMtProtoMediaMessageRepository(
            configSource = get(),
            transportFactory = get(),
            uploader = get(),
            users = get(),
            chats = get(),
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
    single {
        MtProtoUserProfileSnapshotRepository(
            configSource = get(),
            userStore = get(),
            chatStore = get(),
            sessionFactory = get(),
        )
    }
    single<MtProtoUserProfileReader> { get<MtProtoUserProfileSnapshotRepository>() }
    single {
        MtProtoRoomCloudObjectStager(
            get(),
            userProjectionStore = get(),
            chatProjectionStore = get(),
            messageProjectionStore = get(),
            draftStore = get(),
            storyResultStager = get(),
        )
    }
    single<MtProtoCloudObjectStager> { get<MtProtoRoomCloudObjectStager>() }
    single { MtProtoRoomPendingEnvelopeStore(get()) }
    single { get<MonogramDatabase>().secretChatStateDao() }
    single<org.monogram.domain.repository.SponsorRepository> {
        org.monogram.data.mtproto.MtProtoSponsorRepository(scope = get())
    }
    single<MtProtoPushSync> {
        val sessionFactory = get<TelegramMtProtoSessionFactory>()
        MtProtoPushSync(
            scope = get(),
            execute = { reason ->
                sessionFactory.open(MT_PROTO_DEFAULT_ACCOUNT_SLOT).use { transport ->
                    @Suppress("UNCHECKED_CAST")
                    transport.execute(
                        org.monogram.mtproto.tl.generated.cloud.layer223.help.GetAppConfig(hash = 0)
                            as org.monogram.mtproto.tl.runtime.TlMethod<org.monogram.mtproto.tl.runtime.TlObject>
                    )
                }
            },
        )
    }
    single { get<MonogramDatabase>().mtProtoPollDao() }
    single<MtProtoPollPayloadStore> { MtProtoRoomPollStore(dao = get(), accountSlot = MT_PROTO_DEFAULT_ACCOUNT_SLOT) }
    single<MtProtoSecretChatStateStore> {
        MtProtoRoomSecretChatStateStore(dao = get(), accountSlot = MT_PROTO_DEFAULT_ACCOUNT_SLOT)
    }
    single<MtProtoPendingEnvelopeStore> { get<MtProtoRoomPendingEnvelopeStore>() }
    single { MtProtoRoomUpdateStateStore(get(), get()) }
    single<MtProtoTransactionalUpdateStateStore> { get<MtProtoRoomUpdateStateStore>() }
    single { MtProtoRoomLiveUpdateApplier(get(), get(), get()) }
    single<MtProtoRecoveryStateStore> { get<MtProtoRoomUpdateStateStore>() }
    single { MtProtoRoomUpdateRecovery(get(), get(), get(), get()) }
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
            fileHandleStore = get(),
            photoLocationStore = get(),
            storyProjectionStore = get(),
            storyStealthModeStore = get(),
            secretChatStateStore = get(),
            pollPayloads = get<MtProtoPollPayloadStore>() as? MtProtoRoomPollStore,
            authorizationStore = get(),
        )
    }
    single<MtProtoAccountStateResetter> { get<MtProtoAccountStateCleaner>() }
    single { get<MonogramDatabase>().chatFullInfoDao() }
    single { get<MonogramDatabase>().topicDao() }
    single { get<MonogramDatabase>().stickerSetDao() }
    single { get<MonogramDatabase>().recentEmojiDao() }
    single { get<MonogramDatabase>().searchHistoryDao() }
    single { get<MonogramDatabase>().chatFolderDao() }
    single { get<MonogramDatabase>().attachBotDao() }
    single { get<MonogramDatabase>().keyValueDao() }
    single(createdAtStart = true) {
        MtProtoLiveUpdateCoordinator(
            authRepository = get(),
            transportFactory = get(),
            configSource = get(),
            recovery = get(),
            liveUpdateApplier = get(),
            storyRefresh = get(),
            dialogs = get<MtProtoDialogSnapshotRepository>(),
            fullResync = { resyncScope ->
                get<MtProtoPendingEnvelopeStore>().deleteScope(resyncScope)
                get<MtProtoRoomUpdateStateStore>().delete(resyncScope)
            },
            scope = get(),
        )
    }
    single<MtProtoLiveSessionResetter> { get<MtProtoLiveUpdateCoordinator>() }
    single { get<MonogramDatabase>().notificationSettingDao() }
    single { get<MonogramDatabase>().notificationExceptionDao() }
    single { get<MonogramDatabase>().wallpaperDao() }
    single { get<MonogramDatabase>().stickerPathDao() }
    single { get<MonogramDatabase>().sponsorDao() }
    single { get<MonogramDatabase>().textCompositionStyleDao() }

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

    single<UserRepository> {
        MtProtoUserRepository(
            mtProtoProfiles = get<MtProtoUserProfileReader>(),
            scope = get(),
            mtProtoAuthorizationStore = get(),
            mtProtoAccountStateResetter = get(),
            mtProtoAuthSessionResetter = get(),
            mtProtoLiveSessionResetter = get(),
            mtProtoServerLogOut = MtProtoServerLogOut {
                get<TelegramMtProtoSessionFactory>().open(MT_PROTO_DEFAULT_ACCOUNT_SLOT).use { transport ->
                    transport.execute(org.monogram.mtproto.tl.generated.cloud.layer223.auth.LogOut)
                }
            },
            mtProtoUserUpdates = get<MtProtoUserProjectionStore>().updates,
            mtProtoUserFullInfo = { userId -> get<MtProtoChatInfoRepository>().getChatFullInfo(userId) },
        )
    }
    single<DialogSnapshotRepository> { get<MtProtoDialogSnapshotRepository>() }
    single<MessageHistorySnapshotRepository> { get<MtProtoMessageHistorySnapshotRepository>() }
    single<UserProfileSnapshotRepository> { get<MtProtoUserProfileSnapshotRepository>() }

    single<MtProtoFileUploader> {
        TelegramMtProtoFileUploader(
            transportFactory = get(),
            progressStore = MtProtoRoomUploadProgressStore(
                dao = get(),
                accountSlot = MT_PROTO_DEFAULT_ACCOUNT_SLOT,
                dcIdProvider = {
                    get<TelegramMtProtoBootstrapConfigSource>()
                        .createForAccount(MT_PROTO_DEFAULT_ACCOUNT_SLOT).endpoint.dcId
                },
            ),
        )
    }
    single {
        MtProtoProfileEditRepository(
            configSource = get(),
            transportFactory = get(),
            users = get(),
            chats = get(),
            uploader = get(),
        )
    }
    single<UserProfileEditRepository> {
        get<MtProtoProfileEditRepository>()
    }

    single<ContactEditRepository> {
        MtProtoContactEditRepository(
            users = get(),
            mtProtoProfiles = get(),
        )
    }

    single<ProfilePhotoRepository> {
        MtProtoProfilePhotoRepository(
            configSource = get(),
            transportFactory = get(),
            users = get(),
            chats = get(),
            resultStager = get(),
            locations = get(),
            files = get(),
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
            cloudObjectStager = get(),
        )
    }
    single<ChatInfoRepository> {
        get<MtProtoChatInfoRepository>()
    }

    single<MtProtoPremiumRepository> { MtProtoPremiumRepositoryImpl(get()) }
    single<PremiumRepository> {
        MtProtoPremiumAdapter(
            mtProtoFactory = { get<MtProtoPremiumRepository>() },
        )
    }

    single<BotRepository> {
        MtProtoBotAdapter(
            mtProtoFactory = {
                MtProtoBotCommandRepositoryImpl(
                    configSource = get(),
                    transportFactory = get(),
                    users = get(),
                )
            },
        )
    }

    single<ChatStatisticsRepository> {
        MtProtoChatStatisticsAdapter(
            mtProtoFactory = { get<MtProtoChatStatisticsRepository>() },
        )
    }

    single<NetworkSnapshotProvider> {
        ConnectivityNetworkSnapshotProvider(
            connectivityManager = androidContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
    single<MtProtoFolderRepository> {
        MtProtoFolderRepository(
            configSource = get(),
            transportFactory = get(),
            userStore = get(),
            chatStore = get(),
            keyValueDao = get(),
            scope = get(),
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
            folderRepository = get(),
            refreshFolders = { get<MtProtoFolderRepository>().refresh() },
        )
    }
    single {
        MtProtoChatListAdapter(
            mtProtoFactory = { get<MtProtoDialogChatListRepository>() },
            scope = get(),
        )
    }
    single<ChatListRepository> { get<MtProtoChatListAdapter>() }
    single<ChatFolderRepository> { get<MtProtoChatListAdapter>() }
    single<ChatOperationsRepository> { get<MtProtoChatListAdapter>() }
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
        get<MtProtoChatSearchRepository>()
    }
    single<ForumTopicsRepository> {
        MtProtoForumTopicsRepository(
            configSource = get(),
            transportFactory = get(),
            userStore = get(),
            chatStore = get(),
        )
    }
    single<ChatSettingsRepository> {
        MtProtoChatSettingsAdapter(
            mtProtoFactory = { get<MtProtoChatSettingsRepository>() },
        )
    }
    single<ChatCreationRepository> {
        MtProtoChatCreationAdapter(
            mtProtoFactory = { get<MtProtoChatCreationRepository>() },
            mtProtoDatabaseSizeReaderFactory = {
                FileMtProtoDatabaseSizeReader(androidContext().getDatabasePath("monogram_db"))
            },
            storageCleanupFactory = { get<MtProtoStorageCleanupRepository>() },
        )
    }

    single<ClientOptionsRepository> {
        MtProtoClientOptionsAdapter(
            mtProtoFactory = { get<MtProtoClientOptionsRepository>() },
        )
    }

    single<TelegramLimitsRepository> {
        MtProtoLimitsRepository(
            configSource = get(),
            transportFactory = get(),
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
        get<MtProtoNotificationSettingsRepository>()
    }

    single<MtProtoSessionRepository> { MtProtoSessionRepository(transportFactory = get()) }
    single<SessionRepository> {
        get<MtProtoSessionRepository>()
    }

    single<WallpaperRepository> {
        MtProtoWallpaperAdapter(
            mtProtoFactory = {
                MtProtoWallpaperRepositoryImpl(
                    configSource = get(),
                    transportFactory = get(),
                    documents = get(),
                    files = get(),
                    scope = get(),
                )
            },
        )
    }

    single<StorageRepository> {
        MtProtoStorageAdapter(
            mtProtoCleanupFactory = {
                MtProtoStorageCleanupRepositoryImpl(
                    transfers = get(),
                    filesDirectory = java.io.File(androidContext().filesDir, "mtproto/files"),
                )
            },
            mtProtoUsageFactory = {
                MtProtoStorageCleanupRepositoryImpl(
                    transfers = get(),
                    filesDirectory = java.io.File(androidContext().filesDir, "mtproto/files"),
                )
            },
            keyValues = get(),
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
        get<MtProtoNetworkStatisticsRepository>()
    }

    single<MtProtoAttachMenuBotRepository> {
        MtProtoAttachMenuBotRepository(
            transportFactory = get(),
            scope = get(),
        )
    }
    single<AttachMenuBotRepository> {
        get<MtProtoAttachMenuBotRepository>()
    }

    single<PollRepository> {
        PollRepositoryImpl()
    }


    single<MessageRepository> {
        MtProtoMessageRepositoryAdapter(
            draftFactory = { get<MtProtoDraftRepository>() },
            deleteFactory = { get<MtProtoDeleteMessageRepository>() },
            pinnedFactory = { get<MtProtoPinnedMessageRepository>() },
            scheduledFactory = { get<MtProtoScheduledMessageOperations>() },
            pinnedReadFactory = { get<MtProtoPinnedMessageReader>() },
            textFactory = { get<MtProtoTextMessageRepository>() },
            readHistoryFactory = { get<MtProtoReadHistoryRepository>() },
            viewerFactory = { get<MtProtoMessageViewerReader>() },
            fileFactory = { get<MtProtoFileRepository>() },
            mediaFactory = { get<MtProtoMediaMessageRepository>() },
            historyRepository = get<MtProtoMessageHistorySnapshotRepository>(),
            pollPayloads = get(),
        ).repository
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


    single<MtProtoStickerRepository> {
        MtProtoStickerRepository(
            configSource = get(),
            transportFactory = get(),
            locations = get(),
            files = get(),
        )
    }
    single<StickerRepository> {
        get<MtProtoStickerRepository>()
    }

    single<MtProtoGifRepository> {
        MtProtoGifRepository(
            configSource = get(),
            transportFactory = get(),
            locations = get(),
            files = get(),
        )
    }
    single<GifRepository> {
        get<MtProtoGifRepository>()
    }

    single<EmojiRepository> {
        MtProtoEmojiRepository(
            context = androidContext(),
            localDataSource = get(),
            transportFactory = get(),
            configSource = get(),
            locations = get(),
        )
    }


    single {
        MtProtoPrivacyRepository(
            configSource = get(),
            transportFactory = get(),
            users = get(),
            chats = get(),
            accountStateResetter = get(),
            liveSessionResetter = get(),
        )
    }
    single<PrivacyRepository> {
        get<MtProtoPrivacyRepository>()
    }

    single<TelegramLinkRepository> {
        MtProtoTelegramLinkRepository()
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
        MtProtoLinkHandlerAdapter(
            mtProtoFactory = {
                MtProtoLinkHandlerImpl(
                    parser = get(),
                    configSource = get(),
                    transportFactory = get(),
                    users = get(),
                    chats = get(),
                    cloudObjectStager = get(),
                )
            },
        )
    }

    single<StoryRepository> {
        MtProtoStoryAdapter(
            scope = get(),
            mtProtoFactory = {
                MtProtoStoryListRepositoryImpl(
                    configSource = get(),
                    transportFactory = get(),
                    users = get(),
                    chats = get(),
                    stories = get(),
                    cloudObjectStager = get(),
                    storyResultStager = get(),
                )
            },
            mtProtoActiveListFactory = {
                MtProtoStoryActiveListReaderImpl(get(), get(), get(), get())
            },
            mtProtoReadFactory = {
                MtProtoStoryReadRepositoryImpl(
                    configSource = get(),
                    stories = get(),
                    chats = get(),
                    files = get(),
                )
            },
            mtProtoComposerFactory = {
                MtProtoStoryComposerRepositoryImpl(
                    configSource = get(),
                    transportFactory = get(),
                    uploader = get(),
                    users = get(),
                    chats = get(),
                    storyResultStager = get(),
                    storyReader = MtProtoStoryReadRepositoryImpl(
                        configSource = get(),
                        stories = get(),
                        chats = get(),
                        files = get(),
                    ),
                )
            },
            mtProtoStealthModeFactory = { get() },
        )
    }

    single<StreamingRepository> {
        MtProtoStreamingAdapter(
            mtProtoFactory = { MtProtoStreamingRepository(get()) },
        )
    }

    single<PlayerDataSourceFactory> {
        MtProtoPlayerDataSourceFactory(files = get())
    }

    single<ProxyRepository> {
        MtProtoProxyRepository(keyValues = get())
    }

    single<UpdateRepository> {
        MtProtoUpdateRepository(
            context = androidContext(),
            releaseSource = { get<GitHubRemoteDataSource>().getLatestRelease() },
            apkDownloader = KtorMtProtoUpdateApkDownloader(get()),
            scope = get(),
        )
    }

    single<ProxyDiagnosticsRepository> {
        MtProtoProxyDiagnosticsRepository()
    }


    single<LocationRepository> {
        LocationRepositoryImpl(
            remote = get()
        )
    }


    single<PushDebugRepository> {
        PushDebugRepositoryImpl(
            context = androidContext(),
            appPreferences = get(),
            unifiedPushManager = get(),
            scope = get()
        )
    }


    single<org.monogram.data.service.NotificationActionManager> {
        NoOpNotificationActionManager()
    }
}
