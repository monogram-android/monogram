package org.monogram.mtproto.handshake

import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.crypto.RsaPublicKeyTest
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject

class MtProtoAuthHandshakeTest {
    @Test
    fun mapsTimeoutWithoutSwallowingCancellationSemantics() {
        val transport = object : MtProtoHandshakeTransport {
            override suspend fun <R : TlObject> execute(method: TlMethod<R>): R = awaitCancellation()
        }
        val failure = assertThrows(MtProtoHandshakeException::class.java) {
            runBlocking {
                MtProtoAuthHandshake().execute(transport, config(timeoutMillis = 10))
            }
        }
        assertEquals(MtProtoHandshakeFailure.TIMEOUT, failure.failure)
    }

    @Test
    fun mapsTransportFailuresAtThePublicBoundary() {
        val transport = object : MtProtoHandshakeTransport {
            override suspend fun <R : TlObject> execute(method: TlMethod<R>): R {
                throw IllegalStateException("offline")
            }
        }
        val failure = assertThrows(MtProtoHandshakeException::class.java) {
            runBlocking { MtProtoAuthHandshake().execute(transport, config()) }
        }
        assertEquals(MtProtoHandshakeFailure.TRANSPORT, failure.failure)
    }

    @Test
    fun mapsCheckedTransportFailuresAtThePublicBoundary() {
        val transport = object : MtProtoHandshakeTransport {
            override suspend fun <R : TlObject> execute(method: TlMethod<R>): R {
                throw IOException("socket closed")
            }
        }
        val failure = assertThrows(MtProtoHandshakeException::class.java) {
            runBlocking { MtProtoAuthHandshake().execute(transport, config()) }
        }
        assertEquals(MtProtoHandshakeFailure.TRANSPORT, failure.failure)
    }

    @Test
    fun validatesPublicConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { MtProtoHandshakeConfig(0, listOf("key")) }
        assertThrows(IllegalArgumentException::class.java) { MtProtoHandshakeConfig(2, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { MtProtoHandshakeConfig(2, listOf("key"), 0) }
    }

    private fun config(timeoutMillis: Long = 30_000) = MtProtoHandshakeConfig(
        dcId = 2,
        serverRsaPublicKeys = listOf(RsaPublicKeyTest.PEM),
        timeoutMillis = timeoutMillis,
    )
}
