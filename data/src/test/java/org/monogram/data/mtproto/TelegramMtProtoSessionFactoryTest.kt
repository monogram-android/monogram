package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.handshake.MtProtoAuthKey
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoHandshakeConnection
import org.monogram.mtproto.transport.MtProtoRpcTransport
import org.monogram.mtproto.tl.runtime.TlMethod

class TelegramMtProtoSessionFactoryTest {
    @Test
    fun closesHandshakeAndTransfersKeyToEncryptedAssembler() = runBlocking {
        val endpoint = TelegramMtProtoEndpoint(2, "149.154.167.51", 443)
        val config = config(endpoint)
        val handshake = FakeHandshakeConnection()
        val authKey = authKey()
        var receivedKey: MtProtoAuthKey? = null
        val transport = FakeRpcTransport()
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
            encryptedTransportFactory = { actualEndpoint, key, _ ->
                assertEquals(endpoint, actualEndpoint)
                receivedKey = key
                transport
            },
        )

        val result = factory.open()

        assertSame(transport, result)
        assertSame(authKey, receivedKey)
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
            encryptedTransportFactory = { _, _, _ -> error("assembly failed") },
        )

        assertThrows(IllegalStateException::class.java) { runBlocking { factory.open("slot_a") } }
        assertEquals(1, handshake.closeCalls)
        assertThrows(IllegalStateException::class.java) { authKey.toByteArray() }
        Unit
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
        override suspend fun <R> execute(method: TlMethod<R>): R = error("Not used")
        override fun close() = Unit
    }
}
