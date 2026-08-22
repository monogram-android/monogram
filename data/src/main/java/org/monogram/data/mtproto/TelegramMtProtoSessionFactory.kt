package org.monogram.data.mtproto

import kotlinx.coroutines.CancellationException
import org.monogram.data.BuildConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.ExportAuthorization
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.ExportedAuthorization_cdf68dd957
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.ImportAuthorization
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.data.infra.TelegramClientMetadataProvider
import org.monogram.mtproto.handshake.MtProtoAuthKey
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.CloudLayer223RpcTransport
import org.monogram.mtproto.transport.IntermediateTcpEncryptedTransport
import org.monogram.mtproto.transport.IntermediateTcpHandshakeTransport
import org.monogram.mtproto.transport.MtProtoHandshakeConnection
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.mtproto.transport.MtProtoRpcTransport
import org.monogram.mtproto.transport.MtProtoSessionMaintenance
import org.monogram.mtproto.transport.MtProtoEncryptedSession
import org.monogram.mtproto.transport.MtProtoTrafficListener

internal data class TelegramMtProtoEndpoint(
    val dcId: Int,
    val host: String,
    val port: Int,
) {
    init {
        require(dcId > 0) { "dcId must be positive" }
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port must be within 1..65535" }
    }
}

internal data class TelegramMtProtoBootstrapConfig(
    val endpoint: TelegramMtProtoEndpoint,
    val handshake: MtProtoHandshakeConfig,
    val cloud: CloudLayer223ConnectionConfig,
) {
    init {
        require(endpoint.dcId == handshake.dcId) { "MTProto endpoint and handshake DC must match" }
    }
}

internal fun interface TelegramMtProtoBootstrapConfigSource {
    suspend fun create(): TelegramMtProtoBootstrapConfig

    suspend fun createForDc(dcId: Int): TelegramMtProtoBootstrapConfig {
        val config = create()
        require(config.endpoint.dcId == dcId) { "MTProto bootstrap source does not support DC $dcId" }
        return config
    }

    suspend fun createForAccount(accountSlot: String): TelegramMtProtoBootstrapConfig = create()
}

internal fun interface MtProtoAuthKeyLoader {
    suspend fun load(
        scope: MtProtoAuthKeyScope,
        transport: MtProtoHandshakeConnection,
        config: MtProtoHandshakeConfig,
    ): BootstrappedMtProtoAuthKey
}

internal class TelegramMtProtoBootstrapConfigProvider(
    private val metadataProvider: TelegramClientMetadataProvider,
    private val accountDcStore: MtProtoAccountDcStore? = null,
) : TelegramMtProtoBootstrapConfigSource {
    override suspend fun create(): TelegramMtProtoBootstrapConfig = createForDc(DEFAULT_DC_ID)

    override suspend fun createForDc(dcId: Int): TelegramMtProtoBootstrapConfig {
        require(BuildConfig.API_ID > 0) { "API_ID must be configured before starting MTProto" }
        val metadata = metadataProvider.create()
        val endpoint = telegramMtProtoEndpointForDc(dcId)
        return TelegramMtProtoBootstrapConfig(
            endpoint = endpoint,
            handshake = MtProtoHandshakeConfig(
                dcId = endpoint.dcId,
                serverRsaPublicKeys = listOf(MAIN_RSA_PUBLIC_KEY),
            ),
            cloud = CloudLayer223ConnectionConfig(
                apiId = BuildConfig.API_ID,
                deviceModel = metadata.deviceModel,
                systemVersion = metadata.systemVersion,
                applicationVersion = metadata.applicationVersion,
                systemLanguageCode = metadata.systemLanguageCode,
            ),
        )
    }

    override suspend fun createForAccount(accountSlot: String): TelegramMtProtoBootstrapConfig =
        createForDc(accountDcStore?.get(accountSlot) ?: DEFAULT_DC_ID)

    private companion object {
        const val DEFAULT_DC_ID = 2

        val MAIN_RSA_PUBLIC_KEY = """
            -----BEGIN RSA PUBLIC KEY-----
            MIIBCgKCAQEA6LszBcC1LGzyr992NzE0ieY+BSaOW622Aa9Bd4ZHLl+TuFQ4lo4g
            5nKaMBwK/BIb9xUfg0Q29/2mgIR6Zr9krM7HjuIcCzFvDtr+L0GQjae9H0pRB2OO
            62cECs5HKhT5DZ98K33vmWiLowc621dQuwKWSQKjWf50XYFw42h21P2KXUGyp2y/
            +aEyZ+uVgLLQbRA1dEjSDZ2iGRy12Mk5gpYc397aYp438fsJoHIgJ2lgMv5h7WY9
            t6N/byY9Nw9p21Og3AoXSL2q/2IJ1WRUhebgAdGVMlV1fkuOQoEzR7EdpqtQD9Cs
            5+bfo3Nhmcyvk5ftB0WkJ9z6bNZ7yxrP8wIDAQAB
            -----END RSA PUBLIC KEY-----
        """.trimIndent()
    }
}

internal fun telegramMtProtoEndpointForDc(dcId: Int): TelegramMtProtoEndpoint = when (dcId) {
    1 -> TelegramMtProtoEndpoint(dcId, "149.154.175.50", 443)
    2 -> TelegramMtProtoEndpoint(dcId, "149.154.167.51", 443)
    3 -> TelegramMtProtoEndpoint(dcId, "149.154.175.100", 443)
    4 -> TelegramMtProtoEndpoint(dcId, "149.154.167.91", 443)
    5 -> TelegramMtProtoEndpoint(dcId, "149.154.171.5", 443)
    else -> throw IllegalArgumentException("Unsupported Telegram production DC: $dcId")
}

internal object NoOpMtProtoAuthKeyStore : MtProtoAuthKeyStore {
    override suspend fun load(scope: MtProtoAuthKeyScope) = MtProtoAuthKeyLoadResult.Missing
    override suspend fun save(scope: MtProtoAuthKeyScope, authKey: StoredMtProtoAuthKey) = Unit
    override suspend fun delete(scope: MtProtoAuthKeyScope) = Unit
    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
}

internal class TelegramMtProtoSessionFactory(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val keyLoader: MtProtoAuthKeyLoader,
    private val authKeyPersistence: MtProtoAuthKeyPersistence? = null,
    private val userProjectionStore: MtProtoUserProjectionStore = NoOpMtProtoUserProjectionStore,
    private val chatProjectionStore: MtProtoChatProjectionStore = NoOpMtProtoChatProjectionStore,
    private val messageProjectionStore: MtProtoMessageProjectionStore = NoOpMtProtoMessageProjectionStore,
    private val trafficListener: MtProtoTrafficListener? = null,
    private val handshakeConnectionFactory: (TelegramMtProtoEndpoint) -> MtProtoHandshakeConnection = {
        IntermediateTcpHandshakeTransport(it.host, it.port)
    },
    private val encryptedTransportFactory: (
        MtProtoAuthKeyScope,
        TelegramMtProtoEndpoint,
        MtProtoAuthKey,
        CloudLayer223ConnectionConfig,
    ) -> MtProtoRpcTransport = { scope, endpoint, authKey, cloudConfig ->
        createEncryptedTransport(
            scope,
            endpoint,
            authKey,
            cloudConfig,
            requireNotNull(authKeyPersistence) { "MTProto auth-key persistence is required" },
            trafficListener,
        )
    },
) {
    private data class SessionKey(
        val accountSlot: String,
        val dcId: Int,
    )

    private class CachedSession(
        val transport: MtProtoRpcTransport,
        var references: Int = 0,
    )

    private val secondaryAuthorizationMutex = Mutex()
    private val sessionLock = Any()
    private val sessions = mutableMapOf<SessionKey, CachedSession>()
    private val openingSessions = mutableMapOf<SessionKey, CompletableDeferred<CachedSession>>()

    suspend fun open(accountSlot: String = DEFAULT_ACCOUNT_SLOT): MtProtoRpcTransport = open(accountSlot, null)

    suspend fun open(accountSlot: String, dcId: Int?): MtProtoRpcTransport {
        val config = if (dcId == null) {
            configSource.createForAccount(accountSlot)
        } else {
            configSource.createForDc(dcId)
        }
        val key = SessionKey(accountSlot, config.endpoint.dcId)
        var cached: CachedSession? = null
        var pending: CompletableDeferred<CachedSession>? = null
        var isOwner = false
        synchronized(sessionLock) {
            val existing = sessions[key]
            if (existing != null) {
                existing.references++
                cached = existing
            } else {
                pending = openingSessions[key]
                if (pending == null) {
                    pending = CompletableDeferred()
                    openingSessions[key] = pending!!
                    isOwner = true
                }
            }
        }
        if (cached != null) return lease(key, cached!!)
        if (!isOwner) {
            val completed = pending!!.await()
            synchronized(sessionLock) {
                val current = sessions[key]
                if (current === completed) {
                    current.references++
                    return lease(key, current)
                }
            }
            // The owner may have released the session before this waiter acquired its lease.
            return open(accountSlot, key.dcId)
        }

        return try {
            val opened = openFresh(accountSlot, config)
            synchronized(sessionLock) {
                val completed = CachedSession(opened, references = 1)
                sessions[key] = completed
                openingSessions.remove(key)?.complete(completed)
                cached = completed
            }
            lease(key, cached!!)
        } catch (failure: Throwable) {
            synchronized(sessionLock) {
                openingSessions.remove(key)?.completeExceptionally(failure)
            }
            throw failure
        }
    }

    private suspend fun openFresh(
        accountSlot: String,
        config: TelegramMtProtoBootstrapConfig,
    ): MtProtoRpcTransport {
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val handshake = handshakeConnectionFactory(config.endpoint)
        val bootstrapped = handshake.use { connection ->
            keyLoader.load(scope, connection, config.handshake)
        }
        val transport = try {
            encryptedTransportFactory(scope, config.endpoint, bootstrapped.authKey, config.cloud)
        } catch (failure: Throwable) {
            bootstrapped.authKey.close()
            throw failure
        }
        (transport as? org.monogram.mtproto.transport.MtProtoFutureSaltState)
            ?.restoreFutureSalts(bootstrapped.futureSalts)
        return try {
            try {
                (transport as? MtProtoSessionMaintenance)?.refreshFutureSalts()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
            }
            authorizeSecondaryDcIfNeeded(accountSlot, config.endpoint.dcId, transport)
            userProjectionStore.backfill(scope)
            chatProjectionStore.backfill(scope)
            messageProjectionStore.backfill(scope)
            transport
        } catch (failure: Throwable) {
            transport.close()
            throw failure
        }
    }

    private fun lease(key: SessionKey, cached: CachedSession): MtProtoRpcTransport =
        object : MtProtoRpcTransport {
            private val released = AtomicBoolean(false)

            override val updates
                get() = cached.transport.updates

            override val newSessions
                get() = cached.transport.newSessions

            override suspend fun <R> execute(method: org.monogram.mtproto.tl.runtime.TlMethod<R>): R =
                cached.transport.execute(method)

            override fun close() {
                if (released.compareAndSet(false, true)) release(key, cached)
            }
        }

    private fun release(key: SessionKey, cached: CachedSession) {
        var close: MtProtoRpcTransport? = null
        synchronized(sessionLock) {
            if (cached.references > 0) cached.references--
            if (cached.references == 0 && sessions[key] === cached) {
                sessions.remove(key)
                close = cached.transport
            }
        }
        close?.close()
    }

    /**
     * Opens a transport to one CDN DC reusing the persisted home-DC auth key, as required by
     * core.telegram.org/cdn. No handshake key establishment and no secondary export/import run;
     * per-connection salt/time updates are discarded instead of polluting home-DC records.
     */
    suspend fun openCdn(accountSlot: String = DEFAULT_ACCOUNT_SLOT, dcId: Int): MtProtoRpcTransport {
        require(dcId in SUPPORTED_DC_IDS) { "Unsupported CDN Telegram DC: $dcId" }
        val persistence = authKeyPersistence
            ?: throw IllegalStateException("MTProto auth-key persistence is required for CDN transports")
        val homeConfig = configSource.createForAccount(accountSlot)
        val homeScope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, homeConfig.endpoint.dcId)
        val persisted = when (val loaded = persistence.load(homeScope)) {
            PersistedMtProtoAuthKeyResult.Missing,
            PersistedMtProtoAuthKeyResult.Corrupt,
            -> throw IllegalStateException("CDN transports require an authorized session on the home DC")
            is PersistedMtProtoAuthKeyResult.Found -> loaded
        }
        val endpoint = telegramMtProtoEndpointForDc(dcId)
        val transport = try {
            createEncryptedTransport(
                scope = homeScope,
                endpoint = endpoint,
                authKey = persisted.authKey,
                cloudConfig = homeConfig.cloud,
                authKeyPersistence = NO_OP_AUTH_KEY_PERSISTENCE,
                trafficListener = trafficListener,
            )
        } catch (failure: Throwable) {
            persisted.authKey.close()
            throw failure
        }
        (transport as? org.monogram.mtproto.transport.MtProtoFutureSaltState)
            ?.restoreFutureSalts(persisted.futureSalts)
        return try {
            try {
                (transport as? MtProtoSessionMaintenance)?.refreshFutureSalts()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
            }
            transport
        } catch (failure: Throwable) {
            transport.close()
            throw failure
        }
    }

    private suspend fun authorizeSecondaryDcIfNeeded(
        accountSlot: String,
        targetDcId: Int,
        targetTransport: MtProtoRpcTransport,
    ) {
        val homeDcId = configSource.createForAccount(accountSlot).endpoint.dcId
        if (targetDcId == homeDcId) return

        require(targetDcId in SUPPORTED_DC_IDS) { "Unsupported secondary Telegram DC: $targetDcId" }
        val homeTransport = open(accountSlot, homeDcId)
        try {
            secondaryAuthorizationMutex.withLock {
                val exported = homeTransport.execute(ExportAuthorization(targetDcId))
                val authorization = exported as? ExportedAuthorization_cdf68dd957
                    ?: error("auth.exportAuthorization returned an unsupported authorization object")
                val bytes = authorization.bytes.toByteArray()
                try {
                    targetTransport.execute(ImportAuthorization(authorization.id, TlBytes.copyOf(bytes)))
                } finally {
                    bytes.fill(0)
                }
            }
        } finally {
            homeTransport.close()
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        val SUPPORTED_DC_IDS = 1..5

        /** Discards per-connection salt/time updates so CDN transports never pollute home-DC records. */
        private val NO_OP_AUTH_KEY_PERSISTENCE = MtProtoAuthKeyPersistence(NoOpMtProtoAuthKeyStore)

        fun createEncryptedTransport(
            scope: MtProtoAuthKeyScope,
            endpoint: TelegramMtProtoEndpoint,
            authKey: MtProtoAuthKey,
            cloudConfig: CloudLayer223ConnectionConfig,
            authKeyPersistence: MtProtoAuthKeyPersistence,
            trafficListener: MtProtoTrafficListener?,
        ): MtProtoRpcTransport {
            val session = try {
                MtProtoEncryptedSession(authKey)
            } catch (failure: Throwable) {
                authKey.close()
                throw failure
            }
            val stateLock = Mutex()
            var serverSalt = authKey.serverSalt
            var serverTimeSeconds = authKey.serverTimeAnchorSeconds.toLong()
            val raw = try {
                IntermediateTcpEncryptedTransport(
                    host = endpoint.host,
                    port = endpoint.port,
                    session = session,
                    onServerSaltChanged = { updatedSalt ->
                        stateLock.withLock {
                            serverSalt = updatedSalt
                            authKeyPersistence.updateServerState(
                                scope,
                                authKey,
                                serverSalt,
                                serverTimeSeconds,
                                session.copyFutureSalts(),
                            )
                        }
                    },
                    onFutureSaltsChanged = { updatedSalts ->
                        stateLock.withLock {
                            authKeyPersistence.updateServerState(
                                scope,
                                authKey,
                                serverSalt,
                                serverTimeSeconds,
                                updatedSalts,
                            )
                        }
                    },
                    onServerTimeChanged = { updatedTime ->
                        stateLock.withLock {
                            serverTimeSeconds = updatedTime
                            authKeyPersistence.updateServerState(
                                scope,
                                authKey,
                                serverSalt,
                                serverTimeSeconds,
                                session.copyFutureSalts(),
                            )
                        }
                    },
                    trafficListener = trafficListener,
                )
            } catch (failure: Throwable) {
                session.close()
                throw failure
            }
            return try {
                CloudLayer223RpcTransport(raw, cloudConfig)
            } catch (failure: Throwable) {
                raw.close()
                throw failure
            }
        }
    }
}
