package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.Authorization_dbb1508a1d
import org.monogram.mtproto.tl.generated.cloud.layer223.account.Authorizations_38b29faeb6
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetAuthorizations
import org.monogram.mtproto.tl.generated.cloud.layer223.account.ResetAuthorization
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.AcceptLoginToken
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoSessionRepositoryTest {
    @Test
    fun `accepts a valid QR login token through MTProto`() = runBlocking {
        val transport = RecordingTransport()
        val repository = MtProtoSessionRepository(MtProtoSessionTransportFactory { transport })
        val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(1, 2, 3))

        assertTrue(repository.confirmQrCode("tg://login?token=$token"))
        val method = transport.methods.single() as AcceptLoginToken
        assertEquals(byteArrayOf(1, 2, 3).toList(), method.token.toByteArray().toList())
    }

    @Test
    fun `rejects malformed QR login links before opening transport`() = runBlocking {
        var opened = false
        val repository = MtProtoSessionRepository(MtProtoSessionTransportFactory {
            opened = true
            RecordingTransport()
        })

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.confirmQrCode("https://example.com/login") }
        }
        assertEquals(false, opened)
    }

    @Test
    fun `maps authorizations and resets selected session`() = runBlocking {
        val transport = RecordingTransport()
        val repository = MtProtoSessionRepository(MtProtoSessionTransportFactory { transport })

        val sessions = repository.getActiveSessions()
        assertEquals(1, sessions.size)
        assertEquals(42L, sessions.single().id)
        assertTrue(sessions.single().isCurrent)
        assertEquals("Android", sessions.single().platform)
        assertEquals("US, CA", sessions.single().location)

        assertTrue(repository.terminateSession(99L))
        assertEquals(99L, (transport.methods[1] as ResetAuthorization).hash)
    }

    private class RecordingTransport : MtProtoRpcTransport {
        val methods = mutableListOf<TlMethod<*>>()

        override suspend fun <R> execute(method: TlMethod<R>): R {
            methods += method
            @Suppress("UNCHECKED_CAST")
            return when (method) {
                GetAuthorizations -> Authorizations_38b29faeb6(
                    authorizationTtlDays = 180,
                    authorizations = listOf(
                        Authorization_dbb1508a1d(
                            current = true,
                            officialApp = true,
                            passwordPending = false,
                            encryptedRequestsDisabled = false,
                            callRequestsDisabled = false,
                            unconfirmed = false,
                            hash = 42L,
                            deviceModel = "Pixel",
                            platform = "Android",
                            systemVersion = "14",
                            apiId = 1,
                            appName = "Monogram",
                            appVersion = "1.0",
                            dateCreated = 100,
                            dateActive = 200,
                            ip = "203.0.113.1",
                            country = "US",
                            region = "CA",
                        ),
                    ),
                ) as R
                is ResetAuthorization -> true as R
                is AcceptLoginToken -> null as R
                else -> error("Unexpected method: ${method::class.simpleName}")
            }
        }

        override fun close() = Unit
    }
}
