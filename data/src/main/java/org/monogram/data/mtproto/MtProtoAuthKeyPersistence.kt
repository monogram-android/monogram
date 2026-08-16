package org.monogram.data.mtproto

import org.monogram.mtproto.handshake.MtProtoAuthKey
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.handshake.MtProtoHandshakeTransport

internal sealed interface PersistedMtProtoAuthKeyResult {
    data object Missing : PersistedMtProtoAuthKeyResult
    data object Corrupt : PersistedMtProtoAuthKeyResult
    data class Found(val authKey: MtProtoAuthKey) : PersistedMtProtoAuthKeyResult
}

internal class MtProtoAuthKeyPersistence(
    private val store: MtProtoAuthKeyStore,
) {
    suspend fun save(scope: MtProtoAuthKeyScope, authKey: MtProtoAuthKey) {
        val material = authKey.toByteArray()
        val stored = try {
            StoredMtProtoAuthKey.create(
                material = material,
                id = authKey.id,
                serverSalt = authKey.serverSalt,
                createdAt = authKey.createdAt,
            )
        } finally {
            material.fill(0)
        }
        try {
            store.save(scope, stored)
        } finally {
            stored.close()
        }
    }

    suspend fun load(scope: MtProtoAuthKeyScope): PersistedMtProtoAuthKeyResult = when (val result = store.load(scope)) {
        MtProtoAuthKeyLoadResult.Missing -> PersistedMtProtoAuthKeyResult.Missing
        MtProtoAuthKeyLoadResult.Corrupt -> PersistedMtProtoAuthKeyResult.Corrupt
        is MtProtoAuthKeyLoadResult.Found -> {
            val stored = result.authKey
            val material = stored.copyMaterial()
            try {
                PersistedMtProtoAuthKeyResult.Found(
                    MtProtoAuthKey.restore(
                        material = material,
                        id = stored.id,
                        serverSalt = stored.serverSalt,
                        createdAt = stored.createdAt,
                    ),
                )
            } finally {
                material.fill(0)
                stored.close()
            }
        }
    }

    suspend fun delete(scope: MtProtoAuthKeyScope) = store.delete(scope)

    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) =
        store.deleteAccount(accountSlot, environment)
}

internal enum class MtProtoAuthKeySource {
    STORED,
    ESTABLISHED,
}

internal data class BootstrappedMtProtoAuthKey(
    val authKey: MtProtoAuthKey,
    val source: MtProtoAuthKeySource,
)

internal fun interface MtProtoAuthKeyEstablisher {
    suspend fun establish(transport: MtProtoHandshakeTransport, config: MtProtoHandshakeConfig): MtProtoAuthKey
}

internal class MtProtoAuthKeySessionBootstrap(
    private val persistence: MtProtoAuthKeyPersistence,
    private val establisher: MtProtoAuthKeyEstablisher,
) {
    suspend fun loadOrEstablish(
        scope: MtProtoAuthKeyScope,
        transport: MtProtoHandshakeTransport,
        config: MtProtoHandshakeConfig,
    ): BootstrappedMtProtoAuthKey {
        require(scope.dcId == config.dcId) { "MTProto auth key scope and handshake DC must match" }
        when (val persisted = persistence.load(scope)) {
            is PersistedMtProtoAuthKeyResult.Found -> return BootstrappedMtProtoAuthKey(
                persisted.authKey,
                MtProtoAuthKeySource.STORED,
            )
            PersistedMtProtoAuthKeyResult.Missing,
            PersistedMtProtoAuthKeyResult.Corrupt,
            -> Unit
        }
        val established = establisher.establish(transport, config)
        try {
            persistence.save(scope, established)
            return BootstrappedMtProtoAuthKey(established, MtProtoAuthKeySource.ESTABLISHED)
        } catch (failure: Throwable) {
            established.close()
            throw failure
        }
    }
}
