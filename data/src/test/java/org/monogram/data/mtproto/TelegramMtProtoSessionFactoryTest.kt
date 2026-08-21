package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.handshake.MtProtoAuthKey
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.ExportAuthorization
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.ExportedAuthorization_cdf68dd957
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.ImportAuthorization
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoHandshakeConnection
import org.monogram.mtproto.transport.MtProtoRpcTransport
import org.monogram.mtproto.transport.MtProtoSessionMaintenance
import org.monogram.mtproto.tl.runtime.TlMethod

class TelegramMtProtoSessionFactoryTest {
    @Test
    fun usesTDLibProductionFallbackForDcFive() {
        assertEquals(
            TelegramMtProtoEndpoint(5, "149.154.171.5", 443),
            telegramMtProtoEndpointForDc(5),
        )
    }

    @Test
    fun closesHandshakeAndTransfersKeyToEncryptedAssembler() = runBlocking {
        val endpoint = TelegramMtProtoEndpoint(2, "149.154.167.51", 443)
        val config = config(endpoint)
        val handshake = FakeHandshakeConnection()
        val authKey = authKey()
        var receivedKey: MtProtoAuthKey? = null
        val transport = FakeRpcTransport()
        val projectionStore = RecordingUserProjectionStore()
        val chatProjectionStore = RecordingChatProjectionStore()
        val messageProjectionStore = RecordingMessageProjectionStore()
        val factory = TelegramMtProtoSessionFactory(
            configSource = TelegramMtProtoBootstrapConfigSource { config },
            keyLoader = MtProtoAuthKeyLoader { scope, connection, handshakeConfig ->
                assertEquals("default", scope.accountSlot)
                assertEquals(2, scope.dcId)
                assertSame(handshake, connection)
                assertEquals(2, handshakeConfig.dcId)
                BootstrappedMtProtoAuthKey(authKey, MtProtoAuthKeySource.ESTABLISHED)
            },
            handshakeConnectionFactory = { handshake },
            encryptedTransportFactory = { _, actualEndpoint, key, _ ->
                assertEquals(endpoint, actualEndpoint)
                receivedKey = key
                transport
            },
            userProjectionStore = projectionStore,
            chatProjectionStore = chatProjectionStore,
            messageProjectionStore = messageProjectionStore,
        )

        val result = factory.open()

        assertSame(transport, result)
        assertSame(authKey, receivedKey)
        assertEquals(
            listOf(MtProtoAuthKeyScope("default", MtProtoEnvironment.PRODUCTION, 2)),
            projectionStore.backfilledScopes,
        )
        assertEquals(
            listOf(MtProtoAuthKeyScope("default", MtProtoEnvironment.PRODUCTION, 2)),
            chatProjectionStore.backfilledScopes,
        )
        assertEquals(
            listOf(MtProtoAuthKeyScope("default", MtProtoEnvironment.PRODUCTION, 2)),
            messageProjectionStore.backfilledScopes,
        )
        assertEquals(1, handshake.closeCalls)
        assertTrue(handshake.closed)
        val copy = authKey.toByteArray()
        try {
            assertTrue(copy.isNotEmpty())
        } finally {
            copy.fill(0)
            authKey.close()
        }
    }

    @Test
    fun importsHomeAuthorizationBeforeUsingSecondaryDc() = runBlocking {
        val homeTransport = RecordingAuthTransport()
        val secondaryTransport = RecordingAuthTransport()
        val exportedBytes = byteArrayOf(4, 8, 15, 16)
        val configs = mapOf(
            2 to config(TelegramMtProtoEndpoint(2, "home", 443)),
            3 to config(TelegramMtProtoEndpoint(3, "secondary", 443)),
        )
        val factory = TelegramMtProtoSessionFactory(
            configSource = object : TelegramMtProtoBootstrapConfigSource {
                override suspend fun create() = configs.getValue(2)
                override suspend fun createForDc(dcId: Int) = configs.getValue(dcId)
                override suspend fun createForAccount(accountSlot: String) = configs.getValue(2)
            },
            keyLoader = MtProtoAuthKeyLoader { _, _, _ ->
                BootstrappedMtProtoAuthKey(authKey(), MtProtoAuthKeySource.STORED)
            },
            handshakeConnectionFactory = { FakeHandshakeConnection() },
            encryptedTransportFactory = { _, endpoint, _, _ ->
                if (endpoint.dcId == 2) homeTransport else secondaryTransport
            },
        )
        homeTransport.exported = ExportedAuthorization_cdf68dd957(42L, TlBytes.copyOf(exportedBytes))

        val result = factory.open("default", 3)

        assertSame(secondaryTransport, result)
        assertEquals(listOf(3), homeTransport.exportedForDcIds)
        assertEquals(listOf(ImportAuthorization(42L, TlBytes.copyOf(exportedBytes))), secondaryTransport.imported)
        assertEquals(1, homeTransport.closeCalls)
        exportedBytes.fill(0)
        secondaryTransport.close()
    }

    @Test
    fun refreshesFutureSaltsWhenTransportSupportsMaintenance() = runBlocking {
        val authKey = authKey()
        val transport = MaintenanceRpcTransport()
        val factory = TelegramMtProtoSessionFactory(
            configSource = TelegramMtProtoBootstrapConfigSource { config(TelegramMtProtoEndpoint(2, "dc", 443)) },
            keyLoader = MtProtoAuthKeyLoader { _, _, _ -> BootstrappedMtProtoAuthKey(authKey, MtProtoAuthKeySource.STORED) },
            handshakeConnectionFactory = { FakeHandshakeConnection() },
            encryptedTransportFactory = { _, _, _, _ -> transport },
        )
        try {
            factory.open()
            assertEquals(1, transport.futureSaltRefreshes)
        } finally {
            transport.close()
            authKey.close()
        }
    }

    @Test
    fun ignoresFutureSaltRefreshFailureDuringOpen() = runBlocking {
        val authKey = authKey()
        val transport = MaintenanceRpcTransport(failRefresh = true)
        val factory = TelegramMtProtoSessionFactory(
            configSource = TelegramMtProtoBootstrapConfigSource { config(TelegramMtProtoEndpoint(2, "dc", 443)) },
            keyLoader = MtProtoAuthKeyLoader { _, _, _ -> BootstrappedMtProtoAuthKey(authKey, MtProtoAuthKeySource.STORED) },
            handshakeConnectionFactory = { FakeHandshakeConnection() },
            encryptedTransportFactory = { _, _, _, _ -> transport },
        )
        try {
            assertSame(transport, factory.open())
            assertEquals(1, transport.futureSaltRefreshes)
        } finally {
            transport.close()
            authKey.close()
        }
    }

    @Test
    fun closesHandshakeOnceWhenKeyLoadingFails() {
        val handshake = FakeHandshakeConnection()
        val factory = TelegramMtProtoSessionFactory(
            configSource = TelegramMtProtoBootstrapConfigSource {
                config(TelegramMtProtoEndpoint(2, "dc", 443))
            },
            keyLoader = MtProtoAuthKeyLoader { _, _, _ -> error("load failed") },
            handshakeConnectionFactory = { handshake },
        )

        assertThrows(IllegalStateException::class.java) { runBlocking { factory.open() } }
        assertEquals(1, handshake.closeCalls)
    }

    @Test
    fun closesKeyWhenEncryptedAssemblyFails() = runBlocking {
        val authKey = authKey()
        val handshake = FakeHandshakeConnection()
        val factory = TelegramMtProtoSessionFactory(
            configSource = TelegramMtProtoBootstrapConfigSource { config(TelegramMtProtoEndpoint(2, "dc", 443)) },
            keyLoader = MtProtoAuthKeyLoader { _, _, _ ->
                BootstrappedMtProtoAuthKey(authKey, MtProtoAuthKeySource.STORED)
            },
            handshakeConnectionFactory = { handshake },
            encryptedTransportFactory = { _, _, _, _ -> error("assembly failed") },
        )

        assertThrows(IllegalStateException::class.java) { runBlocking { factory.open("slot_a") } }
        assertEquals(1, handshake.closeCalls)
        assertThrows(IllegalStateException::class.java) { authKey.toByteArray() }
        Unit
    }

    @Test
    fun closesEncryptedTransportWhenProjectionBackfillFails() = runBlocking {
        val failure = IllegalStateException("backfill failed")
        val authKey = authKey()
        val transport = FakeRpcTransport()
        val factory = TelegramMtProtoSessionFactory(
            configSource = TelegramMtProtoBootstrapConfigSource { config(TelegramMtProtoEndpoint(2, "dc", 443)) },
            keyLoader = MtProtoAuthKeyLoader { _, _, _ ->
                BootstrappedMtProtoAuthKey(authKey, MtProtoAuthKeySource.STORED)
            },
            userProjectionStore = object : MtProtoUserProjectionStore by NoOpMtProtoUserProjectionStore {
                override suspend fun backfill(scope: MtProtoAuthKeyScope): MtProtoUserProjectionBackfillResult {
                    throw failure
                }
            },
            handshakeConnectionFactory = { FakeHandshakeConnection() },
            encryptedTransportFactory = { _, _, _, _ -> transport },
        )

        val thrown = assertThrows(IllegalStateException::class.java) { runBlocking { factory.open("slot_a") } }

        assertSame(failure, thrown)
        assertEquals(1, transport.closeCalls)
        authKey.close()
    }

    private fun config(endpoint: TelegramMtProtoEndpoint) = TelegramMtProtoBootstrapConfig(
        endpoint = endpoint,
        handshake = MtProtoHandshakeConfig(endpoint.dcId, listOf("test-key")),
        cloud = CloudLayer223ConnectionConfig(
            apiId = 12345,
            deviceModel = "device",
            systemVersion = "system",
            applicationVersion = "app",
            systemLanguageCode = "en",
        ),
    )

    private fun authKey(): MtProtoAuthKey {
        val material = ByteArray(MtProtoAuthKey.MATERIAL_BYTES) { it.toByte() }
        return try {
            MtProtoAuthKey.restore(material, StoredMtProtoAuthKey.calculateId(material), 73L, 1_783_001_185)
        } finally {
            material.fill(0)
        }
    }

    private class FakeHandshakeConnection : MtProtoHandshakeConnection {
        var closeCalls = 0
        var closed = false

        override suspend fun <R : org.monogram.mtproto.tl.runtime.TlObject> execute(method: TlMethod<R>): R =
            error("Not used")

        override fun close() {
            closeCalls += 1
            closed = true
        }
    }

    private class FakeRpcTransport : MtProtoRpcTransport {
        var closeCalls = 0

        override suspend fun <R> execute(method: TlMethod<R>): R = error("Not used")
        override fun close() {
            closeCalls += 1
        }
    }

    private class RecordingAuthTransport : MtProtoRpcTransport {
        var closeCalls = 0
        var exported: ExportedAuthorization_cdf68dd957? = null
        val exportedForDcIds = mutableListOf<Int>()
        val imported = mutableListOf<ImportAuthorization>()

        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R = when (method) {
            is ExportAuthorization -> {
                exportedForDcIds += method.dcId
                requireNotNull(exported) as R
            }
            is ImportAuthorization -> {
                imported += method
                null as R
            }
            else -> error("Unexpected method ${method::class.java.simpleName}")
        }

        override fun close() {
            closeCalls++
        }
    }

    private class MaintenanceRpcTransport(
        private val failRefresh: Boolean = false,
    ) : MtProtoRpcTransport, MtProtoSessionMaintenance {
        var futureSaltRefreshes = 0

        override suspend fun refreshFutureSalts() {
            futureSaltRefreshes++
            check(!failRefresh) { "refresh failed" }
        }

        override suspend fun <R> execute(method: TlMethod<R>): R = error("Not used")
        override fun close() = Unit
    }

    private class RecordingUserProjectionStore : MtProtoUserProjectionStore by NoOpMtProtoUserProjectionStore {
        val backfilledScopes = mutableListOf<MtProtoAuthKeyScope>()

        override suspend fun backfill(scope: MtProtoAuthKeyScope): MtProtoUserProjectionBackfillResult {
            backfilledScopes += scope
            return MtProtoUserProjectionBackfillResult(0, 0)
        }
    }

    private class RecordingChatProjectionStore : MtProtoChatProjectionStore by NoOpMtProtoChatProjectionStore {
        val backfilledScopes = mutableListOf<MtProtoAuthKeyScope>()

        override suspend fun backfill(scope: MtProtoAuthKeyScope): MtProtoChatProjectionBackfillResult {
            backfilledScopes += scope
            return MtProtoChatProjectionBackfillResult(0, 0)
        }
    }

    private class RecordingMessageProjectionStore : MtProtoMessageProjectionStore by NoOpMtProtoMessageProjectionStore {
        val backfilledScopes = mutableListOf<MtProtoAuthKeyScope>()

        override suspend fun backfill(scope: MtProtoAuthKeyScope): MtProtoMessageProjectionBackfillResult {
            backfilledScopes += scope
            return MtProtoMessageProjectionBackfillResult(0, 0)
        }
    }
}
