package org.monogram.data.mtproto

import org.monogram.data.BuildConfig
import org.monogram.data.infra.TelegramClientMetadataProvider
import org.monogram.mtproto.handshake.MtProtoAuthKey
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.CloudLayer223RpcTransport
import org.monogram.mtproto.transport.IntermediateTcpEncryptedTransport
import org.monogram.mtproto.transport.IntermediateTcpHandshakeTransport
import org.monogram.mtproto.transport.MtProtoHandshakeConnection
import org.monogram.mtproto.transport.MtProtoRpcTransport
import org.monogram.mtproto.transport.MtProtoEncryptedSession

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
    fun create(): TelegramMtProtoBootstrapConfig

    fun createForDc(dcId: Int): TelegramMtProtoBootstrapConfig {
        val config = create()
        require(config.endpoint.dcId == dcId) { "MTProto bootstrap source does not support DC $dcId" }
        return config
    }
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
) : TelegramMtProtoBootstrapConfigSource {
    override fun create(): TelegramMtProtoBootstrapConfig = createForDc(DEFAULT_DC_ID)

    override fun createForDc(dcId: Int): TelegramMtProtoBootstrapConfig {
        require(BuildConfig.API_ID > 0) { "API_ID must be configured before starting MTProto" }
        val metadata = metadataProvider.create()
        val endpoint = endpointForDc(dcId)
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

    private companion object {
        const val DEFAULT_DC_ID = 2

        fun endpointForDc(dcId: Int): TelegramMtProtoEndpoint = when (dcId) {
            1 -> TelegramMtProtoEndpoint(dcId, "149.154.175.50", 443)
            2 -> TelegramMtProtoEndpoint(dcId, "149.154.167.51", 443)
            3 -> TelegramMtProtoEndpoint(dcId, "149.154.175.100", 443)
            4 -> TelegramMtProtoEndpoint(dcId, "149.154.167.91", 443)
            5 -> TelegramMtProtoEndpoint(dcId, "91.108.56.130", 443)
            else -> throw IllegalArgumentException("Unsupported Telegram production DC: $dcId")
        }

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

internal class TelegramMtProtoSessionFactory(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val keyLoader: MtProtoAuthKeyLoader,
    private val authKeyPersistence: MtProtoAuthKeyPersistence? = null,
    private val userProjectionStore: MtProtoUserProjectionStore = NoOpMtProtoUserProjectionStore,
    private val chatProjectionStore: MtProtoChatProjectionStore = NoOpMtProtoChatProjectionStore,
    private val messageProjectionStore: MtProtoMessageProjectionStore = NoOpMtProtoMessageProjectionStore,
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
        )
    },
) {
    suspend fun open(accountSlot: String = DEFAULT_ACCOUNT_SLOT): MtProtoRpcTransport = open(accountSlot, null)

    suspend fun open(accountSlot: String, dcId: Int?): MtProtoRpcTransport {
        val config = dcId?.let(configSource::createForDc) ?: configSource.create()
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
        return try {
            userProjectionStore.backfill(scope)
            chatProjectionStore.backfill(scope)
            messageProjectionStore.backfill(scope)
            transport
        } catch (failure: Throwable) {
            transport.close()
            throw failure
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"

        fun createEncryptedTransport(
            scope: MtProtoAuthKeyScope,
            endpoint: TelegramMtProtoEndpoint,
            authKey: MtProtoAuthKey,
            cloudConfig: CloudLayer223ConnectionConfig,
            authKeyPersistence: MtProtoAuthKeyPersistence,
        ): MtProtoRpcTransport {
            val session = try {
                MtProtoEncryptedSession(authKey)
            } catch (failure: Throwable) {
                authKey.close()
                throw failure
            }
            val raw = try {
                IntermediateTcpEncryptedTransport(
                    host = endpoint.host,
                    port = endpoint.port,
                    session = session,
                    onServerSaltChanged = { serverSalt ->
                        authKeyPersistence.updateServerSalt(scope, authKey, serverSalt)
                    },
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
